package com.fairshare.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic accent colors that must survive Material You theming.
 *
 * Material 3's `colorScheme.primary` and `colorScheme.tertiary` are
 * pulled from the user's wallpaper on Android 12+ — so a "credit"
 * amount tinted with `primary` can end up orange, mauve or any other
 * hue that disappears against the rest of the UI. The balance view
 * needs the user to instantly tell who owes vs who is owed, so we
 * pin these two colors to fixed greens / reds that stay legible in
 * both light and dark mode regardless of the dynamic palette.
 *
 * Values:
 *   - credit: classic M3 success green (Material `green-900` in light,
 *     `green-200`-ish in dark) — saturated enough to survive against
 *     the dynamic surface.
 *   - debt: M3 error palette (same family the framework uses for
 *     destructive actions) — chosen so colorblind users still
 *     associate it with "alert / pay".
 */
@Immutable
data class FairShareSemanticColors(
    val credit: Color,
    val debt: Color,
)

private val LightSemanticColors = FairShareSemanticColors(
    credit = Color(0xFF1B5E20),
    debt = Color(0xFFB3261E),
)

private val DarkSemanticColors = FairShareSemanticColors(
    credit = Color(0xFF7CCB85),
    debt = Color(0xFFF2B8B5),
)

internal fun semanticColorsFor(darkTheme: Boolean): FairShareSemanticColors =
    if (darkTheme) DarkSemanticColors else LightSemanticColors

val LocalFairShareSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/** Convenience accessor: `FairShareTheme.semanticColors.credit`. */
object FairShareTheme {
    val semanticColors: FairShareSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFairShareSemanticColors.current
}
