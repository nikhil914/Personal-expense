package app.ledgerly.core.audit

import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerTest {
    private val rec = Reconciler(IST)

    private fun baseTxns() = listOf(
        txn("t1", "2000", whenAt = at(2026, 10, 5, 10)),
        txn("t2", "5000", TxnType.INCOME, whenAt = at(2026, 10, 10, 9)),
        txn("t3", "760", whenAt = at(2026, 10, 20, 12)),
    )

    @Test fun perfectlyReconciledScores100() {
        val r = rec.reconcile(AccountReconInput("acc1", inr("10000"), baseTxns(), listOf(
            BalanceSnapshot(at(2026, 10, 5, 11), inr("8000")),
            BalanceSnapshot(at(2026, 10, 12, 9), inr("13000")),
            BalanceSnapshot(at(2026, 10, 20, 13), inr("12240")),
        )))
        assertEquals(100, r.score)
        assertTrue(r.gaps.isEmpty())
        assertEquals(listOf(inr("8000"), inr("13000"), inr("12240")), r.checks.map { it.expected })
    }

    @Test fun missingCashWithdrawalShowsGap() {
        val r = rec.reconcile(AccountReconInput("acc1", inr("10000"), baseTxns(), listOf(
            BalanceSnapshot(at(2026, 10, 5, 11), inr("8000")),
            BalanceSnapshot(at(2026, 10, 12, 9), inr("13000")),
            BalanceSnapshot(at(2026, 10, 20, 13), inr("11000")), // ₹1,240 ATM withdrawal never recorded
        )))
        val gap = r.gaps.single()
        assertEquals(inr("-1240"), gap.amount)
        assertEquals("₹1,240 unexplained since 12 Oct", gap.message)
        assertEquals(inr("12240"), r.checks[2].expected)
        assertEquals(inr("11000"), r.checks[2].observed)
        // movement 2000 + 5000 + (760 + 1240) = 9000, unexplained 1240 → 86%
        assertEquals(inr("9000"), r.totalMovement)
        assertEquals(86, r.score)
    }

    @Test fun gapIsNotDoubleCountedAtLaterSnapshots() {
        val txns = baseTxns() + txn("t4", "500", whenAt = at(2026, 10, 24))
        val r = rec.reconcile(AccountReconInput("acc1", inr("10000"), txns, listOf(
            BalanceSnapshot(at(2026, 10, 20, 13), inr("11000")),
            BalanceSnapshot(at(2026, 10, 25), inr("10500")),
        )))
        assertEquals(1, r.gaps.size)
        assertEquals(inr("-1240"), r.checks[1].cumulativeGap)
        assertTrue(r.checks[1].intervalGap.isZero)
    }

    @Test fun recordingTheMissingTransactionClosesTheGap() {
        val txns = baseTxns() + txn("atm", "1240", TxnType.ATM_WITHDRAWAL, whenAt = at(2026, 10, 15))
        val r = rec.reconcile(AccountReconInput("acc1", inr("10000"), txns, listOf(
            BalanceSnapshot(at(2026, 10, 12, 9), inr("13000")),
            BalanceSnapshot(at(2026, 10, 20, 13), inr("11000")),
        )))
        assertEquals(100, r.score)
    }

    @Test fun ignoresDeletedFailedAndCountsUnverified() {
        val f = Fixture()
        val txns = listOf(
            txn("ok", "100", whenAt = at(2026, 10, 1)),
            txn("del", "999", whenAt = at(2026, 10, 1)).copy(deletedAt = f.clock.now),
            txn("fail", "999", whenAt = at(2026, 10, 1)).copy(status = TxnStatus.FAILED),
            txn("later", "50", whenAt = at(2026, 10, 30)),
        )
        val r = rec.reconcile(AccountReconInput("acc1", inr("1000"), txns, listOf(BalanceSnapshot(at(2026, 10, 2), inr("900")))))
        assertEquals(100, r.score)
        assertEquals(1, r.unverifiedTransactions)
    }

    @Test fun toleranceAbsorbsRounding() {
        val r = Reconciler(IST, toleranceMinor = 100).reconcile(AccountReconInput("acc1", inr("1000"),
            listOf(txn("a", "100.40", whenAt = at(2026, 10, 1))), listOf(BalanceSnapshot(at(2026, 10, 2), inr("900")))))
        assertTrue(r.gaps.isEmpty())
    }

    @Test fun overallScoreWeightsAccountsByMovement() {
        val good = AccountReconInput("a", inr("1000"), listOf(txn("x", "9000", TxnType.INCOME, whenAt = at(2026, 10, 1))),
            listOf(BalanceSnapshot(at(2026, 10, 2), inr("10000"))))
        val bad = AccountReconInput("b", inr("1000"), emptyList(), listOf(BalanceSnapshot(at(2026, 10, 2), inr("0"))))
        val report = rec.reconcileAll(listOf(good, bad))
        assertEquals(100, report.accounts[0].score)
        assertEquals(0, report.accounts[1].score)
        assertEquals(90, report.overallScore)
        assertEquals("₹1,000 unexplained since opening balance", report.gaps.single().message)
    }

    @Test fun noSnapshotsMeansNothingToReconcile() {
        val r = rec.reconcile(AccountReconInput("acc1", inr("1000"), baseTxns(), emptyList()))
        assertEquals(100, r.score)
        assertEquals(3, r.unverifiedTransactions)
    }
}
