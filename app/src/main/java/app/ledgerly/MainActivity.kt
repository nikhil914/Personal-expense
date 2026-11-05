package app.ledgerly

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.ledgerly.ui.common.LocalPrivacyMode
import app.ledgerly.ui.navigation.LedgerlyNavHost
import app.ledgerly.ui.onboarding.OnboardingScreen
import app.ledgerly.ui.theme.LedgerlyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Single activity. Extends FragmentActivity because androidx.biometric's BiometricPrompt needs one.
 */
class MainActivity : FragmentActivity() {

    companion object {
        /** Navigation route to open, e.g. "txn/<id>", "quickadd?cash=true", "trips". */
        const val EXTRA_ROUTE = "route"
    }

    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LedgerlyApp).container
        if (savedInstanceState == null) pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)

        // Privacy mode: FLAG_SECURE blocks screenshots, screen recording and the recents thumbnail.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settingsState.collect { s ->
                    if (s.privacyMode) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            val settings by container.loadedSettings.collectAsStateWithLifecycle()
            val locked by container.appLock.locked.collectAsStateWithLifecycle()
            val route by pendingRoute.collectAsStateWithLifecycle()
            val s = settings
            LedgerlyTheme(dynamicColor = s?.dynamicColor ?: true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        locked -> LockScreen(onUnlock = {
                            container.appLock.authenticate(this@MainActivity, getString(R.string.lock_title), getString(R.string.lock_subtitle))
                        })
                        !s.onboardingDone -> OnboardingScreen()
                        else -> CompositionLocalProvider(LocalPrivacyMode provides s.privacyMode) {
                            LedgerlyNavHost(pendingRoute = route, onRouteConsumed = { pendingRoute.value = null })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 24.dp)) { Text(stringResource(R.string.unlock)) }
    }
}
