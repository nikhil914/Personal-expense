package app.ledgerly

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import app.ledgerly.capture.RetentionWorker
import app.ledgerly.engine.PostIngestNotifier
import app.ledgerly.fx.RateSyncWorker
import app.ledgerly.widget.WidgetRefreshWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LedgerlyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PostIngestNotifier.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appLock)

        container.appScope.launch {
            val settings = container.settingsStore.settings.first()
            if (settings.appLockEnabled) container.appLock.lock()
            container.seeder.seedIfEmpty(settings.baseCurrency)
        }

        RateSyncWorker.schedule(this)
        RetentionWorker.schedule(this)
        WidgetRefreshWorker.schedule(this)
    }
}
