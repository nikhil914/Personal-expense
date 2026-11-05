package app.ledgerly.fx

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Parses the ECB daily reference-rate feed
 * (https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml):
 *   <Cube time='2024-11-01'><Cube currency='USD' rate='1.0858'/>...</Cube>
 * Pure Kotlin (regex over a tiny, stable document) so it is unit-testable on the JVM.
 */
object EcbRatesParser {
    data class Result(val date: LocalDate, val eurRates: Map<String, BigDecimal>)

    private val timeRx = Regex("""time\s*=\s*['"](\d{4}-\d{2}-\d{2})['"]""")
    private val rateRx = Regex("""currency\s*=\s*['"]([A-Z]{3})['"]\s+rate\s*=\s*['"]([0-9.]+)['"]""")

    fun parse(xml: String): Result? {
        val date = timeRx.find(xml)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val rates = rateRx.findAll(xml).mapNotNull { m ->
            runCatching { m.groupValues[1] to BigDecimal(m.groupValues[2]) }.getOrNull()
        }.filter { it.second.signum() > 0 }.toMap()
        if (rates.isEmpty()) return null
        return Result(date, rates)
    }
}
