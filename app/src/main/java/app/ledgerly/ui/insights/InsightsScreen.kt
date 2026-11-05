package app.ledgerly.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledgerly.engine.FinancialPeriod
import app.ledgerly.ui.common.*
import java.time.LocalDate
import java.time.ZoneId

private val palette = listOf(0xFF4E79A7, 0xFFF28E2B, 0xFFE15759, 0xFF76B7B2, 0xFF59A14F, 0xFFEDC948, 0xFFB07AA1, 0xFF9C755F).map { Color(it) }

@Composable
fun InsightsScreen() {
    val c = appContainer()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val period = remember(s.monthStartDay) { FinancialPeriod.current(LocalDate.now(zone), s.monthStartDay) }
    val overview by remember(s.baseCurrency, period) { c.budgetRepository.observeOverview(s.baseCurrency, period, zone) }.collectAsStateWithLifecycle(null)
    val cats by c.categoryRepository.observeEntities().collectAsStateWithLifecycle(emptyList())
    val names = cats.associate { it.id to it.name }
    val slices = overview?.byTopLevel.orEmpty().filter { it.second.minor > 0 }
    if (slices.isEmpty()) { EmptyState("No spending yet", "Charts appear once transactions are captured."); return }
    val total = slices.sumOf { it.second.minor }.toFloat()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Spending by category", style = MaterialTheme.typography.titleLarge)
            Canvas(Modifier.fillMaxWidth().height(220.dp).padding(16.dp)) {
                val d = size.minDimension; val stroke = d * 0.18f
                val topLeft = Offset((size.width - d) / 2 + stroke / 2, stroke / 2)
                var start = -90f
                slices.forEachIndexed { i, (_, m) ->
                    val sweep = 360f * m.minor / total
                    drawArc(palette[i % palette.size], start, sweep - 1f, false, topLeft, Size(d - stroke, d - stroke), style = Stroke(stroke))
                    start += sweep
                }
            }
        }
        items(slices.size) { i ->
            val (id, m) = slices[i]
            ListItem(
                leadingContent = { Canvas(Modifier.size(12.dp)) { drawCircle(palette[i % palette.size]) } },
                headlineContent = { Text(names[id] ?: id) },
                trailingContent = { Row { MoneyText(m); Text("  %.0f%%".format(100f * m.minor / total)) } },
            )
        }
    }
}
