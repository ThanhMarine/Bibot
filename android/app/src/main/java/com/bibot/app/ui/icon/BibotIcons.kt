package com.bibot.app.ui.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Một ngôn ngữ icon duy nhất cho Bibot.
 * Mọi icon chức năng (Home, Settings, TopBar) phải đi qua object này —
 * không rải Icons.Filled.* tuỳ tiện ở từng màn hình.
 */
object BibotIcons {
    // Home / mode
    val Mic: ImageVector = Icons.Rounded.Mic
    val Book: ImageVector = Icons.AutoMirrored.Rounded.MenuBook
    val Story: ImageVector = Icons.Rounded.AutoStories
    val FairyTales: ImageVector = Icons.Rounded.AutoAwesome
    val Music: ImageVector = Icons.Rounded.MusicNote
    val Weather: ImageVector = Icons.Rounded.WbCloudy
    val News: ImageVector = Icons.AutoMirrored.Rounded.Article
    val Settings: ImageVector = Icons.Rounded.Settings

    // Settings sections
    val Globe: ImageVector = Icons.Rounded.Public
    val Robot: ImageVector = Icons.Rounded.SmartToy
    val Key: ImageVector = Icons.Rounded.Key
    val Speaker: ImageVector = Icons.AutoMirrored.Rounded.VolumeUp
    val Ticket: ImageVector = Icons.Rounded.ConfirmationNumber
    val Crown: ImageVector = Icons.Rounded.WorkspacePremium
    val Package: ImageVector = Icons.Rounded.Inventory2
    val Books: ImageVector = Icons.AutoMirrored.Rounded.MenuBook
    val Bell: ImageVector = Icons.Rounded.Notifications
    val Broom: ImageVector = Icons.Rounded.CleaningServices
    val Question: ImageVector = Icons.AutoMirrored.Rounded.HelpOutline
    val Letter: ImageVector = Icons.Rounded.MailOutline
    val Info: ImageVector = Icons.Rounded.Info
}
