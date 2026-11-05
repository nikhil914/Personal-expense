package app.ledgerly.core.dedup

/** String similarity helpers: Jaro-Winkler and merchant token normalisation. */
object Similarity {

    fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val aM = BooleanArray(a.length)
        val bM = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = maxOf(0, i - window)
            val hi = minOf(b.length - 1, i + window)
            for (j in lo..hi) {
                if (!bM[j] && a[i] == b[j]) { aM[i] = true; bM[j] = true; matches++; break }
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in a.indices) {
            if (!aM[i]) continue
            while (!bM[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
    }

    /** Jaro-Winkler with standard prefix scale 0.1 and max prefix 4. */
    fun jaroWinkler(a: String, b: String, prefixScale: Double = 0.1): Double {
        val j = jaro(a, b)
        var p = 0
        while (p < minOf(4, a.length, b.length) && a[p] == b[p]) p++
        return j + p * prefixScale * (1 - j)
    }

    private val NOISE = setOf(
        "PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "LLC", "INC", "CO", "CORP", "COMPANY", "THE", "AND",
        "INDIA", "IN", "IND", "WWW", "COM", "NET", "ORG", "HTTP", "HTTPS", "PAYMENTS", "PAYMENT", "PAY", "POS",
        "ECOM", "ONLINE", "MERCHANT", "AT", "TO", "VIA", "UPI", "STORE", "STORES", "RETAIL", "SERVICES", "TECHNOLOGIES",
        "MUMBAI", "BANGALORE", "BENGALURU", "DELHI", "NEW", "GURGAON", "GURUGRAM", "NOIDA", "PUNE", "CHENNAI",
        "HYDERABAD", "KOLKATA", "BLR", "MUM", "DEL", "SG", "SGP", "US", "USA", "GB", "UK", "AE", "NY", "CA",
    )

    /** Uppercase, strip punctuation/digits-only tokens and legal/location noise; keeps order. */
    fun merchantTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.uppercase()
            .replace(Regex("[*#@_/\\\\.,:;()\\[\\]'\"&|+-]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val toks = cleaned.split(' ').filter { it.isNotBlank() }
        val kept = toks.filter { it !in NOISE && !it.all(Char::isDigit) && !(it.length > 4 && it.count(Char::isDigit) * 2 > it.length) }
        return kept.ifEmpty { toks.filter { !it.all(Char::isDigit) } }
    }

    fun normaliseMerchant(raw: String?): String = merchantTokens(raw).joinToString(" ")

    /**
     * Merchant similarity in 0..1: max of Jaro-Winkler on normalised strings, on concatenated tokens
     * ("SWIGGY INSTAMART" vs "SWIGGYINSTAMART"), and a token-subset score (one name contained in the other).
     */
    fun merchant(a: String?, b: String?): Double {
        val ta = merchantTokens(a); val tb = merchantTokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val na = ta.joinToString(" "); val nb = tb.joinToString(" ")
        if (na == nb) return 1.0
        val ca = ta.joinToString(""); val cb = tb.joinToString("")
        var best = maxOf(jaroWinkler(na, nb), jaroWinkler(ca, cb))
        if (ca.length >= 4 && cb.length >= 4 && (ca.startsWith(cb) || cb.startsWith(ca))) best = maxOf(best, 0.95)
        val sa = ta.toSet(); val sb = tb.toSet()
        val inter = sa.intersect(sb)
        if (inter.isNotEmpty()) {
            val shorter = minOf(sa.size, sb.size)
            val subset = inter.size.toDouble() / shorter
            if (subset == 1.0 && inter.any { it.length >= 3 }) best = maxOf(best, 0.92)
            else best = maxOf(best, 0.6 + 0.3 * subset * inter.size / maxOf(sa.size, sb.size))
        }
        // token-wise best match average (handles typos within tokens)
        val tokenAvg = ta.map { x -> tb.maxOf { y -> jaroWinkler(x, y) } }.average()
        val tokenAvgRev = tb.map { y -> ta.maxOf { x -> jaroWinkler(x, y) } }.average()
        best = maxOf(best, minOf(tokenAvg, tokenAvgRev))
        return best.coerceIn(0.0, 1.0)
    }

    /** Normalise a payment reference: alphanumerics only, uppercase, leading zeros stripped. */
    fun normaliseReference(ref: String?): String? {
        if (ref.isNullOrBlank()) return null
        val r = ref.uppercase()
            .replace(Regex("\\b(UPI|IMPS|NEFT|REF|RRN|TXN|TRANSACTION|AUTH|APPROVAL|CODE|NO|ID|NUMBER)\\b"), " ")
            .filter { it.isLetterOrDigit() }.trimStart('0')
        return r.takeIf { it.length >= 4 }
    }

    /** Whitespace/case-insensitive text fingerprint for exact-duplicate detection. */
    fun normaliseText(s: String?): String? = s?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
}
