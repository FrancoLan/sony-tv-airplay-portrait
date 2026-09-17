plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.frank.airplayguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.frank.airplayguard"
        minSdk = 22          // Android 5.1 — covers every Android TV Sony ever shipped
        targetSdk = 33       // 33 keeps us out of Android 14's stricter FGS type enforcement
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed so `./gradlew assembleRelease` produces a directly sideloadable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
