package app.ledgerly.core.parser

import app.ledgerly.core.model.SourceEvent

/** Result of looking up an event's sender / package. */
data class SenderMatch(
    val allowed: Boolean,
    val institution: String?,
    /** Normalised key used for template sender matching (DLT header, package name, or email domain). */
    val key: String?,
    /** True for aggregator sources (e.g. the Gmail app) where the real institution is in the text. */
    val aggregator: Boolean = false,
    val userAdded: Boolean = false,
)

/**
 * Allow-list of financial SMS sender ids, Android package names and email domains, mapped to
 * institution names. Thread-safe for concurrent reads; user additions are synchronised.
 */
class SenderRegistry(
    smsHeaders: Map<String, String> = DEFAULT_SMS_HEADERS,
    packages: Map<String, String> = DEFAULT_PACKAGES,
    emailDomains: Map<String, String> = DEFAULT_EMAIL_DOMAINS,
) {
    private val sms = HashMap(smsHeaders.mapKeys { it.key.uppercase() })
    private val pkgs = HashMap(packages)
    private val domains = HashMap(emailDomains.mapKeys { it.key.lowercase() })
    private val userSms = HashMap<String, String>()
    private val userPkgs = HashMap<String, String>()
    private val lock = Any()

    /** Adds (or overrides) a user-trusted SMS sender. Accepts raw ids like "AX-MYBANK-S". */
    fun addSender(senderId: String, institution: String) {
        val key = normalizeSmsSender(senderId) ?: return
        synchronized(lock) { userSms[key] = institution }
    }

    /** Adds (or overrides) a user-trusted notification package. */
    fun addPackage(packageName: String, institution: String) {
        synchronized(lock) { userPkgs[packageName.trim()] = institution }
    }

    fun removeSender(senderId: String) {
        val key = normalizeSmsSender(senderId) ?: return
        synchronized(lock) { userSms.remove(key) }
    }

    fun removePackage(packageName: String) {
        synchronized(lock) { userPkgs.remove(packageName.trim()) }
    }

    /** All allow-listed packages (for the Android NotificationListener allow-list UI). */
    fun allowedPackages(): Set<String> = synchronized(lock) { pkgs.keys + userPkgs.keys }

    fun isAllowedPackage(packageName: String?): Boolean = lookupPackage(packageName) != null

    fun isAllowedSmsSender(senderId: String?): Boolean = lookupSms(senderId) != null

    /** Institution for an SMS sender id, or null if not allow-listed. */
    fun lookupSms(senderId: String?): String? {
        val key = normalizeSmsSender(senderId) ?: return null
        synchronized(lock) { userSms[key] }?.let { return it }
        sms[key]?.let { return it }
        // Fuzzy: DLT headers sometimes get variants (HDFCBN, HDFCBK, HDFCCC ...).
        if (key.any { it.isDigit() }) return null
        return FUZZY_HEADER_PREFIXES.entries.firstOrNull { key.startsWith(it.key) }?.value
    }

    fun lookupPackage(packageName: String?): String? {
        val p = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        synchronized(lock) { userPkgs[p] }?.let { return it }
        return pkgs[p]
    }

    fun lookupEmail(from: String?): String? {
        val domain = emailDomain(from) ?: return null
        domains[domain]?.let { return it }
        return domains.entries.firstOrNull { domain.endsWith("." + it.key) }?.value
    }

    /** Resolves the sender of an event. Text is used to name the institution for aggregator apps. */
    fun resolve(event: SourceEvent): SenderMatch {
        val pkg = event.packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (pkg != null) {
            val userInst = synchronized(lock) { userPkgs[pkg] }
            val inst = userInst ?: pkgs[pkg]
            if (inst != null) {
                val aggregator = pkg in AGGREGATOR_PACKAGES
                val realInst = if (aggregator) {
                    institutionFromText(TextNormalizer.combine(event.title, event.body)) ?: lookupEmail(event.sender)
                } else inst
                return SenderMatch(true, realInst, pkg, aggregator, userInst != null)
            }
            return SenderMatch(false, institutionFromText(event.title ?: ""), pkg)
        }
        val sender = event.sender?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SenderMatch(false, null, null)
        if (sender.contains('@')) {
            val inst = lookupEmail(sender)
            return SenderMatch(inst != null, inst ?: institutionFromText(TextNormalizer.combine(event.title, event.body)), emailDomain(sender))
        }
        val key = normalizeSmsSender(sender)
        val userInst = key?.let { synchronized(lock) { userSms[it] } }
        val inst = userInst ?: lookupSms(sender)
        return SenderMatch(inst != null, inst, key, userAdded = userInst != null)
    }

    companion object {
        private val DLT = Regex("^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$")
        private val PLAIN = Regex("^([A-Z0-9]{3,11})(?:-[A-Z])?$")

        /**
         * Normalises an SMS sender: "AX-HDFCBK-S" / "VM-HDFCBK" / "HDFCBK" -> "HDFCBK".
         * Phone numbers are returned digits-only (e.g. "+91 98..." -> "9198...").
         */
        fun normalizeSmsSender(raw: String?): String? {
            val s = raw?.trim()?.uppercase()?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
            DLT.find(s)?.let { return it.groupValues[1] }
            if (s.startsWith("+") || s.all { it.isDigit() }) return s.filter { it.isDigit() }
            PLAIN.find(s)?.let { return it.groupValues[1] }
            return s
        }

        fun emailDomain(from: String?): String? {
            val f = from?.trim() ?: return null
            val addr = Regex("<([^>]+)>").find(f)?.groupValues?.get(1) ?: f
            val at = addr.lastIndexOf('@')
            if (at < 0 || at == addr.length - 1) return null
            return addr.substring(at + 1).trim().trimEnd('>').lowercase()
        }

        /** Detects an institution name inside text (used for Gmail notifications etc.). */
        fun institutionFromText(text: String): String? {
            val t = text.lowercase()
            return TEXT_INSTITUTIONS.firstOrNull { (kw, _) -> Regex("(?<![a-z])" + Regex.escape(kw) + "(?![a-z])").containsMatchIn(t) }?.second
        }

        const val GMAIL = "com.google.android.gm"
        val AGGREGATOR_PACKAGES = setOf(GMAIL, "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail")

        val DEFAULT_SMS_HEADERS: Map<String, String> = mapOf(
            "HDFCBK" to "HDFC Bank", "HDFCBN" to "HDFC Bank", "HDFCCC" to "HDFC Bank", "HDFCSM" to "HDFC Bank",
            "ICICIB" to "ICICI Bank", "ICICIT" to "ICICI Bank", "ICICIO" to "ICICI Bank", "ICICIC" to "ICICI Bank",
            "SBIINB" to "State Bank of India", "SBIUPI" to "State Bank of India", "SBICRD" to "SBI Card",
            "SBIPSG" to "State Bank of India", "ATMSBI" to "State Bank of India", "CBSSBI" to "State Bank of India",
            "SBYONO" to "State Bank of India", "SBMSMS" to "State Bank of India",
            "AXISBK" to "Axis Bank", "AXISCC" to "Axis Bank", "AXISMR" to "Axis Bank",
            "KOTAKB" to "Kotak Mahindra Bank", "KOTAKM" to "Kotak Mahindra Bank", "KMBANK" to "Kotak Mahindra Bank",
            "YESBNK" to "Yes Bank", "YESBKC" to "Yes Bank",
            "IDFCFB" to "IDFC FIRST Bank", "IDFCBK" to "IDFC FIRST Bank",
            "AUBANK" to "AU Small Finance Bank", "AUSFBL" to "AU Small Finance Bank",
            "FEDBNK" to "Federal Bank", "FEDFIB" to "Federal Bank",
            "INDUSB" to "IndusInd Bank", "INDUSL" to "IndusInd Bank", "IBLCRD" to "IndusInd Bank",
            "BOBTXN" to "Bank of Baroda", "BOBSMS" to "Bank of Baroda", "BOBCRD" to "Bank of Baroda",
            "PNBSMS" to "Punjab National Bank", "CANBNK" to "Canara Bank", "UNIONB" to "Union Bank of India",
            "IDBIBK" to "IDBI Bank", "RBLBNK" to "RBL Bank", "RBLCRD" to "RBL Bank", "SCBANK" to "Standard Chartered",
            "HSBCIN" to "HSBC", "CITIBK" to "Citibank", "AMEXIN" to "American Express", "AMEX" to "American Express",
            "PAYTMB" to "Paytm Payments Bank", "IPAYTM" to "Paytm", "PYTMBK" to "Paytm Payments Bank",
            "JUPITR" to "Jupiter", "FIMONY" to "Fi", "SLCEIT" to "Slice", "ONECRD" to "OneCard",
            "AMZPAY" to "Amazon Pay", "CREDIN" to "CRED", "GPAYIN" to "Google Pay", "PHONPE" to "PhonePe",
            "BANKBR" to "Bank of Baroda", "IOBCHN" to "Indian Overseas Bank", "CENTBK" to "Central Bank of India",
            "REVOLUT" to "Revolut", "MONZO" to "Monzo", "CHASE" to "Chase", "HSBC" to "HSBC", "WISE" to "Wise",
            "BARCLAYS" to "Barclays", "NATWEST" to "NatWest", "24273" to "Chase",
        )

        private val FUZZY_HEADER_PREFIXES: Map<String, String> = linkedMapOf(
            "HDFC" to "HDFC Bank", "ICICI" to "ICICI Bank", "SBI" to "State Bank of India", "AXIS" to "Axis Bank",
            "KOTAK" to "Kotak Mahindra Bank", "YESB" to "Yes Bank", "IDFC" to "IDFC FIRST Bank", "AUBANK" to "AU Small Finance Bank",
            "FEDB" to "Federal Bank", "INDUS" to "IndusInd Bank", "AMEX" to "American Express",
        )

        val DEFAULT_PACKAGES: Map<String, String> = mapOf(
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "com.phonepe.app" to "PhonePe",
            "net.one97.paytm" to "Paytm",
            "com.dreamplug.androidapp" to "CRED",
            "in.amazon.mShop.android.shopping" to "Amazon Pay",
            "com.snapwork.hdfc" to "HDFC Bank",
            "com.hdfcbank.payzapp" to "HDFC Bank",
            "com.csam.icici.bank.imobile" to "ICICI Bank",
            "com.sbi.lotusintouch" to "State Bank of India",
            "com.sbi.SBIFreedomPlus" to "State Bank of India",
            "com.sbi.upi" to "State Bank of India",
            "com.axis.mobile" to "Axis Bank",
            "com.msf.kbank.mobile" to "Kotak Mahindra Bank",
            "com.yesbank" to "Yes Bank",
            "com.idfcfirstbank.optimus" to "IDFC FIRST Bank",
            "com.aubank.aupay.bhimupi" to "AU Small Finance Bank",
            "com.fedmobile" to "Federal Bank",
            "com.indusind.mobile" to "IndusInd Bank",
            "in.org.npci.upiapp" to "BHIM",
            "com.mobikwik_new" to "MobiKwik",
            "money.jupiter" to "Jupiter",
            "com.epifi.paisa" to "Fi",
            "com.revolut.revolut" to "Revolut",
            "com.transferwise.android" to "Wise",
            "com.americanexpress.android.acctsvcs.us" to "American Express",
            "com.americanexpress.android.acctsvcs.uk" to "American Express",
            "com.americanexpress.android.acctsvcs.in" to "American Express",
            "com.chase.sig.android" to "Chase",
            "uk.co.hsbc.hsbcukmobilebanking" to "HSBC",
            "com.htsu.hsbcpersonalbanking" to "HSBC",
            "co.uk.getmondo" to "Monzo",
            "de.number26.android" to "N26",
            "com.barclays.android.barclaysmobilebanking" to "Barclays",
            "com.starlingbank.android" to "Starling",
            "com.paypal.android.p2pmobile" to "PayPal",
            GMAIL to "Gmail",
        )

        val DEFAULT_EMAIL_DOMAINS: Map<String, String> = mapOf(
            "hdfcbank.net" to "HDFC Bank", "hdfcbank.com" to "HDFC Bank", "icicibank.com" to "ICICI Bank",
            "sbi.co.in" to "State Bank of India", "sbicard.com" to "SBI Card", "axisbank.com" to "Axis Bank",
            "kotak.com" to "Kotak Mahindra Bank", "yesbank.in" to "Yes Bank", "idfcfirstbank.com" to "IDFC FIRST Bank",
            "aubank.in" to "AU Small Finance Bank", "federalbank.co.in" to "Federal Bank", "indusind.com" to "IndusInd Bank",
            "americanexpress.com" to "American Express", "aexp.com" to "American Express", "chase.com" to "Chase",
            "hsbc.co.uk" to "HSBC", "hsbc.co.in" to "HSBC", "hsbc.com" to "HSBC", "revolut.com" to "Revolut",
            "wise.com" to "Wise", "monzo.com" to "Monzo", "n26.com" to "N26",
        )

        /** Keyword -> institution, most specific first. */
        private val TEXT_INSTITUTIONS: List<Pair<String, String>> = listOf(
            "hdfc" to "HDFC Bank", "icici" to "ICICI Bank", "sbi card" to "SBI Card", "sbi" to "State Bank of India",
            "state bank" to "State Bank of India", "axis" to "Axis Bank", "kotak" to "Kotak Mahindra Bank",
            "yes bank" to "Yes Bank", "idfc" to "IDFC FIRST Bank", "au small finance" to "AU Small Finance Bank",
            "au bank" to "AU Small Finance Bank", "federal bank" to "Federal Bank", "indusind" to "IndusInd Bank",
            "american express" to "American Express", "amex" to "American Express", "chase" to "Chase",
            "hsbc" to "HSBC", "revolut" to "Revolut", "wise" to "Wise", "monzo" to "Monzo", "n26" to "N26",
            "bank of baroda" to "Bank of Baroda", "canara" to "Canara Bank", "pnb" to "Punjab National Bank",
            "citi" to "Citibank", "standard chartered" to "Standard Chartered", "rbl" to "RBL Bank",
        )
    }
}
