package app.ledgerly.capture

/**
 * Apps whose notifications may carry transaction alerts. Users can toggle each one and add custom
 * package names in Settings. Package names should be re-verified against the Play Store periodically.
 */
object KnownApps {
    const val GMAIL = "com.google.android.gm"

    data class App(val packageName: String, val label: String, val group: String)

    val all: List<App> = listOf(
        // UPI / wallets (India)
        App("com.google.android.apps.nbu.paisa.user", "Google Pay", "UPI"),
        App("com.phonepe.app", "PhonePe", "UPI"),
        App("net.one97.paytm", "Paytm", "UPI"),
        App("in.org.npci.upiapp", "BHIM", "UPI"),
        App("com.dreamplug.androidapp", "CRED", "UPI"),
        // Banks (India)
        App("com.snapwork.hdfc", "HDFC Bank", "Bank"),
        App("com.csam.icici.bank.imobile", "ICICI iMobile", "Bank"),
        App("com.sbi.lotusintouch", "SBI YONO", "Bank"),
        App("com.axis.mobile", "Axis Mobile", "Bank"),
        App("com.msf.kbank.mobile", "Kotak", "Bank"),
        // Global cards / neobanks
        App("com.revolut.revolut", "Revolut", "Card"),
        App("com.transferwise.android", "Wise", "Card"),
        App("com.americanexpress.android.acctsvcs.us", "Amex", "Card"),
        App("com.chase.sig.android", "Chase", "Card"),
        App("uk.co.hsbc.hsbcukmobilebanking", "HSBC UK", "Bank"),
        App("co.uk.getmondo", "Monzo", "Bank"),
        App("com.google.android.apps.walletnfcrel", "Google Wallet", "Card"),
        // Email: bank alert emails surface through Gmail notifications (separate consent).
        App(GMAIL, "Gmail (bank alert emails)", "Email"),
    )

    /** Allowed by default once notification capture is consented. Gmail is opt-in on its own. */
    val defaultAllowed: Set<String> = all.map { it.packageName }.filter { it != GMAIL }.toSet()

    fun label(packageName: String): String = all.firstOrNull { it.packageName == packageName }?.label ?: packageName
}
