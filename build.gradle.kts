plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ganyansosyal.fotoproje"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ganyansosyal.fotoproje"
        minSdk = 24
        targetSdk = 34
        versionCode = 30
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Mağaza dışı kurulum için hata ayıklama anahtarıyla imzalanır (eski Flutter derlemesi gibi)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // NewPipeExtractor için
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    // YouTube çıkarımı (eski youtube_explode_dart yerine)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // MP3 kodlama (LAME): Mp3.kt
    implementation("com.github.naman14:TAndroidLame:0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
