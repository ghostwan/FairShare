package com.fairshare.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val LightColors = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF535F70),
    tertiary = Color(0xFF6B5778),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    secondary = Color(0xFFBBC7DB),
    tertiary = Color(0xFFD6BEE4),
)

@Composable
fun FairShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disabled by default: the app's identity is built around the blue
    // palette below and the matching launcher icon. Letting Material
    // You hijack the scheme produces unpredictable hues (orange / mauve
    // primaries depending on the wallpaper) that clash with the icon
    // and wash out the semantic credit / debt colors in the balances
    // view. Leave the flag in the signature so a future per-user
    // toggle can opt back in.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalFairShareSemanticColors provides semanticColorsFor(darkTheme),
            content = content,
        )
    }
}
