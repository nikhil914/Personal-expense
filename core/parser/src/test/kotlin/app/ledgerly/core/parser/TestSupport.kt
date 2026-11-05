package app.ledgerly.core.parser

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.SourceEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/** Fixed "now" for all tests: 2026-11-05 10:00 IST. */
val RECEIVED: Instant = LocalDateTime.of(2026, 11, 5, 10, 0).atZone(IST).toInstant()

fun ist(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0): Instant =
    LocalDateTime.of(y, mo, d, h, mi, s).atZone(IST).toInstant()

private var counter = 0

fun sms(body: String, sender: String? = "AX-HDFCBK-S", at: Instant = RECEIVED) =
    SourceEvent("sms-${counter++}", Channel.SMS, sender, null, null, body, at)

fun notif(pkg: String, title: String?, body: String, at: Instant = RECEIVED) =
    SourceEvent("n-${counter++}", Channel.NOTIFICATION, null, pkg, title, body, at)

fun inr(amount: String) = Money.of(amount, "INR")
fun money(amount: String, cur: String) = Money.of(amount, cur)

object Pkg {
    const val GPAY = "com.google.android.apps.nbu.paisa.user"
    const val PHONEPE = "com.phonepe.app"
    const val PAYTM = "net.one97.paytm"
    const val REVOLUT = "com.revolut.revolut"
    const val WISE = "com.transferwise.android"
    const val MONZO = "co.uk.getmondo"
    const val N26 = "de.number26.android"
    const val CHASE = "com.chase.sig.android"
    const val HSBC = "uk.co.hsbc.hsbcukmobilebanking"
    const val AMEX = "com.americanexpress.android.acctsvcs.us"
    const val GMAIL = "com.google.android.gm"
}
