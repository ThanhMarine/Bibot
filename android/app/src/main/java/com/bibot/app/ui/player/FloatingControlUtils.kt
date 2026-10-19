package com.bibot.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A3.3: Shared utilities for floating controls.
 * TODO: Use proper WindowInsets when Compose version supports it.
 */
@Composable
fun contentBottomInset(): Dp =
    FloatingControlDefaults.ContentBottomInset + 56.dp // TODO: Use proper WindowInsets when Compose version supports it