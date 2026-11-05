package app.ledgerly.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import app.ledgerly.AppContainer
import app.ledgerly.R
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Split
import app.ledgerly.core.model.Transaction
import app.ledgerly.data.db.AuditActor
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.data.db.ReviewState
import app.ledgerly.data.db.SourceEventEntity
import app.ledgerly.data.db.TransactionEventEntity
import app.ledgerly.data.repo.TxnDetail
import app.ledgerly.ui.common.CategoryPickerDialog
import app.ledgerly.ui.common.DateFmt
import app.ledgerly.ui.common.MoneyText
import app.ledgerly.ui.common.SectionHeader
import app.ledgerly.ui.common.SourceBadges
import app.ledgerly.ui.common.channelLabel
import app.ledgerly.ui.common.ledgerlyViewModel
import app.ledgerly.widget.SafeToSpendWidget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal

class TransactionDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val detail: StateFlow<TxnDetail?> = c.transactionRepository.observeDetail(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val categories: StateFlow<List<CategoryEntity>> = c.categoryRepository.observeEntities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun edit(transform: (Transaction) -> Transaction) = viewModelScope.launch {
        val current = c.transactionRepository.get(id) ?: return@launch
        c.transactionRepository.update(transform(current), AuditActor.USER)
        SafeToSpendWidget.refresh(c.let { app -> appContext(app) })
    }

    fun setCategory(categoryId: String) = edit { it.copy(categoryId = categoryId, isReviewed = true) }
    fun setNote(note: String) = edit { it.copy(note = note.ifBlank { null }) }
    fun addTag(tag: String) = edit { it.copy(tags = it.tags + tag.trim().lowercase().removePrefix("#")) }
    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }
    fun setSplits(splits: List<Split>) = edit { it.copy(splits = splits) }
    fun markReviewed() = viewModelScope.launch { c.transactionRepository.markReviewed(id) }
    fun delete() = viewModelScope.launch { c.transactionRepository.softDelete(id) }
    fun restore() = viewModelScope.launch { c.transactionRepository.restore(id) }
    fun alwaysCategorise(merchant: String, categoryId: String) = viewModelScope.launch {
        c.categoryRepository.addMerchantRule(merchant, categoryId, id)
    }

    private fun appContext(container: AppContainer): android.content.Context = container.appContext
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(nav: NavController, id: String) {
    val vm = ledgerlyViewModel(key = "txn-$id") { TransactionDetailViewModel(it, id) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCategory by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var showTag by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }
    val catById = categories.associateBy { it.id }

    val movedToTrash = stringResource(R.string.moved_to_trash)
    val undo = stringResource(R.string.undo)
    val alwaysLabel = stringResource(R.string.always)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transaction)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    val d = detail
                    if (d != null && d.row.txn.deletedAt == null) {
                        IconButton(onClick = {
                            vm.delete()
                            scope.launch {
                                val r = snackbar.showSnackbar(movedToTrash, actionLabel = undo, duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed) vm.restore() else nav.popBackStack()
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
                    } else if (d != null) {
                        IconButton(onClick = { vm.restore() }) { Icon(Icons.Filled.RestoreFromTrash, contentDescription = stringResource(R.string.restore)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val t = d.row.txn
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Header(t, d.row.reviewState, catById, onCategory = { showCategory = true }, onReviewed = { vm.markReviewed() }) }
            item { AmountsCard(t) }
            item {
                SectionHeader(stringResource(R.string.details))
                DetailLine(stringResource(R.string.note), t.note ?: stringResource(R.string.add_note)) { showNote = true }
                DetailLine(stringResource(R.string.reference), t.reference ?: "—")
                DetailLine(stringResource(R.string.account), t.accountLast4?.let { "••$it" } ?: "—")
                DetailLine(stringResource(R.string.status), t.status.name.lowercase())
                d.row.confidence?.let { DetailLine(stringResource(R.string.parse_confidence), "${(it * 100).toInt()}%") }
            }
            item {
                SectionHeader(stringResource(R.string.tags), action = { TextButton(onClick = { showTag = true }) { Text(stringResource(R.string.add)) } })
                LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(t.tags.sorted()) { tag ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeTag(tag) },
                            label = { Text("#$tag") },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.split), action = { TextButton(onClick = { showSplit = true }) { Text(stringResource(R.string.edit)) } })
                if (t.splits.isEmpty()) {
                    Text(stringResource(R.string.no_split), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                t.splits.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(catById[s.categoryId]?.name ?: s.categoryId, modifier = Modifier.weight(1f))
                        MoneyText(s.amount)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.original_messages)) }
            if (d.sourceEvents.isEmpty()) {
                item { Text(stringResource(R.string.manual_entry), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(d.sourceEvents, key = { it.id }) { RawMessageCard(it) }
            item { SectionHeader(stringResource(R.string.edit_history)) }
            items(d.history, key = { it.id }) { HistoryEntry(it) }
            item { Spacer(Modifier.padding(32.dp)) }
        }

        if (showCategory) {
            CategoryPickerDialog(categories, onPick = { cat ->
                showCategory = false
                vm.setCategory(cat.id)
                val merchant = t.merchant ?: t.merchantRaw
                if (merchant != null) {
                    scope.launch {
                        val r = snackbar.showSnackbar("$alwaysLabel: $merchant → ${cat.name}?", actionLabel = alwaysLabel, duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) vm.alwaysCategorise(merchant, cat.id)
                    }
                }
            }, onDismiss = { showCategory = false })
        }
        if (showTag) TextInputDialog(stringResource(R.string.add_tag), "", onDone = { if (it.isNotBlank()) vm.addTag(it); showTag = false }, onDismiss = { showTag = false })
        if (showNote) TextInputDialog(stringResource(R.string.note), t.note.orEmpty(), onDone = { vm.setNote(it); showNote = false }, onDismiss = { showNote = false })
        if (showSplit) SplitDialog(t, categories, onSave = { vm.setSplits(it); showSplit = false }, onDismiss = { showSplit = false })
    }
}

@Composable
private fun Header(t: Transaction, reviewState: ReviewState, cats: Map<String, CategoryEntity>, onCategory: () -> Unit, onReviewed: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(t.merchant ?: t.merchantRaw ?: stringResource(R.string.unknown_merchant), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(DateFmt.dateTime(t.occurredAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onCategory, label = { Text(cats[t.categoryId]?.name ?: stringResource(R.string.uncategorised)) })
            SourceBadges(t.sourceChannels)
        }
        if (t.deletedAt != null) {
            Text(stringResource(R.string.in_trash), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (reviewState != ReviewState.OK) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (reviewState == ReviewState.POSSIBLE_DUPLICATE) R.string.possible_duplicate else R.string.needs_review),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onReviewed) { Text(stringResource(R.string.looks_right)) }
            }
        }
    }
}

/** Original vs account vs base amounts, applied/market rate and the FX fee exposer line. */
@Composable
private fun AmountsCard(t: Transaction) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AmountLine(stringResource(R.string.amount_original), t.original)
            if (t.account != t.original) AmountLine(stringResource(R.string.amount_account), t.account)
            if (t.base != t.account) AmountLine(stringResource(R.string.amount_base), t.base)
            t.fxRateApplied?.let { DetailText(stringResource(R.string.rate_applied), it.stripTrailingZeros().toPlainString()) }
            t.marketRate?.let { DetailText(stringResource(R.string.rate_market, t.rateSource ?: "", t.rateDate?.toString() ?: ""), it.stripTrailingZeros().toPlainString()) }
            t.fxFee?.let { fee ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fx_fee), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    MoneyText(fee, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AmountLine(label: String, money: Money) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        MoneyText(money, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DetailText(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun RawMessageCard(e: SourceEventEntity) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channelLabel(e.channel)} · ${e.sender ?: e.packageName ?: ""}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            Text(DateFmt.dateTime(e.receivedAt), style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                val body = e.body
                Text(
                    text = if (body != null) listOfNotNull(e.title, body).joinToString("\n") else stringResource(R.string.raw_purged),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.parsed_by, e.parserId ?: "—", e.parseStatus?.name ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryEntry(e: TransactionEventEntity) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            val change = when {
                e.field != null && (e.oldValue != null || e.newValue != null) -> "${e.field}: ${e.oldValue ?: "∅"} → ${e.newValue ?: "∅"}"
                else -> e.newValue ?: ""
            }
            Text("${e.type.name.lowercase().replace('_', ' ')} · ${e.actor.name.lowercase()}", style = MaterialTheme.typography.labelLarge)
            if (change.isNotEmpty()) Text(change, style = MaterialTheme.typography.bodySmall)
            e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(DateFmt.dateTime(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Split editor: lines of category + amount; must add up to the base amount. */
@Composable
private fun SplitDialog(t: Transaction, categories: List<CategoryEntity>, onSave: (List<Split>) -> Unit, onDismiss: () -> Unit) {
    val ccy = t.base.currency
    val lines = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            if (t.splits.isEmpty()) add((t.categoryId ?: "other") to t.base.toDecimal().toPlainString())
            else t.splits.forEach { add(it.categoryId to it.amount.toDecimal().toPlainString()) }
        }
    }
    var pickFor by remember { mutableStateOf<Int?>(null) }
    val catById = categories.associateBy { it.id }
    val total = lines.sumOf { it.second.toBigDecimalOrNull() ?: BigDecimal.ZERO }
    val valid = total.compareTo(t.base.toDecimal()) == 0 && lines.all { (it.second.toBigDecimalOrNull() ?: BigDecimal.ZERO).signum() > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEachIndexed { i, (catId, amount) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickFor = i }, modifier = Modifier.weight(1f)) { Text(catById[catId]?.name ?: catId, maxLines = 1) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { v -> lines[i] = catId to v },
                            modifier = Modifier.width(110.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (lines.size > 1) IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove)) }
                    }
                }
                TextButton(onClick = { lines.add("other" to "0") }) { Text(stringResource(R.string.add_split_line)) }
                Text(
                    stringResource(R.string.split_total, total.toPlainString(), t.base.toDecimal().toPlainString(), ccy.code),
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val splits = if (lines.size == 1) emptyList() else lines.map { (cat, amt) -> Split(cat, Money.of(amt, ccy)) }
                onSave(splits)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    pickFor?.let { idx ->
        CategoryPickerDialog(categories, onPick = { cat -> lines[idx] = cat.id to lines[idx].second; pickFor = null }, onDismiss = { pickFor = null })
    }
}
