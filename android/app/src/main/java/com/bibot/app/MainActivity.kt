package com.bibot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import com.bibot.app.ui.menu.BibotMainScreen
import com.bibot.app.ui.theme.BibotTheme

class MainActivity : ComponentActivity() {
    // May < API 33 khong co per-app locales: boc locale tu pref dong bo truoc khi tao UI
    override fun attachBaseContext(newBase: android.content.Context) {
        var ctx = newBase
        try {
            val lang = com.bibot.app.core.locale.AppLocale.savedLang(newBase)
            if (lang != com.bibot.app.core.locale.AppLocale.SYSTEM &&
                android.os.Build.VERSION.SDK_INT < 33
            ) {
                val config = android.content.res.Configuration(newBase.resources.configuration)
                config.setLocale(java.util.Locale(lang))
                ctx = newBase.createConfigurationContext(config)
            }
        } catch (_: Exception) {}
        super.attachBaseContext(ctx)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Chuẩn storage (nhanh, sync) — cleanup nặng đã chuyển sang BibotApp 2.1.2
        try {
            com.bibot.app.data.files.FilePaths.ensureAll(this)
        } catch (_: Exception) {}
        // Init Go proxy for free users without key (2.6)
        try {
            val proxy = com.bibot.app.BuildConfig.BIBOT_LLM_PROXY_URL
            if (proxy.isNotBlank()) Class.forName("mobile.Mobile").getMethod("InitProxy", String::class.java).invoke(null, proxy)
        } catch (_: Exception) {}
        // Seed demo books async — fix deprecated launchWhenCreated → repeatOnLifecycle 2.1.2
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    com.bibot.app.data.books.BookRepository(this@MainActivity).seedDemo()
                } catch (_: Exception) {}
            }
        }
        enableEdgeToEdge()
        setContent {
            BibotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BibotMainScreen()
                }
            }
        }
    }
}

