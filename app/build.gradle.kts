plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ganyansosyal.fotoproje"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ganyansosyal.fotoproje"
        minSdk = 24
        targetSdk = 36
        versionCode = 32
        versionName = "1.0.0"
        // yt-dlp + ffmpeg yalnızca gerçek telefon işlemcileri için (APK boyutunu küçültür)
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    // Play imzası: ortam değişkenleri varsa kendi keystore'un, yoksa debug anahtarı
    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (!ksFile.isNullOrBlank() && file(ksFile).exists()) {
            create("play") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("play") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // yt-dlp / python / ffmpeg ikilileri diske çıkarılmalı (extractNativeLibs=true)
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // LevelPlay + AdMob adaptörü (adaptör Google'ın yeni ads-mobile-sdk kütüphanesini ister; play-services-ads ile birlikte kullanılmaz)
    implementation("com.unity3d.ads-mediation:mediation-sdk:9.5.0")
    implementation("com.unity3d.ads-mediation:admob-adapter:5.11.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.2.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
