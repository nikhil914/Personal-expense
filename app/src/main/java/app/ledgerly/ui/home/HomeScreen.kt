package app.ledgerly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import app.ledgerly.AppContainer
import app.ledgerly.R
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.data.db.RecurringEntity
import app.ledgerly.data.db.TripEntity
import app.ledgerly.data.repo.BudgetOverview
import app.ledgerly.engine.FinancialPeriod
import app.ledgerly.engine.SafeToSpend
import app.ledgerly.ui.common.DateFmt
import app.ledgerly.ui.common.MoneyText
import app.ledgerly.ui.common.SectionHeader
import app.ledgerly.ui.common.ledgerlyViewModel
import app.ledgerly.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class HomeState(
    val base: CurrencyCode,
    val safeToSpend: SafeToSpend?,
    val overview: BudgetOverview,
    val categories: Map<String, CategoryEntity>,
    val upcoming: List<RecurringEntity>,
    val activeTrip: TripEntity?,
    val reviewCount: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val zone = ZoneId.systemDefault()

    val state: StateFlow<HomeState?> = c.settingsState.flatMapLatest { s ->
        val today = LocalDate.now(zone)
        val period = FinancialPeriod.current(today, s.monthStartDay)
        combine(
            c.budgetRepository.observeOverview(s.baseCurrency, period, zone),
            c.categoryRepository.observeEntities(),
            c.recurringRepository.observeUpcoming(today, today.plusDays(14)),
            c.tripRepository.observeActive(),
            c.transactionRepository.observeReviewCount(),
        ) { overview, cats, upcoming, trip, reviewCount ->
            HomeState(s.baseCurrency, null, overview, cats.associateBy { it.id }, upcoming, trip, reviewCount)
        }.mapLatest { st -> st.copy(safeToSpend = c.budgetRepository.safeToSpend(s.baseCurrency, today, period, zone)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val vm = ledgerlyViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.quickAdd()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.quick_add)) },
            )
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            s.activeTrip?.let { trip ->
                item { TripBanner(trip) { nav.navigate(Routes.TRIPS) } }
            }
            if (s.reviewCount > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { nav.navigate(Routes.REVIEW) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Inbox, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.review_banner, s.reviewCount))
                        }
                    }
                }
            }
            item { SafeToSpendCard(s) { nav.navigate(Routes.BUDGETS) } }
            item { MonthCard(s) }
            item { SectionHeader(stringResource(R.string.top_categories)) }
            val top = s.overview.byTopLevel.take(5)
            if (top.isEmpty()) {
                item { Text(stringResource(R.string.no_spend_yet), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(top, key = { it.first }) { (catId, amount) ->
                ListItem(
                    headlineContent = { Text(s.categories[catId]?.name ?: catId) },
                    trailingContent = { MoneyText(amount, whole = true) },
                )
            }
            item { SectionHeader(stringResource(R.string.upcoming_bills)) }
            if (s.upcoming.isEmpty()) {
                item { Text(stringResource(R.string.no_upcoming), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(s.upcoming, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.name) },
                    supportingContent = { r.nextDue?.let { Text(DateFmt.date(it)) } },
                    trailingContent = { MoneyText(Money(r.amountMinor, CurrencyCode.of(r.currency))) },
                    modifier = Modifier.clickable { nav.navigate(Routes.SUBSCRIPTIONS) },
                )
            }
            item { Spacer(Modifier.padding(48.dp)) }
        }
    }
}

@Composable
private fun SafeToSpendCard(s: HomeState, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.safe_to_spend_today), style = MaterialTheme.typography.labelLarge)
            val sts = s.safeToSpend
            if (sts == null) {
                Text(stringResource(R.string.set_budget_prompt), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            } else {
                MoneyText(
                    sts.today,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = if (sts.today.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                    whole = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoneyText(sts.remainingInPeriod, style = MaterialTheme.typography.bodyMedium, whole = true)
                    Text(" " + stringResource(R.string.left_for_days, sts.daysLeft), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MonthCard(s: HomeState) {
    val o = s.overview
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.month_spend_vs_budget, DateFmt.date(o.period.start), DateFmt.date(o.period.end.minusDays(1))),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyText(o.spent, style = MaterialTheme.typography.headlineSmall, whole = true)
                o.total?.let {
                    Text(" / ", style = MaterialTheme.typography.bodyLarge)
                    MoneyText(it, style = MaterialTheme.typography.bodyLarge, whole = true)
                }
            }
            o.total?.let { total ->
                val frac = if (total.minor > 0) (o.spent.minor.toFloat() / total.minor).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (o.spent.minor > total.minor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TripBanner(trip: TripEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flight, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.trip_mode_on, trip.name), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.trip_since, DateFmt.date(trip.startDate), trip.currency), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
