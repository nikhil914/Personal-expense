package app.ledgerly.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF00897B)
private val TealDark = Color(0xFF4DB6AC)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Color(0xFF5C6BC0),
    tertiary = Color(0xFFF9A825),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00201C),
    secondary = Color(0xFF9FA8DA),
    tertiary = Color(0xFFFFD54F),
    error = Color(0xFFEF9A9A),
)

/** Semantic colours for money that work in both themes. */
object MoneyColors {
    val income = Color(0xFF2E7D32)
    val incomeDark = Color(0xFF81C784)
    val chart = listOf(
        Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFFFFB74D), Color(0xFF4DB6AC),
        Color(0xFFF06292), Color(0xFF9575CD), Color(0xFF81C784), Color(0xFFA1887F), Color(0xFF90A4AE),
    )
}

@Composable
fun LedgerlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}
