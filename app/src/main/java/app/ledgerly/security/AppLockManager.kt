package app.ledgerly.security

import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Biometric / device-credential app lock. Registered on ProcessLifecycleOwner: when the app returns to
 * the foreground after [timeoutMillis] in the background, [locked] becomes true and MainActivity shows
 * the lock screen and prompts.
 */
class AppLockManager(private val isEnabled: () -> Boolean, private val timeoutMillis: Long = 30_000L) : DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null
    private var coldStart = true

    override fun onStart(owner: LifecycleOwner) {
        val bg = backgroundedAt
        if (isEnabled() && (coldStart || (bg != null && SystemClock.elapsedRealtime() - bg >= timeoutMillis))) _locked.value = true
        coldStart = false
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Called when the user enables the lock in Settings, so it doesn't lock immediately. */
    fun markUnlocked() {
        _locked.value = false
    }

    /** Called once settings are loaded at startup when the lock is enabled (cold-start lock). */
    fun lock() {
        _locked.value = true
    }

    fun authenticate(activity: FragmentActivity, title: String, subtitle: String) {
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric or screen lock configured: the lock cannot be enforced, so don't trap the user.
            _locked.value = false
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _locked.value = false
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators) // no negative button when DEVICE_CREDENTIAL is allowed
            .build()
        prompt.authenticate(info)
    }
}
