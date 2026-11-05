package app.ledgerly.core.dedup

import app.ledgerly.core.model.Channel.EMAIL
import app.ledgerly.core.model.Channel.NOTIFICATION
import app.ledgerly.core.model.Channel.SMS
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DedupEngineTest {
    private val engine = DedupEngine()

    /** Simulates the app: match -> merge or insert. */
    private fun ingest(store: MutableList<Transaction>, c: Candidate, e: DedupEngine = engine): MatchResult {
        val r = e.match(c, store)
        when (r) {
            is MatchResult.MergeInto -> {
                val i = store.indexOfFirst { it.id == r.txnId }
                store[i] = Merger.merge(store[i], c).transaction
            }
            else -> store += c.toTxn("t${store.size + 1}")
        }
        return r
    }

    @Test fun `HDFC card swipe via SMS notification and Gmail merges into one entry`() {
        val store = mutableListOf<Transaction>()
        val sms = cand("sms1", SMS, inr("1249.00"), at(10, 20, 15), "SWIGGY BANGALORE", ref = "407812345678",
            text = "Rs.1249.00 spent on HDFC Bank Card x4521 at SWIGGY BANGALORE on 2026-03-10:20:15:00. Avl bal Rs.48,210", balance = inr("48210.00"))
        val notif = cand("n1", NOTIFICATION, inr("1249.00"), at(10, 20, 15, 40), "Swiggy", pkg = "com.snapwork.hdfc")
        val email = cand("e1", EMAIL, inr("1249.00"), at(10, 20, 19), "Swiggy Limited", last4 = null, accountId = "hdfc-cc")
        assertIs<MatchResult.NewTransaction>(ingest(store, sms))
        val r2 = ingest(store, notif)
        assertIs<MatchResult.MergeInto>(r2)
        assertTrue(r2.confidence >= 0.9)
        assertTrue(MatchReason.DIFFERENT_CHANNEL in r2.reasons)
        assertIs<MatchResult.MergeInto>(ingest(store, email))
        val t = store.single()
        assertEquals(setOf(SMS, NOTIFICATION, EMAIL), t.sourceChannels)
        assertEquals(listOf("sms1", "n1", "e1"), t.sourceEventIds)
        assertEquals("Swiggy Limited", t.merchantRaw) // email wins merchant
        assertEquals("407812345678", t.reference)     // SMS reference kept
        assertEquals(at(10, 20, 15), t.occurredAt)     // earliest
        assertEquals("SMS + Notification + Email", SourceBadge.text(t))
    }

    @Test fun `channels arriving in reverse order still merge`() {
        val store = mutableListOf<Transaction>()
        ingest(store, cand("e1", EMAIL, inr("560.00"), at(10, 9, 2), "Uber India Systems", accountId = "hdfc-cc", last4 = null))
        val r = ingest(store, cand("s1", SMS, inr("560.00"), at(10, 9, 0), "UBER INDIA", ref = "RRN998877"))
        assertIs<MatchResult.MergeInto>(r)
        assertEquals("Uber India Systems", store.single().merchantRaw)
        assertEquals("RRN998877", store.single().reference)
        assertEquals(at(10, 9, 0), store.single().occurredAt)
    }

    @Test fun `two genuine identical coffees 20 minutes apart do not merge`() {
        val store = mutableListOf<Transaction>()
        val text = "Rs.250.00 spent on HDFC Bank Card x4521 at STARBUCKS COFFEE"
        ingest(store, cand("s1", SMS, inr("250.00"), at(10, 8, 30), "STARBUCKS COFFEE", text = text))
        val r = ingest(store, cand("s2", SMS, inr("250.00"), at(10, 8, 50), "STARBUCKS COFFEE", text = text))
        assertIs<MatchResult.NewTransaction>(r)
        // and the second coffee's notification lands on the second coffee, not the first
        val n = engine.match(cand("n2", NOTIFICATION, inr("250.00"), at(10, 8, 50, 20), "Starbucks"), store)
        assertIs<MatchResult.MergeInto>(n)
        assertEquals("t2", n.txnId)
        assertEquals(2, store.size)
    }

    @Test fun `two coffees 5 minutes apart on same channel with different text do not merge`() {
        val store = mutableListOf<Transaction>()
        ingest(store, cand("s1", SMS, inr("250.00"), at(10, 8, 30), "STARBUCKS", text = "Rs.250 at STARBUCKS. Bal 9750"))
        val r = ingest(store, cand("s2", SMS, inr("250.00"), at(10, 8, 35), "STARBUCKS", text = "Rs.250 at STARBUCKS. Bal 9500"))
        assertIs<MatchResult.NewTransaction>(r)
    }

    @Test fun `redelivered identical SMS is an exact duplicate`() {
        val texts = mapOf("s1" to "Rs.99 debited at JIOMART  ref 123")
        val e = DedupEngine(sourceText = { texts[it.removePrefix("ev-")] ?: texts[it] })
        val store = mutableListOf(cand("s1", SMS, inr("99.00"), at(10, 11), "JIOMART", text = texts["s1"]).toTxn("t1"))
        val r = e.match(cand("s1b", SMS, inr("99.00"), at(10, 11, 1), "JIOMART", text = "rs.99 debited at jiomart ref 123"), store)
        assertIs<MatchResult.MergeInto>(r)
        assertEquals(MatchReason.EXACT_TEXT_REDELIVERY, r.reasons.first())
    }

    @Test fun `same source event id is idempotent`() {
        val c = cand("s1", SMS, inr("99.00"), at(10, 11), "JIOMART")
        val r = engine.match(c, listOf(c.toTxn("t1")))
        assertEquals(MatchResult.MergeInto("t1", 1.0, listOf(MatchReason.SAME_SOURCE_EVENT)), r)
    }

    @Test fun `notification update from same app merges`() {
        val e = DedupEngine(sourcePackage = { "net.one97.paytm" })
        val store = listOf(cand("n1", NOTIFICATION, inr("300.00"), at(10, 12), "Ramesh Kirana", pkg = "net.one97.paytm").toTxn("t1"))
        val r = e.match(cand("n2", NOTIFICATION, inr("300.00"), at(10, 12, 1), "Ramesh Kirana", pkg = "net.one97.paytm"), store)
        assertIs<MatchResult.MergeInto>(r)
        assertEquals(MatchReason.NOTIFICATION_UPDATE, r.reasons.first())
    }

    @Test fun `UPI with identical reference merges regardless of time and merchant spelling`() {
        val store = listOf(txn("t1", inr("1500.00"), at(10, 14), "rameshkumar@okaxis", accountId = "sbi", ref = "612345678901"))
        val c = cand("g1", NOTIFICATION, inr("1500.00"), at(10, 16), "Ramesh Kumar", accountId = "sbi", last4 = null,
            ref = "UPI Ref 6123 4567 8901", kind = MessageKind.UPI_SENT)
        val r = engine.match(c, store)
        assertIs<MatchResult.MergeInto>(r)
        assertEquals(0.99, r.confidence)
        assertEquals(MatchReason.EXACT_REFERENCE, r.reasons.first())
    }

    @Test fun `same reference but different amount needs review`() {
        val store = listOf(txn("t1", inr("1500.00"), at(10, 14), "X", accountId = "sbi", ref = "612345678901"))
        val r = engine.match(cand("g1", SMS, inr("150.00"), at(10, 14), "X", accountId = "sbi", last4 = null, ref = "612345678901"), store)
        assertIs<MatchResult.PossibleDuplicate>(r)
    }

    @Test fun `different card last4 never merges`() {
        val store = listOf(txn("t1", inr("500.00"), at(10, 10), "AMAZON", accountId = null).copy(accountLast4 = "1111"))
        val r = engine.match(cand("n1", NOTIFICATION, inr("500.00"), at(10, 10), "Amazon", accountId = null, last4 = "2222"), store)
        assertIs<MatchResult.NewTransaction>(r)
    }

    @Test fun `debit never merges with a credit`() {
        val store = listOf(txn("t1", inr("500.00"), at(10, 10), "AMAZON", type = TxnType.REFUND))
        assertIs<MatchResult.NewTransaction>(engine.match(cand("n1", NOTIFICATION, inr("500.00"), at(10, 10), "Amazon"), store))
    }

    @Test fun `foreign spend matches across currencies`() {
        // Notification shows only INR; SMS shows USD 45 (INR 4,212.30)
        val store = mutableListOf<Transaction>()
        ingest(store, cand("n1", NOTIFICATION, inr("4212.30"), at(10, 3, 5), "OPENAI"))
        val sms = cand("s1", SMS, usd("45.00"), at(10, 3, 5, 30), "OPENAI *CHATGPT SUBSCR", accountAmount = inr("4212.30"))
        val r = ingest(store, sms)
        assertIs<MatchResult.MergeInto>(r)
        assertTrue(MatchReason.SAME_ACCOUNT_AMOUNT in r.reasons)
        val t = store.single()
        assertEquals(usd("45.00"), t.original)
        assertEquals(inr("4212.30"), t.account)
    }

    @Test fun `original amount match when account amounts differ in representation`() {
        val store = listOf(txn("t1", usd("45.00"), at(10, 3), "OPENAI", account = inr("4212.30")))
        val r = engine.match(cand("e1", EMAIL, usd("45.00"), at(10, 5), "OpenAI, LLC"), store)
        assertIs<MatchResult.MergeInto>(r)
        assertTrue(MatchReason.SAME_AMOUNT in r.reasons)
    }

    @Test fun `email statement channel uses the wider 3-day window`() {
        val store = listOf(txn("t1", inr("2399.00"), at(8, 19), "MYNTRA DESIGNS"))
        val r = engine.match(cand("e1", EMAIL, inr("2399.00"), at(10, 9), "Myntra"), store)
        assertIs<MatchResult.MergeInto>(r)
        val r2 = engine.match(cand("n1", NOTIFICATION, inr("2399.00"), at(10, 9), "Myntra"), store)
        assertIs<MatchResult.NewTransaction>(r2)
    }

    @Test fun `different merchant same amount and time is only a review card`() {
        val store = listOf(txn("t1", inr("500.00"), at(10, 10), "BIG BAZAAR"))
        val r = engine.match(cand("n1", NOTIFICATION, inr("500.00"), at(10, 10, 2), "Indian Oil Petrol"), store)
        assertIs<MatchResult.PossibleDuplicate>(r)
        assertTrue(MatchReason.MERCHANT_DIFFERENT in r.reasons)
    }

    @Test fun `ambiguous between two equal existing entries downgrades to review`() {
        val store = listOf(
            txn("t1", inr("250.00"), at(10, 8, 30), "STARBUCKS", channels = setOf(SMS)),
            txn("t2", inr("250.00"), at(10, 8, 30), "STARBUCKS", channels = setOf(SMS)),
        )
        val r = engine.match(cand("n1", NOTIFICATION, inr("250.00"), at(10, 8, 31), "Starbucks"), store)
        assertIs<MatchResult.PossibleDuplicate>(r)
        assertTrue(MatchReason.AMBIGUOUS in r.reasons)
    }

    @Test fun `thresholds and windows are configurable`() {
        val store = listOf(txn("t1", inr("250.00"), at(10, 8, 30), "STARBUCKS"))
        val c = cand("n1", NOTIFICATION, inr("250.00"), at(10, 8, 50), "Starbucks")
        assertIs<MatchResult.NewTransaction>(engine.match(c, store))
        val wide = DedupEngine(DedupConfig(timeWindow = Duration.ofMinutes(30)))
        assertIs<MatchResult.MergeInto>(wide.match(c, store))
        val strict = DedupEngine(DedupConfig(wDifferentChannel = 0.0, wMerchant = 0.0, mergeThreshold = 0.95, reviewThreshold = 0.6))
        assertIs<MatchResult.PossibleDuplicate>(strict.match(cand("n1", NOTIFICATION, inr("250.00"), at(10, 8, 31), "Starbucks"), store))
    }

    @Test fun `deleted transactions are ignored`() {
        val store = listOf(txn("t1", inr("250.00"), at(10, 8, 30), "STARBUCKS").copy(deletedAt = at(10, 9)))
        assertIs<MatchResult.NewTransaction>(engine.match(cand("n1", NOTIFICATION, inr("250.00"), at(10, 8, 31), "Starbucks"), store))
    }

    @Test fun `conflicting references reduce confidence`() {
        val store = listOf(txn("t1", inr("250.00"), at(10, 8, 30), "STARBUCKS", ref = "111122223333"))
        val r = engine.match(cand("n1", NOTIFICATION, inr("250.00"), at(10, 8, 31), "Starbucks", ref = "999988887777"), store)
        assertIs<MatchResult.PossibleDuplicate>(r)
        assertTrue(MatchReason.REFERENCE_CONFLICT in r.reasons)
    }

    @Test fun `missing merchant on one side still merges on amount card and time`() {
        val store = listOf(txn("t1", inr("777.00"), at(10, 13), null))
        val r = engine.match(cand("n1", NOTIFICATION, inr("777.00"), at(10, 13, 1), "DMart"), store)
        assertIs<MatchResult.MergeInto>(r)
        assertTrue(MatchReason.MERCHANT_UNKNOWN in r.reasons)
    }

    @Test fun `candidate from parsed transaction`() {
        val p = app.ledgerly.core.model.ParsedTransaction(
            sourceEventId = "ev1", channel = SMS, kind = MessageKind.UPI_RECEIVED, direction = TxnDirection.CREDIT,
            amount = inr("100.00"), occurredAt = at(1, 1), confidence = 0.9, parserId = "hdfc-upi",
            status = app.ledgerly.core.model.ParseStatus.PARSED, merchantRaw = "A", reference = "R1234",
        )
        val c = Candidate.from(p, "acc")!!
        assertEquals("acc", c.accountId)
        assertTrue(c.isCredit)
        assertEquals(TxnType.INCOME, c.suggestedType)
        assertEquals(null, Candidate.from(p.copy(amount = null), "acc"))
    }
}
