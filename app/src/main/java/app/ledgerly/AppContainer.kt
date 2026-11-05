package app.ledgerly

import android.content.Context
import app.ledgerly.capture.CaptureQueue
import app.ledgerly.capture.SmsHistoryImporter
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.repo.BudgetRepository
import app.ledgerly.data.repo.CaptureRepository
import app.ledgerly.data.repo.CategoryRepository
import app.ledgerly.data.repo.FxRateRepository
import app.ledgerly.data.repo.RecurringRepository
import app.ledgerly.data.repo.Seeder
import app.ledgerly.data.repo.TransactionRepository
import app.ledgerly.data.repo.TripRepository
import app.ledgerly.data.settings.AppSettings
import app.ledgerly.data.settings.SettingsStore
import app.ledgerly.engine.Categoriser
import app.ledgerly.engine.EngineFactory
import app.ledgerly.engine.Engines
import app.ledgerly.engine.IngestionPipeline
import app.ledgerly.engine.PostIngestNotifier
import app.ledgerly.engine.RuleBasedCategoriser
import app.ledgerly.export.Exporter
import app.ledgerly.security.AppLockManager
import app.ledgerly.security.DbKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Manual dependency injection. One instance per process, owned by [LedgerlyApp]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)

    /** Hot settings snapshot for callbacks that must not suspend (notification listener, app lock). */
    val settingsState: StateFlow<AppSettings> = settingsStore.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    /** Null until DataStore has been read once (avoids flashing onboarding on cold start). */
    val loadedSettings: StateFlow<AppSettings?> = run {
        val nullable: Flow<AppSettings?> = settingsStore.settings
        nullable.stateIn(appScope, SharingStarted.Eagerly, null)
    }

    val database: LedgerlyDatabase by lazy {
        LedgerlyDatabase.build(appContext, DbKeyManager(appContext).getOrCreatePassphrase())
    }

    val engines: Engines by lazy { EngineFactory.create(appContext) }

    val transactionRepository by lazy { TransactionRepository(database, engines.audit) }
    val categoryRepository by lazy { CategoryRepository(database) }
    val fxRateRepository by lazy { FxRateRepository(database) }
    val budgetRepository by lazy { BudgetRepository(database, engines.budget) }
    val tripRepository by lazy { TripRepository(database) }
    val recurringRepository by lazy { RecurringRepository(database) }
    val captureRepository by lazy { CaptureRepository(database) }
    val seeder by lazy { Seeder(database) }

    val categoriser: Categoriser by lazy { RuleBasedCategoriser(database) }
    val notifier by lazy { PostIngestNotifier(appContext) }
    val captureQueue by lazy { CaptureQueue(appContext, database.sourceEventDao()) }
    val smsHistoryImporter by lazy { SmsHistoryImporter(appContext, captureQueue) }
    val exporter by lazy { Exporter(appContext, database) }

    val ingestionPipeline by lazy {
        IngestionPipeline(
            db = database,
            engines = engines,
            categoriser = categoriser,
            transactions = transactionRepository,
            rates = fxRateRepository,
            budgets = budgetRepository,
            trips = tripRepository,
            settingsStore = settingsStore,
            notifier = notifier,
        )
    }

    val appLock = AppLockManager(isEnabled = { settingsState.value.appLockEnabled })
}
