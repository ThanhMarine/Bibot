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

private val DarkColorScheme = darkColorScheme(
    primary = BibotAccentBright,
    onPrimary = BibotOnAccentDark,
    primaryContainer = BibotAccentDeep,
    onPrimaryContainer = BibotAccentBright,
    secondary = BibotSecondaryDark,
    onSecondary = BibotOnAccentDark,
    secondaryContainer = BibotSecondaryContainerDark,
    onSecondaryContainer = BibotSecondaryDark,
    tertiary = BibotTertiaryDark,
    onTertiary = BibotOnAccentDark,
    tertiaryContainer = BibotTertiaryContainerDark,
    onTertiaryContainer = BibotTertiaryDark,
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
    onPrimary = BibotOnAccent,
    primaryContainer = BibotAccentContainerLight,
    onPrimaryContainer = BibotAccentDeep,
    secondary = BibotSecondaryLight,
    onSecondary = BibotOnAccent,
    secondaryContainer = BibotSecondaryContainerLight,
    onSecondaryContainer = BibotAccentDeep,
    tertiary = BibotTertiaryLight,
    onTertiary = BibotOnAccent,
    tertiaryContainer = BibotTertiaryContainerLight,
    onTertiaryContainer = BibotAccentDeep,
    background = BibotBackgroundLight,
    onBackground = BibotOnSurfaceLight,
    surface = BibotSurfaceLight,
    onSurface = BibotOnSurfaceLight,
    surfaceVariant = BibotSurfaceVariantLight,
    onSurfaceVariant = BibotOnSurfaceVariantLight,
    outline = BibotOutlineLight,
    outlineVariant = BibotOutlineLight.copy(alpha = 0.6f)
)

@Composable
fun BibotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Mặc định TẮT: palette Bibot nhất quán trên mọi máy (không Material You).
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
