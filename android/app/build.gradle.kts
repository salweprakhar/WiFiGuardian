plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableDebugSigning = listOf(
    "WIFIGUARDIAN_KEYSTORE_PATH",
    "WIFIGUARDIAN_STORE_PASSWORD",
    "WIFIGUARDIAN_KEY_ALIAS",
    "WIFIGUARDIAN_KEY_PASSWORD"
).associateWith { System.getenv(it) }
val hasAnyStableDebugSigningValue = stableDebugSigning.values.any { !it.isNullOrBlank() }
val hasAllStableDebugSigningValues = stableDebugSigning.values.all { !it.isNullOrBlank() }
require(!hasAnyStableDebugSigningValue || hasAllStableDebugSigningValues) {
    "Stable debug signing requires WIFIGUARDIAN_KEYSTORE_PATH, WIFIGUARDIAN_STORE_PASSWORD, " +
        "WIFIGUARDIAN_KEY_ALIAS, and WIFIGUARDIAN_KEY_PASSWORD."
}

android {
    namespace = "com.example.wifiguardian"
    compileSdk = 35

    if (hasAllStableDebugSigningValues) {
        signingConfigs {
            create("stableDebug") {
                storeFile = file(stableDebugSigning.getValue("WIFIGUARDIAN_KEYSTORE_PATH")!!)
                storePassword = stableDebugSigning.getValue("WIFIGUARDIAN_STORE_PASSWORD")
                keyAlias = stableDebugSigning.getValue("WIFIGUARDIAN_KEY_ALIAS")
                keyPassword = stableDebugSigning.getValue("WIFIGUARDIAN_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.wifiguardian"
        minSdk = 26
        targetSdk = 28
        versionCode = 8
        versionName = "0.8-built-in-terminal"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("debug") {
            if (hasAllStableDebugSigningValues) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
    }
}
