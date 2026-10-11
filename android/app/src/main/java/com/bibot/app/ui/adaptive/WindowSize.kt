package com.bibot.app.ui.adaptive

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Adaptive helpers — phone vs tablet.
 * Compact <600dp (phone portrait), Medium 600-840dp (tablet portrait / phone landscape), Expanded >=840dp (tablet).
 * Dùng để đổi: nút 56→72dp, NavigationBar → NavigationRail, grid 2→4 cột, player height.
 */
enum class DevicePosture { Phone, Tablet, LargeTablet }

@Composable
fun rememberDevicePosture(windowSizeClass: WindowSizeClass? = null): DevicePosture {
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp
    // Fallback khi chưa có WindowSizeClass (preview)
    if (windowSizeClass == null) {
        return when {
            widthDp >= 840 -> DevicePosture.LargeTablet
            widthDp >= 600 -> DevicePosture.Tablet
            else -> DevicePosture.Phone
        }
    }
    return when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> DevicePosture.LargeTablet
        WindowWidthSizeClass.Medium -> DevicePosture.Tablet
        else -> DevicePosture.Phone
    }
}

@Composable
fun isTablet(windowSizeClass: WindowSizeClass? = null): Boolean = rememberDevicePosture(windowSizeClass) != DevicePosture.Phone

fun bigButtonHeight(isTablet: Boolean): Dp = if (isTablet) 72.dp else 64.dp
fun fabSize(isTablet: Boolean): Dp = if (isTablet) 96.dp else 72.dp
fun playerBarHeight(isTablet: Boolean): Dp = if (isTablet) 96.dp else 72.dp
