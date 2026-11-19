package com.bibot.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Nút bấm to — chuẩn phone/tablet, tối ưu cho người lớn tuổi / thao tác một tay / trên ô tô.
 * - Hit-target tối thiểu 56dp (phone) / 64dp (tablet), khuyến nghị 72dp cho nút chính.
 * - Haptic feedback, semantics Role.Button, contentDescription đầy đủ.
 * - Hỗ trợ icon Material + label 2 dòng, không cắt chữ.
 */
@Composable
fun BigButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    subLabel: String? = null,
    enabled: Boolean = true,
    height: Dp = 64.dp,
    isPrimary: Boolean = true,
    icon: @Composable (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val colors = if (isPrimary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
    val btnModifier = modifier
        .heightIn(min = height)
        .semantics { role = Role.Button }

    if (isPrimary) {
        Button(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
            enabled = enabled,
            modifier = btnModifier,
            colors = colors,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
        ) { BigButtonContent(label, subLabel, icon) }
    } else {
        FilledTonalButton(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            enabled = enabled,
            modifier = btnModifier,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
        ) { BigButtonContent(label, subLabel, icon) }
    }
}

@Composable
private fun BigButtonContent(label: String, subLabel: String?, icon: @Composable (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Box(Modifier.padding(end = 8.dp)) { icon() }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 20.sp)
            if (subLabel != null) Text(subLabel, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun BigIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    iconSize: Dp = 36.dp,
    enabled: Boolean = true,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary,
    icon: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    FilledIconButton(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        enabled = enabled,
        modifier = modifier.size(size).semantics { role = Role.Button },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
fun BigModeTile(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    height: Dp = 96.dp
) {
    val haptic = LocalHapticFeedback.current
    Card(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        modifier = modifier.heightIn(min = height).semantics { role = Role.Button },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
