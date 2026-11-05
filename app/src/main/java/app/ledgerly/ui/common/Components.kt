package app.ledgerly.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ledgerly.R
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.ui.theme.MoneyColors
import app.ledgerly.util.MoneyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** True when privacy mode is on: amounts are masked until tapped. */
val LocalPrivacyMode = staticCompositionLocalOf { false }

@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    income: Boolean = false,
    color: Color = Color.Unspecified,
    whole: Boolean = false,
) {
    val privacy = LocalPrivacyMode.current
    var revealed by remember { mutableStateOf(false) }
    val text = if (privacy && !revealed) "••••" else if (whole) MoneyFormat.formatWhole(money) else MoneyFormat.format(money)
    val c = when {
        color != Color.Unspecified -> color
        income -> if (isSystemInDarkTheme()) MoneyColors.incomeDark else MoneyColors.income
        else -> Color.Unspecified
    }
    Text(
        text = if (income && !(privacy && !revealed)) "+$text" else text,
        style = style,
        color = c,
        modifier = if (privacy) modifier.clickable { revealed = !revealed } else modifier,
    )
}

@Composable
fun channelLabel(channel: Channel): String = when (channel) {
    Channel.SMS -> stringResource(R.string.channel_sms)
    Channel.NOTIFICATION -> stringResource(R.string.channel_notification)
    Channel.EMAIL -> stringResource(R.string.channel_email)
    Channel.OCR -> stringResource(R.string.channel_receipt)
    Channel.MANUAL -> stringResource(R.string.channel_manual)
    Channel.IMPORT -> stringResource(R.string.channel_import)
    Channel.BANK_API -> stringResource(R.string.channel_bank)
}

/** Merged-sources badge: "SMS + Notification + Email" as small pills. */
@Composable
fun SourceBadges(channels: Set<Channel>, modifier: Modifier = Modifier) {
    if (channels.isEmpty()) return
    val sorted = channels.sortedBy { it.ordinal }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        sorted.forEachIndexed { i, ch ->
            if (i > 0) Text("+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = RoundedCornerShape(50),
                color = if (sorted.size > 1) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = channelLabel(ch),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

object DateFmt {
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    fun date(i: Instant): String = date.format(i.atZone(ZoneId.systemDefault()))
    fun dateTime(i: Instant): String = dateTime.format(i.atZone(ZoneId.systemDefault()))
    fun date(d: java.time.LocalDate): String = date.format(d)
}

/** Two-level category picker. Parents are selectable too. */
@Composable
fun CategoryPickerDialog(
    categories: List<CategoryEntity>,
    onPick: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val parents = categories.filter { it.parentId == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.pick_category)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                parents.forEach { p ->
                    item(key = p.id) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp),
                        )
                    }
                    items(categories.filter { it.parentId == p.id }, key = { it.id }) { c ->
                        Text(
                            c.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        },
    )
}

val COMMON_CURRENCIES = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD", "JPY", "AUD", "CAD", "CHF", "THB", "MYR", "HKD", "LKR", "NPR", "SAR", "KWD", "ZAR", "NZD", "SEK")

@Composable
fun CurrencyPickerDialog(selected: CurrencyCode?, onPick: (CurrencyCode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.pick_currency)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(COMMON_CURRENCIES) { code ->
                    val name = runCatching { java.util.Currency.getInstance(code).displayName }.getOrDefault(code)
                    Text(
                        text = "$code  ·  $name",
                        fontWeight = if (selected?.code == code) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(CurrencyCode.of(code)) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
    )
}

/** Simple saveable text state helper for dialogs. */
@Composable
fun rememberText(initial: String = "") = rememberSaveable { mutableStateOf(initial) }
