package app.ledgerly.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ledgerly.AppContainer
import app.ledgerly.LedgerlyApp

/** Creates a ViewModel with manual DI from the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> ledgerlyViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as LedgerlyApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as LedgerlyApp).container
