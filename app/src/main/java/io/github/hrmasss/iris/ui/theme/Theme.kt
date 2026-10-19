package io.github.hrmasss.iris.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB3C1),
    onPrimary = Color(0xFF5E1129),
    primaryContainer = Color(0xFF7C2940),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFD9BAFF),
    onSecondary = Color(0xFF3F1A6B),
    secondaryContainer = Color(0xFF573383),
    onSecondaryContainer = Color(0xFFEEDBFF),
    tertiary = Color(0xFFFFB68E),
    background = Color(0xFF150F1C),
    surface = Color(0xFF150F1C),
    surfaceContainer = Color(0xFF221B2A),
    surfaceContainerHigh = Color(0xFF2D2535),
    surfaceContainerHighest = Color(0xFF383040),
)

private val Light = lightColorScheme(
    primary = Color(0xFF9B3F57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF3F0016),
    secondary = Color(0xFF704C9D),
    secondaryContainer = Color(0xFFEEDBFF),
    onSecondaryContainer = Color(0xFF29004F),
    tertiary = Color(0xFF8A4F2B),
    background = Color(0xFFFFF7FA),
    surface = Color(0xFFFFF7FA),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IrisTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
