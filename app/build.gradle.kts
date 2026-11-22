plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.trainingwraps.puente"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.trainingwraps.puente"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "1.2"
    }

    // Firma fija: las próximas versiones se instalan encima sin desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("firma.jks")
            storePassword = "puente123"
            keyAlias = "puente"
            keyPassword = "puente123"
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
