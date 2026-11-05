package app.ledgerly.core.parser

import java.util.concurrent.CopyOnWriteArrayList

/** Normalised merchant with an optional category hint such as "Food/Delivery". */
data class NormalizedMerchant(
    val name: String,
    val categoryHint: String?,
    val raw: String,
    /** True when the name came from the alias table (built-in or user). */
    val aliased: Boolean,
    /** True when the raw value looked like a person (P2P transfer) rather than a business. */
    val isPerson: Boolean = false,
)

/** One alias rule: any [keys] (upper-case word sequences) maps to [name]. */
data class MerchantAlias(val name: String, val category: String?, val keys: List<String>)

/**
 * Cleans raw merchant strings from bank messages ("AMZN MKTP IN*2X3AB", "SWIGGY*ORDER",
 * "zomato@hdfcbank", "POS 1234 STARBUCKS MUMBAI") and maps them to canonical names.
 */
class MerchantNormalizer(aliases: List<MerchantAlias> = DEFAULT_ALIASES) {

    private val builtIn = aliases
    private val user = CopyOnWriteArrayList<MerchantAlias>()

    /** Adds a user alias; user aliases win over built-ins. */
    fun addAlias(key: String, name: String, category: String? = null) {
        user.add(0, MerchantAlias(name, category, listOf(key.uppercase().trim())))
    }

    fun aliases(): List<MerchantAlias> = user + builtIn

    fun normalize(raw: String?): NormalizedMerchant? {
        val r = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { normalizeInternal(r) }.getOrNull()
    }

    private fun normalizeInternal(raw: String): NormalizedMerchant? {
        var s = raw
        var fromVpa = false
        // UPI VPA: take local part ("zomato@hdfcbank" -> "zomato", "paytm-zomato@ptybl" -> "zomato").
        val vpaMatch = VPA.find(s)
        if (vpaMatch != null) {
            val rest = s.replace(vpaMatch.value, " ").trim()
            // "netflix@icici NETFLIX" -> prefer the readable name next to the VPA.
            s = if (rest.any { it.isLetter() } && rest.length >= 3) rest else vpaLocal(vpaMatch.groupValues[1])
            fromVpa = true
        }
        s = s.uppercase()
        // UPI/P2M/1234/NAME or NEFT-REF-NAME style
        Regex("^(?:NEFT|RTGS|IMPS)\\s*(?:CR|DR)?-[A-Z]{4}0[A-Z0-9]{6}-(.+?)(?:-.*)?$").find(s)?.let { s = it.groupValues[1] }
        Regex("^(?:UPI|IMPS|NEFT|RTGS)[/ -](?:P2[MA][/ -])?(?:[A-Z0-9]*\\d[A-Z0-9]*[/ -])?(.+)$").find(s)?.let { s = it.groupValues[1] }

        // Try aliases on the full string first (handles "AMZN MKTP IN*2X3AB").
        findAlias(s.replace('*', ' '))?.let { return NormalizedMerchant(it.name, it.category, raw, true) }

        // Split on '*' (payment-aggregator prefixes like "RAZ*SWIGGY", "PAYU*" or suffix ids).
        val parts = s.split('*').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            parts.firstOrNull { p -> AGGREGATOR_PREFIXES.none { p == it } && p.any { it.isLetter() } && !isIdToken(p) }?.let { s = it }
        }
        s = s.replace(Regex("[_]+"), " ")
        s = s.replace(Regex("^(?:POS|ECOM|ECS|ACH|BIL|BILLPAY|NACH|SI|VPS|IPS|MPS|PCD|PUR|PURCHASE|MB|IB|UPI|PAYU|RAZORPAY|RZP|CCAVENUE|PAYPAL|SQ|TST|SP|PP)\\b[\\s:/*-]*"), "")
        s = s.replace(Regex("^\\d{3,}\\s+"), "")
        s = s.replace(Regex("\\b(?:WWW\\.)"), "").replace(Regex("\\.(?:COM|IN|CO\\.IN|NET|ORG|CO\\.UK|IO)\\b"), "")

        // Drop trailing ids, cities, legal suffixes, country codes (repeat to peel multiple layers).
        var tokens = s.split(Regex("[\\s,/#-]+")).filter { it.isNotEmpty() }.toMutableList()
        var changed = true
        while (changed && tokens.size > 1) {
            changed = false
            val last = tokens.last()
            if (isIdToken(last) || last in CITIES || last in LEGAL || last in COUNTRY || last.length == 1) {
                tokens.removeAt(tokens.size - 1); changed = true
            } else if (tokens.size >= 2) {
                val two = tokens[tokens.size - 2] + " " + last
                if (two in CITIES || two in LEGAL) { tokens.removeAt(tokens.size - 1); tokens.removeAt(tokens.size - 1); changed = true }
            }
        }
        tokens = tokens.filterNot { isIdToken(it) && tokens.size > 1 }.toMutableList()
        val cleaned = tokens.joinToString(" ").trim().trim('-', '.', ',', '*', ':')
        if (cleaned.isEmpty()) return null

        findAlias(cleaned)?.let { return NormalizedMerchant(it.name, it.category, raw, true) }
        val person = fromVpa && PERSON_HINT.containsMatchIn(raw) || looksLikePerson(cleaned)
        return NormalizedMerchant(titleCase(cleaned), if (person) "Transfers/P2P" else null, raw, false, person)
    }

    private fun findAlias(upper: String): MerchantAlias? {
        val padded = " " + upper.replace(Regex("[^A-Z0-9&' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val compact = upper.replace(Regex("[^A-Z0-9]"), "")
        for (a in aliases()) {
            for (k in a.keys) {
                if (padded.contains(" $k ")) return a
                if (k.length >= 6 && !k.contains(' ') && compact.startsWith(k)) return a
            }
        }
        return null
    }

    private fun vpaLocal(local: String): String {
        var l = local.lowercase()
        l = l.removePrefix("paytm-").removePrefix("paytmqr").removePrefix("bharatpe.").removePrefix("q")
            .let { if (it.isEmpty()) local.lowercase() else it }
        l = l.replace(Regex("[._-]?(?:rzp|payu|razorpay|pg|online|merchant|payments?|upi|qr|bqr)$"), "")
        l = l.replace(Regex("\\d{4,}"), " ").replace(Regex("[._-]+"), " ").trim()
        return l.ifEmpty { local }
    }

    private fun isIdToken(t: String): Boolean {
        val digits = t.count { it.isDigit() }
        return (digits >= 3) || (digits >= 1 && t.length >= 4 && t.any { it.isLetter() } && t.length <= 12 && digits * 3 >= t.length)
    }

    private fun looksLikePerson(s: String): Boolean {
        val words = s.split(' ')
        return words.size in 2..4 && words.all { it.length >= 2 && it.all { c -> c.isLetter() } } &&
            words.none { it in BUSINESS_WORDS }
    }

    private fun titleCase(s: String): String = s.lowercase().split(' ').joinToString(" ") { w ->
        if (w.length <= 3 && w in setOf("atm", "kfc", "pvr", "bp", "hp", "ikea")) w.uppercase()
        else w.replaceFirstChar { it.titlecase() }
    }

    companion object {
        private val VPA = Regex("\\b([A-Za-z0-9][A-Za-z0-9._-]*)@(?:ok)?[A-Za-z]{2,}(?![\\w.@])")
        private val PERSON_HINT = Regex("@(?:ok(?:axis|hdfcbank|icici|sbi)|ybl|ibl|axl|paytm|apl|upi)\\b", RegexOption.IGNORE_CASE)
        private val AGGREGATOR_PREFIXES = setOf("RAZ", "RAZORPAY", "RZP", "PAYU", "CCA", "BD", "PYU", "SQ", "TST", "PP", "PAYPAL", "IN", "POS")
        private val CITIES = setOf(
            "MUMBAI", "BOMBAY", "DELHI", "NEW DELHI", "BANGALORE", "BENGALURU", "BANGLORE", "CHENNAI", "KOLKATA", "HYDERABAD", "PUNE",
            "GURGAON", "GURUGRAM", "NOIDA", "AHMEDABAD", "JAIPUR", "LUCKNOW", "CHANDIGARH", "KOCHI", "COCHIN", "INDORE", "THANE",
            "NAVI MUMBAI", "GOA", "SURAT", "NAGPUR", "LONDON", "SINGAPORE", "DUBAI", "PARIS", "BERLIN", "AMSTERDAM", "DUBLIN",
            "LUXEMBOURG", "SEATTLE", "NEW YORK", "SAN FRANCISCO", "MOUNTAIN VIEW", "CUPERTINO", "SUNNYVALE",
        )
        private val COUNTRY = setOf("IN", "IND", "INDIA", "US", "USA", "GB", "GBR", "UK", "SG", "SGP", "AE", "UAE", "DE", "FR", "NL", "IE", "LU", "EU", "EMEA", "APAC", "INTL")
        private val LEGAL = setOf(
            "PVT", "LTD", "PVT LTD", "PRIVATE", "LIMITED", "PRIVATE LIMITED", "LLP", "LLC", "INC", "CORP", "CO", "GMBH", "PLC", "SA", "BV",
            "TECHNOLOGIES", "TECHNOLOGY", "SERVICES", "SOLUTIONS", "RETAIL", "ONLINE", "INDIA PVT", "INTERNET", "ENTERPRISES",
        )
        private val BUSINESS_WORDS = setOf(
            "STORE", "STORES", "MART", "SHOP", "CAFE", "RESTAURANT", "HOTEL", "PHARMACY", "MEDICAL", "TRADERS", "MARKET", "FOODS",
            "KITCHEN", "BAKERY", "SUPERMARKET", "CENTRE", "CENTER", "ENTERPRISE", "AGENCY", "MOTORS", "FUEL", "PETROL", "STATION",
            "BAR", "PUB", "CLINIC", "HOSPITAL", "SALON", "SPORTS", "ATM", "BANK", "ROAD", "MARKT",
        )

        private fun a(name: String, category: String, vararg keys: String) = MerchantAlias(name, category, keys.map { it.uppercase() })

        /** Built-in alias table (India + global) with default category hints. */
        val DEFAULT_ALIASES: List<MerchantAlias> = listOf(
            // Shopping / online
            a("Amazon Web Services", "Software/Cloud", "AMAZON WEB SERVICES", "AWS EMEA", "AWS", "AMAZONWEBSERVICES"),
            a("Amazon Prime Video", "Entertainment/Streaming", "PRIME VIDEO", "PRIMEVIDEO", "AMAZON PRIME"),
            a("Amazon", "Shopping/Online", "AMZN MKTP", "AMZN", "AMAZON PAY", "AMAZON", "AMAZONPAY", "AMAZON IN", "AMAZON RETAIL"),
            a("Flipkart", "Shopping/Online", "FLIPKART", "FKRT", "FLIPKART INTERNET", "EKART"),
            a("Myntra", "Shopping/Apparel", "MYNTRA"),
            a("Ajio", "Shopping/Apparel", "AJIO"),
            a("Meesho", "Shopping/Online", "MEESHO"),
            a("Nykaa", "Shopping/Beauty", "NYKAA"),
            a("Tata CLiQ", "Shopping/Online", "TATACLIQ", "TATA CLIQ"),
            a("Croma", "Shopping/Electronics", "CROMA"),
            a("Reliance Digital", "Shopping/Electronics", "RELIANCE DIGITAL"),
            a("Decathlon", "Shopping/Sports", "DECATHLON"),
            a("IKEA", "Shopping/Home", "IKEA"),
            a("Zara", "Shopping/Apparel", "ZARA"),
            a("H&M", "Shopping/Apparel", "H&M", "H & M", "HENNES"),
            a("Uniqlo", "Shopping/Apparel", "UNIQLO"),
            a("Lifestyle", "Shopping/Apparel", "LIFESTYLE INTERNATIONAL", "LIFESTYLE STORES"),
            a("Westside", "Shopping/Apparel", "WESTSIDE"),
            a("Walmart", "Shopping/General", "WALMART", "WAL-MART", "WM SUPERCENTER"),
            a("Target", "Shopping/General", "TARGET"),
            a("Costco", "Food/Groceries", "COSTCO"),
            a("eBay", "Shopping/Online", "EBAY"),
            // Food delivery / groceries / dining
            a("Swiggy Instamart", "Food/Groceries", "INSTAMART", "SWIGGY INSTAMART"),
            a("Swiggy", "Food/Delivery", "SWIGGY", "BUNDL TECHNOLOGIES", "BUNDL"),
            a("Zomato", "Food/Delivery", "ZOMATO"),
            a("Blinkit", "Food/Groceries", "BLINKIT", "GROFERS"),
            a("Zepto", "Food/Groceries", "ZEPTO", "KIRANAKART"),
            a("BigBasket", "Food/Groceries", "BIGBASKET", "BIG BASKET", "SUPERMARKET GROCERY SUPPLIES"),
            a("Dunzo", "Food/Groceries", "DUNZO"),
            a("DMart", "Food/Groceries", "DMART", "AVENUE SUPERMARTS"),
            a("Reliance Fresh", "Food/Groceries", "RELIANCE FRESH", "RELIANCE SMART", "RELIANCE RETAIL", "JIOMART"),
            a("More Supermarket", "Food/Groceries", "MORE RETAIL", "MORE SUPERMARKET"),
            a("Lidl", "Food/Groceries", "LIDL"),
            a("Aldi", "Food/Groceries", "ALDI"),
            a("Tesco", "Food/Groceries", "TESCO"),
            a("Sainsbury's", "Food/Groceries", "SAINSBURYS", "SAINSBURY'S", "SAINSBURY"),
            a("REWE", "Food/Groceries", "REWE"),
            a("Edeka", "Food/Groceries", "EDEKA"),
            a("Whole Foods", "Food/Groceries", "WHOLE FOODS", "WHOLEFDS"),
            a("Starbucks", "Food/Dining", "STARBUCKS", "TATA STARBUCKS"),
            a("McDonald's", "Food/Dining", "MCDONALDS", "MCDONALD'S", "MC DONALDS", "HARDCASTLE RESTAURANTS"),
            a("KFC", "Food/Dining", "KFC"),
            a("Domino's", "Food/Dining", "DOMINOS", "DOMINO'S", "JUBILANT FOODWORKS"),
            a("Pizza Hut", "Food/Dining", "PIZZA HUT", "PIZZAHUT"),
            a("Burger King", "Food/Dining", "BURGER KING"),
            a("Subway", "Food/Dining", "SUBWAY"),
            a("Haldiram's", "Food/Dining", "HALDIRAM", "HALDIRAMS"),
            a("Chaayos", "Food/Dining", "CHAAYOS"),
            a("Third Wave Coffee", "Food/Dining", "THIRD WAVE COFFEE"),
            a("Cafe Coffee Day", "Food/Dining", "CAFE COFFEE DAY", "CCD"),
            a("Pret A Manger", "Food/Dining", "PRET A MANGER", "PRET"),
            a("Costa Coffee", "Food/Dining", "COSTA COFFEE"),
            a("Deliveroo", "Food/Delivery", "DELIVEROO"),
            a("Uber Eats", "Food/Delivery", "UBER EATS", "UBEREATS"),
            a("DoorDash", "Food/Delivery", "DOORDASH"),
            // Transport / travel
            a("Uber", "Transport/Ride", "UBER", "UBER INDIA", "UBER TRIP", "UBER BV"),
            a("Ola", "Transport/Ride", "OLA", "OLACABS", "ANI TECHNOLOGIES", "OLA CABS"),
            a("Rapido", "Transport/Ride", "RAPIDO", "ROPPEN"),
            a("Lyft", "Transport/Ride", "LYFT"),
            a("Namma Metro", "Transport/Transit", "BMRCL", "NAMMA METRO"),
            a("Delhi Metro", "Transport/Transit", "DMRC", "DELHI METRO"),
            a("FASTag", "Transport/Tolls", "FASTAG", "NETC"),
            a("Indian Oil", "Transport/Fuel", "INDIAN OIL", "IOCL", "INDIANOIL"),
            a("Bharat Petroleum", "Transport/Fuel", "BHARAT PETROLEUM", "BPCL"),
            a("HP Petrol", "Transport/Fuel", "HPCL", "HINDUSTAN PETROLEUM"),
            a("Shell", "Transport/Fuel", "SHELL"),
            a("IRCTC", "Travel/Rail", "IRCTC"),
            a("MakeMyTrip", "Travel/Booking", "MAKEMYTRIP", "MMT"),
            a("Goibibo", "Travel/Booking", "GOIBIBO"),
            a("Cleartrip", "Travel/Booking", "CLEARTRIP"),
            a("IndiGo", "Travel/Flights", "INDIGO", "INTERGLOBE AVIATION"),
            a("Air India", "Travel/Flights", "AIR INDIA", "AIRINDIA"),
            a("redBus", "Travel/Bus", "REDBUS"),
            a("Airbnb", "Travel/Hotels", "AIRBNB"),
            a("Booking.com", "Travel/Hotels", "BOOKING.COM", "BOOKING COM", "BOOKINGCOM"),
            a("Expedia", "Travel/Booking", "EXPEDIA"),
            a("OYO", "Travel/Hotels", "OYO", "OYO ROOMS", "ORAVEL"),
            // Bills / telecom / utilities
            a("Airtel", "Bills/Telecom", "AIRTEL", "BHARTI AIRTEL", "AIRTEL PAYMENTS"),
            a("Jio", "Bills/Telecom", "JIO", "RELIANCE JIO", "JIO PREPAID", "JIOFIBER"),
            a("Vi", "Bills/Telecom", "VODAFONE IDEA", "VODAFONE", "VI PREPAID", "IDEA CELLULAR"),
            a("BSNL", "Bills/Telecom", "BSNL"),
            a("Tata Play", "Bills/TV", "TATA PLAY", "TATASKY", "TATA SKY"),
            a("ACT Fibernet", "Bills/Internet", "ACT FIBERNET", "ATRIA CONVERGENCE"),
            a("BESCOM", "Bills/Electricity", "BESCOM"),
            a("Tata Power", "Bills/Electricity", "TATA POWER"),
            a("Adani Electricity", "Bills/Electricity", "ADANI ELECTRICITY"),
            a("MSEDCL", "Bills/Electricity", "MSEDCL", "MAHAVITARAN"),
            a("Mahanagar Gas", "Bills/Gas", "MAHANAGAR GAS", "MGL"),
            a("Indane Gas", "Bills/Gas", "INDANE"),
            a("LIC", "Finance/Insurance", "LIC", "LIFE INSURANCE CORPORATION", "LICI"),
            // Entertainment / streaming
            a("Netflix", "Entertainment/Streaming", "NETFLIX"),
            a("Spotify", "Entertainment/Streaming", "SPOTIFY"),
            a("Disney+ Hotstar", "Entertainment/Streaming", "HOTSTAR", "DISNEY HOTSTAR", "NOVI DIGITAL"),
            a("JioCinema", "Entertainment/Streaming", "JIOCINEMA", "JIO CINEMA"),
            a("YouTube Premium", "Entertainment/Streaming", "YOUTUBE", "YOUTUBE PREMIUM", "GOOGLE YOUTUBE"),
            a("Apple", "Software/Subscriptions", "APPLE COM BILL", "APPLE.COM/BILL", "APPLE SERVICES", "ITUNES", "APPLE"),
            a("Google Play", "Software/Subscriptions", "GOOGLE PLAY", "GOOGLE PLAYSTORE", "PLAY STORE"),
            a("Google Cloud", "Software/Cloud", "GOOGLE CLOUD", "GOOGLE GSUITE", "GOOGLE WORKSPACE"),
            a("Microsoft", "Software/Subscriptions", "MICROSOFT", "MSFT", "XBOX"),
            a("GitHub", "Software/Subscriptions", "GITHUB"),
            a("Adobe", "Software/Subscriptions", "ADOBE"),
            a("Dropbox", "Software/Subscriptions", "DROPBOX"),
            a("Zoom", "Software/Subscriptions", "ZOOM US", "ZOOM.US", "ZOOM VIDEO"),
            a("Canva", "Software/Subscriptions", "CANVA"),
            a("Notion", "Software/Subscriptions", "NOTION LABS", "NOTION SO"),
            a("Steam", "Entertainment/Gaming", "STEAM", "STEAMPOWERED", "VALVE"),
            a("PlayStation", "Entertainment/Gaming", "PLAYSTATION", "SONY PLAYSTATION", "PSN"),
            a("BookMyShow", "Entertainment/Movies", "BOOKMYSHOW", "BIGTREE ENTERTAINMENT", "BMS"),
            a("PVR INOX", "Entertainment/Movies", "PVR", "INOX", "PVR INOX"),
            // Health / fitness / services
            a("Apollo Pharmacy", "Health/Pharmacy", "APOLLO PHARMACY", "APOLLO"),
            a("Tata 1mg", "Health/Pharmacy", "1MG", "TATA 1MG"),
            a("PharmEasy", "Health/Pharmacy", "PHARMEASY"),
            a("Netmeds", "Health/Pharmacy", "NETMEDS"),
            a("Practo", "Health/Doctor", "PRACTO"),
            a("Cult.fit", "Health/Fitness", "CULTFIT", "CULT FIT", "CURE FIT", "CUREFIT"),
            a("Urban Company", "Home/Services", "URBAN COMPANY", "URBANCLAP", "URBAN CLAP"),
            // Finance / wallets
            a("CRED", "Finance/Bill Payment", "CRED", "DREAMPLUG"),
            a("Paytm", "Transfers/Wallet", "PAYTM", "ONE97"),
            a("PhonePe", "Transfers/Wallet", "PHONEPE"),
            a("Zerodha", "Finance/Investments", "ZERODHA"),
            a("Groww", "Finance/Investments", "GROWW", "NEXTBILLION"),
        )
    }
}
