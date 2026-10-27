plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eldablos.crimson"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eldablos.crimson"
        // Health Connect is built into Android 14+, so no extra library is needed.
        minSdk = 34
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"
    }

    signingConfigs {
        // Same fixed debug key as the Buds app so new builds install over old ones.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testOptions {
        // Lets tests build small Android values like ComponentName, which the stub library would refuse.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's built-in org.json is a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}
