
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
        versionCode = 1
        versionName = "0.1"
    }

    // Java 17 compatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 17 compatibility
    kotlinOptions {
        jvmTarget = "17"
    }
}
