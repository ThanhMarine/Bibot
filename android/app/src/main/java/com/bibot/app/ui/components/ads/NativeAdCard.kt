package com.bibot.app.ui.components.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.core.ads.AdCopy

/**
 * NativeInlineAd — quang cao TINH giua ky doc (TTS Bypass).
 * - Fixed height (~110dp) de khong layout-shift khi LazyColumn auto-scroll karaoke.
 * - Khong audio, khong video tu phat, khong animation nang.
 * - Nhan "Quang cao"/"Ad" + badge gia dinh cho Cotich.
 * - Hien tai la placeholder; load AdMob NativeAd that se thay body giu nguyen khung.
 */
object NativeInlineAd {
    /** Vi tri chen (0-based): thuong ~80 (~8', giua ky 16'), Cotich ~160 (1 native/2 chu ky). */
    const val INDEX_DEFAULT = 79
    const val INDEX_COTICH = 159
}

@Composable
fun NativeInlineAd(
    lang: String,
    isFamily: Boolean,
    modifier: Modifier = Modifier
) {
    if (!com.bibot.app.BuildConfig.ENABLE_ADS) return
    // VIP: sach ads giua ky
    val vipCtx = LocalContext.current
    val vipPrefs = remember { com.bibot.app.data.ads.AdsPreferences(vipCtx) }
    val vipState by vipPrefs.adsStateFlow.collectAsState(initial = com.bibot.app.data.ads.AdsState())
    if (com.bibot.app.data.ads.isVipActive(vipState)) return
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 110.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                AdCopy.nativeLabel(lang) + if (isFamily) " • " + AdCopy.familyBadge(lang) else "",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (lang == "en") "Sponsored content. Reading continues below."
                else "Noi dung tai tro. Ban doc tiep ben duoi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
