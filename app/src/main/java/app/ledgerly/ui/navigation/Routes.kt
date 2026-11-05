package app.ledgerly.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.ui.graphics.vector.ImageVector
import app.ledgerly.R

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val INSIGHTS = "insights"
    const val ASK = "ask"
    const val MORE = "more"
    const val TXN = "txn/{id}"
    const val QUICK_ADD = "quickadd?cash={cash}"
    const val REVIEW = "review"
    const val IGNORED = "ignored"
    const val BUDGETS = "budgets"
    const val TRIPS = "trips"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SETTINGS = "settings"
    const val TRASH = "trash"

    fun txn(id: String) = "txn/$id"
    fun quickAdd(cash: Boolean = false) = "quickadd?cash=$cash"
}

enum class TopLevel(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Home(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Transactions(Routes.TRANSACTIONS, R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    Insights(Routes.INSIGHTS, R.string.nav_insights, Icons.Filled.Insights),
    Ask(Routes.ASK, R.string.nav_ask, Icons.Filled.QuestionAnswer),
    More(Routes.MORE, R.string.nav_more, Icons.Filled.Menu),
}
