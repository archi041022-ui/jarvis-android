plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Постоянный ключ подписи: обновления ставятся поверх старой версии без удаления
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis2026"
            keyAlias = "jarvis"
            keyPassword = "jarvis2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("jarvis")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
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
