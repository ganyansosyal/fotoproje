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
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
