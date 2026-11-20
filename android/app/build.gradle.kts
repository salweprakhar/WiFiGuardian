plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wifiguardian"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wifiguardian"
        minSdk = 26
        targetSdk = 28
        versionCode = 6
        versionName = "0.6-consent-proxy"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
