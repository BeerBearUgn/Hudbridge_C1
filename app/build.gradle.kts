plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.gratio.hudbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.gratio.hudbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
