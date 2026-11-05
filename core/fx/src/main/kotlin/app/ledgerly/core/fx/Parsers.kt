package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class RateParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Parses the ECB euro reference-rate XML (eurofxref-daily.xml, eurofxref-hist.xml, eurofxref-hist-90d.xml).
 * Produces EUR-based [FxRate]s. Uses JDK DOM only, with DTDs/external entities disabled.
 */
object EcbXmlParser {
    const val SOURCE = "ECB"

    fun parse(xml: String): List<FxRate> = parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    fun parse(input: InputStream): List<FxRate> {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                trySet("http://apache.org/xml/features/disallow-doctype-decl", true)
                trySet("http://xml.org/sax/features/external-general-entities", false)
                trySet("http://xml.org/sax/features/external-parameter-entities", false)
            }
            f.newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw RateParseException("Invalid ECB XML: ${e.message}", e)
        }
        val out = ArrayList<FxRate>()
        val cubes = doc.getElementsByTagNameNS("*", "Cube")
        for (i in 0 until cubes.length) {
            val day = cubes.item(i) as Element
            val time = day.getAttribute("time")
            if (time.isEmpty()) continue
            val date = try { LocalDate.parse(time.trim()) } catch (e: Exception) {
                throw RateParseException("Bad ECB date '$time'", e)
            }
            val children = day.childNodes
            for (j in 0 until children.length) {
                val c = children.item(j) as? Element ?: continue
                val cur = c.getAttribute("currency").trim()
                val rate = c.getAttribute("rate").trim()
                if (cur.isEmpty() || rate.isEmpty()) continue
                val code = runCatching { CurrencyCode.of(cur) }.getOrNull() ?: continue
                if (code == CurrencyCode.EUR) continue
                val value = rate.toBigDecimalOrNull() ?: throw RateParseException("Bad ECB rate '$rate' for $cur")
                if (value.signum() <= 0) continue
                out += FxRate(date, CurrencyCode.EUR, code, value, SOURCE)
            }
        }
        if (out.isEmpty()) throw RateParseException("No rates found in ECB XML")
        return out
    }

    private fun DocumentBuilderFactory.trySet(feature: String, value: Boolean) {
        try { setFeature(feature, value) } catch (_: Exception) { /* not supported by this parser */ }
    }
}

/**
 * Minimal hand-written parser for Open Exchange Rates style JSON:
 * `{"timestamp": 1704412800, "base": "USD", "rates": {"INR": 83.1, ...}}`.
 * The date comes from `timestamp` (UTC) when present, else from [fallbackDate].
 */
object OpenExchangeRatesJsonParser {
    const val SOURCE = "OXR"

    fun parse(json: String, fallbackDate: LocalDate? = null, source: String = SOURCE): List<FxRate> {
        val root = try { MiniJson(json).parseDocument() } catch (e: RateParseException) { throw e } catch (e: Exception) {
            throw RateParseException("Invalid JSON: ${e.message}", e)
        }
        if (root !is Map<*, *>) throw RateParseException("Expected JSON object at root")
        val baseStr = root["base"] as? String ?: throw RateParseException("Missing 'base'")
        val base = runCatching { CurrencyCode.of(baseStr) }.getOrElse { throw RateParseException("Bad base '$baseStr'") }
        val date = (root["timestamp"] as? BigDecimal)?.let {
            Instant.ofEpochSecond(it.toLong()).atZone(ZoneOffset.UTC).toLocalDate()
        } ?: (root["date"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: fallbackDate ?: throw RateParseException("No timestamp/date and no fallback date")
        val rates = root["rates"] as? Map<*, *> ?: throw RateParseException("Missing 'rates' object")
        return rates.mapNotNull { (k, v) ->
            val code = runCatching { CurrencyCode.of(k as String) }.getOrNull() ?: return@mapNotNull null
            val value = v as? BigDecimal ?: return@mapNotNull null
            if (code == base || value.signum() <= 0) null else FxRate(date, base, code, value, source)
        }
    }
}

/** Tiny JSON reader: objects -> Map, arrays -> List, numbers -> BigDecimal, strings, booleans, null. */
internal class MiniJson(private val s: String) {
    private var i = 0

    fun parseDocument(): Any? {
        val v = value(); ws()
        if (i != s.length) err("Trailing content")
        return v
    }

    private fun err(msg: String): Nothing = throw RateParseException("$msg at offset $i")

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        if (i >= s.length) err("Unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else err("Unexpected '$c'")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) err("Expected $word")
        i += word.length; return v
    }

    private fun obj(): Map<String, Any?> {
        i++; val m = LinkedHashMap<String, Any?>(); ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws(); if (i >= s.length || s[i] != '"') err("Expected key")
            val k = str(); ws()
            if (i >= s.length || s[i] != ':') err("Expected ':'")
            i++; m[k] = value(); ws()
            if (i >= s.length) err("Unterminated object")
            when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> err("Expected ',' or '}'") }
        }
    }

    private fun arr(): List<Any?> {
        i++; val l = ArrayList<Any?>(); ws()
        if (i < s.length && s[i] == ']') { i++; return l }
        while (true) {
            l += value(); ws()
            if (i >= s.length) err("Unterminated array")
            when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> err("Expected ',' or ']'") }
        }
    }

    private fun str(): String {
        i++; val sb = StringBuilder()
        while (true) {
            if (i >= s.length) err("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) err("Bad escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) err("Bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> err("Bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): BigDecimal {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        return s.substring(start, i).toBigDecimalOrNull() ?: err("Bad number '${s.substring(start, i)}'")
    }
}
