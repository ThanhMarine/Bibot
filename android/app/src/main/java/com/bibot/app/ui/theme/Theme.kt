package com.bibot.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Nền sáng -> chữ đen đậm. Nền tối -> chữ trắng đậm.
 * dynamicColor = false để palette nhất quán, nhưng vẫn theo hệ thống sáng/tối.
 */
private val DarkColorScheme = darkColorScheme(
    primary = BibotAccentBright,
    onPrimary = BibotOnSurfaceDark,
    primaryContainer = BibotAccentDeep,
    onPrimaryContainer = BibotOnSurfaceDark,
    secondary = BibotSecondaryDark,
    onSecondary = BibotOnSurfaceDark,
    secondaryContainer = BibotSecondaryContainerDark,
    onSecondaryContainer = BibotOnSurfaceDark,
    tertiary = BibotTertiaryDark,
    onTertiary = BibotOnSurfaceDark,
    tertiaryContainer = BibotTertiaryContainerDark,
    onTertiaryContainer = BibotOnSurfaceDark,
    background = BibotBackgroundDark,
    onBackground = BibotOnSurfaceDark,
    surface = BibotSurfaceDark,
    onSurface = BibotOnSurfaceDark,
    surfaceVariant = BibotSurfaceVariantDark,
    onSurfaceVariant = BibotOnSurfaceVariantDark,
    outline = BibotOutlineDark,
    outlineVariant = BibotOutlineDark.copy(alpha = 0.5f)
)

private val LightColorScheme = lightColorScheme(
    primary = BibotAccent,
    onPrimary = BibotInk,
    primaryContainer = BibotAccentContainerLight,
    onPrimaryContainer = BibotInk,
    secondary = BibotSecondaryLight,
    onSecondary = BibotInk,
    secondaryContainer = BibotSecondaryContainerLight,
    onSecondaryContainer = BibotInk,
    tertiary = BibotTertiaryLight,
    onTertiary = BibotInk,
    tertiaryContainer = BibotTertiaryContainerLight,
    onTertiaryContainer = BibotInk,
    background = BibotBackgroundLight,
    onBackground = BibotInk,
    surface = BibotSurfaceLight,
    onSurface = BibotInk,
    surfaceVariant = BibotSurfaceVariantLight,
    onSurfaceVariant = BibotInk,
    outline = BibotOutlineLight,
    outlineVariant = BibotOutlineLight.copy(alpha = 0.6f)
)

@Composable
fun BibotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
