plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eldablos.crimsonicons"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eldablos.crimsonicons"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "3.0"
    }

    signingConfigs {
        // Same fixed debug key as the other apps so new builds install over old ones.
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

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
