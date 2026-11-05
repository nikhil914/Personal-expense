package app.ledgerly.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import app.ledgerly.AppContainer
import app.ledgerly.R
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.TxnType
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.data.db.ReviewState
import app.ledgerly.data.repo.TxnRow
import app.ledgerly.ui.common.CategoryPickerDialog
import app.ledgerly.ui.common.DateFmt
import app.ledgerly.ui.common.EmptyState
import app.ledgerly.ui.common.MoneyText
import app.ledgerly.ui.common.SourceBadges
import app.ledgerly.ui.common.channelLabel
import app.ledgerly.ui.common.ledgerlyViewModel
import app.ledgerly.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TxnFilters(
    val query: String = "",
    val channel: Channel? = null,
    val currency: String? = null,
    val categoryId: String? = null,
)

data class TransactionsState(
    val rows: List<TxnRow> = emptyList(),
    val categories: Map<String, CategoryEntity> = emptyMap(),
    val currencies: List<String> = emptyList(),
    val filters: TxnFilters = TxnFilters(),
)

class TransactionsViewModel(c: AppContainer) : ViewModel() {
    private val filters = MutableStateFlow(TxnFilters())

    val state: StateFlow<TransactionsState> = combine(
        c.transactionRepository.observeActive(),
        c.categoryRepository.observeEntities(),
        filters,
    ) { rows, cats, f ->
        val byId = cats.associateBy { it.id }
        val q = f.query.trim().lowercase()
        val filtered = rows.filter { r ->
            val t = r.txn
            (q.isEmpty() || listOfNotNull(t.merchant, t.merchantRaw, t.note, byId[t.categoryId]?.name, t.original.toDecimal().toPlainString())
                .any { it.lowercase().contains(q) } || t.tags.any { it.contains(q) }) &&
                (f.channel == null || f.channel in t.sourceChannels) &&
                (f.currency == null || t.original.currency.code == f.currency) &&
                (f.categoryId == null || t.categoryId == f.categoryId || byId[t.categoryId]?.parentId == f.categoryId)
        }
        TransactionsState(filtered, byId, rows.map { it.txn.original.currency.code }.distinct().sorted(), f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsState())

    fun update(transform: (TxnFilters) -> TxnFilters) {
        filters.value = transform(filters.value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(nav: NavController) {
    val vm = ledgerlyViewModel { TransactionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showCategoryPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_transactions)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.quickAdd()) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.quick_add)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = { q -> vm.update { it.copy(query = q) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search_transactions)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filters.query.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(query = "") } }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                    }
                },
                singleLine = true,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf(Channel.SMS, Channel.NOTIFICATION, Channel.EMAIL, Channel.MANUAL)) { ch ->
                    FilterChip(
                        selected = state.filters.channel == ch,
                        onClick = { vm.update { it.copy(channel = if (it.channel == ch) null else ch) } },
                        label = { Text(channelLabel(ch)) },
                    )
                }
                item {
                    val catName = state.filters.categoryId?.let { state.categories[it]?.name }
                    FilterChip(
                        selected = catName != null,
                        onClick = { if (catName != null) vm.update { it.copy(categoryId = null) } else showCategoryPicker = true },
                        label = { Text(catName ?: stringResource(R.string.category)) },
                    )
                }
                items(state.currencies) { code ->
                    FilterChip(
                        selected = state.filters.currency == code,
                        onClick = { vm.update { it.copy(currency = if (it.currency == code) null else code) } },
                        label = { Text(code) },
                    )
                }
            }
            if (state.rows.isEmpty()) {
                EmptyState(stringResource(R.string.no_transactions), stringResource(R.string.no_transactions_body))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.txn.id }) { row ->
                    TxnRowItem(row, state.categories) { nav.navigate(Routes.txn(row.txn.id)) }
                    HorizontalDivider()
                }
            }
        }
    }
    if (showCategoryPicker) {
        CategoryPickerDialog(
            categories = state.categories.values.sortedBy { it.sortOrder },
            onPick = { cat -> vm.update { it.copy(categoryId = cat.id) }; showCategoryPicker = false },
            onDismiss = { showCategoryPicker = false },
        )
    }
}

@Composable
fun TxnRowItem(row: TxnRow, categories: Map<String, CategoryEntity>, onClick: () -> Unit) {
    val t = row.txn
    val isIncome = t.type == TxnType.INCOME || t.type == TxnType.REFUND
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(t.merchant ?: t.merchantRaw ?: categories[t.categoryId]?.name ?: t.type.name.lowercase().replaceFirstChar { it.uppercase() }) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(DateFmt.date(t.occurredAt), categories[t.categoryId]?.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                SourceBadges(t.sourceChannels)
                if (row.reviewState != ReviewState.OK) {
                    Text(
                        stringResource(if (row.reviewState == ReviewState.POSSIBLE_DUPLICATE) R.string.possible_duplicate else R.string.needs_review),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(t.base, income = isIncome)
                if (t.original.currency != t.base.currency) {
                    MoneyText(t.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}
