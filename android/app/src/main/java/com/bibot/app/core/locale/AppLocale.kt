package com.bibot.app.core.locale

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * P1 i18n: ap dung ngon ngu app (system/vi/en) theo pref `language`.
 * - API 33+: android.app.LocaleManager.setApplicationLocales (khong can restart).
 * - Duoi 33: doi locale cau hinh + recreate() Activity.
 * Khong dung appcompat chi de doi ngon ngu (nang APK vo ich).
 */
object AppLocale {
    const val SYSTEM = "system"
    const val VI = "vi"
    const val EN = "en"
    private const val PREF_FILE = "bibot_locale"
    private const val PREF_KEY = "lang"

    /** Ngon ngu he thong hien tai (ma 2 chu, vd "vi"/"en"). */
    fun systemLang(): String = try {
        Locale.getDefault().language?.lowercase()?.take(2)?.ifBlank { VI } ?: VI
    } catch (_: Exception) { VI }

    /** Giai quyet "system" -> ma that de so sanh/hien thi. */
    fun resolve(pref: String): String = when (pref) {
        VI -> VI
        EN -> EN
        else -> systemLang()
    }

    /** Ap dung ngay khi mo app (goi trong BibotApp.onCreate). */
    fun applyOnStart(context: Context, pref: String) {
        if (pref == SYSTEM) return // de he thong tu xu (API 33+ doc localeConfig)
        apply(context, pref)
    }

    fun apply(context: Context, lang: String) {
        if (lang == SYSTEM) {
            clearOverride(context)
        } else {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val mgr = context.getSystemService(android.app.LocaleManager::class.java)
                    mgr?.applicationLocales = android.os.LocaleList.forLanguageTags(lang)
                }
            } catch (_: Exception) {}
        }
        // Mirror dong bo cho attachBaseContext (may < API 33 khong co LocaleManager)
        try {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, lang).apply()
        } catch (_: Exception) {}
        LocaleListCompat.create(Locale(if (lang == SYSTEM) systemLang() else lang))
    }

    /** Doc lua chon da luu (dong bo) — dung trong Activity.attachBaseContext. */
    fun savedLang(context: Context): String = try {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY, SYSTEM) ?: SYSTEM
    } catch (_: Exception) { SYSTEM }

    private fun clearOverride(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val mgr = context.getSystemService(android.app.LocaleManager::class.java)
                mgr?.applicationLocales = android.os.LocaleList.forLanguageTags("")
            }
        } catch (_: Exception) {}
    }

    /** Goi sau khi user doi trong Cai dat: ap dung + ve lai man hinh (can cho < API 33). */
    fun applyAndRecreate(activity: Activity?, lang: String) {
        activity?.let { apply(it, lang) }
        try { activity?.recreate() } catch (_: Exception) {}
    }
}
