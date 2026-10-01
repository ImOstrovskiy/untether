plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.imostrovskiy.untether"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "io.github.imostrovskiy.untether"
        minSdk = 31
        targetSdk = 37
        // CI passes -PversionName=1.2.3 -PversionCode=<run number>.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.2.0"
    }
    // Release signing comes from the environment (CI secrets); nothing secret lives in the repo.
    val keystore = System.getenv("UNTETHER_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("UNTETHER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("UNTETHER_KEY_ALIAS")
            keyPassword = System.getenv("UNTETHER_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    // No Google-encrypted dependency list in the APK: it is opaque to everyone else (F-Droid rejects it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
