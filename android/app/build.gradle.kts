import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
}

// Secret ky ma VIP (HMAC): uu tien local.properties (may dev, KHONG commit) -> env BIBOT_VIP_SECRET (CI) -> rong.
val vipHmacSecret: String = run {
    val fromLocal = try {
        val lp = rootProject.file("local.properties")
        if (lp.exists()) {
            val props = Properties()
            lp.inputStream().use { props.load(it) }
            (props.getProperty("bibot.vip.secret") ?: "").trim()
        } else ""
    } catch (_: Exception) { "" }
    val secret = if (fromLocal.isNotEmpty()) fromLocal else (System.getenv("BIBOT_VIP_SECRET") ?: "").trim()
    if (secret.isNotEmpty() && (secret.length < 32 || secret.length % 2 != 0)) {
        logger.warn("[Bibot] bibot.vip.secret/BIBOT_VIP_SECRET qua ngan hoac khong phai HEX chan — VIP verify se bao loi. Tao moi: python tools/vipcodes/vip_batch.py --gen-secret")
        ""
    } else secret
}

// Firebase (FirestoreSync thu cong, khong google-services plugin): uu tien
// local.properties (bibot.firebase.projectId/appId/apiKey, KHONG commit) -> env
// FIREBASE_PROJECT_ID/FIREBASE_APP_ID/FIREBASE_API_KEY (CI) -> rong (=offline).
fun fbProp(name: String): String = run {
    val fromLocal = try {
        val lp = rootProject.file("local.properties")
        if (lp.exists()) {
            val props = Properties()
            lp.inputStream().use { props.load(it) }
            (props.getProperty(name) ?: "").trim()
        } else ""
    } catch (_: Exception) { "" }
    if (fromLocal.isNotEmpty()) fromLocal else when (name) {
        "bibot.firebase.projectId" -> (System.getenv("FIREBASE_PROJECT_ID") ?: "").trim()
        "bibot.firebase.appId" -> (System.getenv("FIREBASE_APP_ID") ?: "").trim()
        "bibot.firebase.apiKey" -> (System.getenv("FIREBASE_API_KEY") ?: "").trim()
        else -> ""
    }
}
val fbProjectId = fbProp("bibot.firebase.projectId")
val fbAppId = fbProp("bibot.firebase.appId")
val fbApiKey = fbProp("bibot.firebase.apiKey")

android {
    namespace = "com.bibot.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.bibot.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BIBOT_PERSONA", "\"Em là Bi\"")
        // I2 staging: BIBOT_RELAY_URL (env, không commit) trỏ relay staging/PageFreeze;
        // trống = dùng dev fallback local (debug) / NeedOnline (release).
        buildConfigField("String", "BIBOT_LLM_PROXY_URL", "\"" + (System.getenv("BIBOT_RELAY_URL") ?: "") + "\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"" + fbProjectId + "\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"" + fbAppId + "\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"" + fbApiKey + "\"")
        buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-2478233272401556~3883137708\"")
        // I2: KHÔNG nướng VIP_HMAC_SECRET vào release (R8 không giấu được string) —
        // secret chỉ có ở debug (dev test offline), release để rỗng, server verify.
        buildConfigField("String", "ANNOUNCE_URL", "\"\"")
        buildConfigField("String", "AD_TEST_DEVICES", "\"" + (System.getenv("AD_TEST_DEVICES") ?: "") + "\"")
        buildConfigField("String", "DEFAULT_DRIVE_FOLDER_ID", "\"1UfFOF2FQbA5L9MKJtevEEGja1K0dAhxm\"")

        resConfigs("vi", "en")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("demo") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_DRIVE", "false")
            buildConfigField("boolean", "ENABLE_VOICE", "true")
            buildConfigField("boolean", "ENABLE_ADS", "true")
        }
        create("commercial") {
            dimension = "distribution"
            applicationIdSuffix = ".vip"
            versionNameSuffix = "-vip"
            buildConfigField("boolean", "ENABLE_DRIVE", "true")
            buildConfigField("boolean", "ENABLE_VOICE", "true")
            buildConfigField("boolean", "ENABLE_ADS", "false")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../bibot-release.keystore")
            storePassword = "bibotpass"
            keyAlias = "bibot-release"
            keyPassword = "bibotpass"
            // Fix cai dat tren may that (Android 12+ chep file): bat du v1+v2+v3.
            // Ban R8 truoc chi co v2 (thieu META-INF/*.SF) nen mot so trinh cai bao loi chung chung.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("testRelease") {
            storeFile = file("../bibot-test.keystore")
            storePassword = "bibottest"
            keyAlias = "bibot-test"
            keyPassword = "bibottest"
        }
    }
    buildTypes {
        debug {
            // Dev test offline mã VIP local (VipCode.verify). Release luôn rỗng.
            buildConfigField("String", "VIP_HMAC_SECRET", "\"" + vipHmacSecret + "\"")
        }
        release {
            // I2: release KHÔNG nướng secret — server verify duy nhất.
            buildConfigField("String", "VIP_HMAC_SECRET", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sinh file native-debug-symbols.zip kem AAB de Play giai ma crash/ANR native
            // (sherpa/vosk/onnxruntime). Khong lam tang dung luong app.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }

        bundle {
            language {
                enableSplit = true
            }
            density {
                enableSplit = true
            }
            abi {
                enableSplit = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    // Copy AAB ra ten chuan gui Play (chay sau bundle): demo -> Bibot.aab, commercial -> BibotV.aab.
    // Lenh: ./gradlew :app:renameReleaseBundles
    tasks.register("renameReleaseBundles") {
        dependsOn("bundleDemoRelease", "bundleCommercialRelease")
        doLast {
            val pairs = listOf("demoRelease" to "Bibot.aab", "commercialRelease" to "BibotV.aab")
            for ((variant, target) in pairs) {
                val dir = layout.buildDirectory.dir("outputs/bundle/$variant").get().asFile
                val targetFile = File(dir, target)
                val src = dir.listFiles { f -> f.extension == "aab" && f.name != target }
                    ?.maxByOrNull { it.lastModified() }
                if (src == null) {
                    logger.lifecycle("[Bibot] $variant: giu nguyen $target (khong co file doi ten)")
                    continue
                }
                if (targetFile.exists() && targetFile.lastModified() >= src.lastModified()) continue
                src.copyTo(targetFile, overwrite = true)
                logger.lifecycle("[Bibot] $variant -> $target (${targetFile.length() / 1024 / 1024}MB)")
            }
        }
    }

    // Rename output APK debug theo flavor: demo → Bibot.apk, commercial → BibotV.apk
    applicationVariants.all {
        val variantName = flavorName
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (outputImpl != null) {
                outputImpl.outputFileName =
                    if (variantName == "demo") "Bibot.apk" else "BibotV.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.emoji2)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.vosk.android)

    "commercialImplementation"(libs.google.drive)
    "commercialImplementation"(libs.google.api.client)
    implementation(libs.play.services.auth)

    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
    implementation(libs.commons.compress)
    implementation(libs.admob)

    testImplementation("junit:junit:4.13.2")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    debugImplementation(libs.androidx.ui.tooling)
}