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
        versionCode = 82
        versionName = "2.1.1"
        // The on-phone model's native library: the phone's 64-bit ARM build is all it needs.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // A fixed debug key (keys/debug.keystore) so new builds install over old ones.
        getByName("debug") {
            storeFile = rootProject.file("keys/debug.keystore")
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


    testOptions {
        // Lets tests build small Android values like ComponentName, which the stub library would refuse.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The assistant's offline back-up: Google's LiteRT-LM, running Gemma on the phone (GPU). Needs Kotlin 2.3+ (it carries 2.4 metadata).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    testImplementation("junit:junit:4.13.2")
    // Android's built-in org.json is a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}
