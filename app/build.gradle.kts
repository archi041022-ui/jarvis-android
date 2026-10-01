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
        versionCode = 3
        versionName = "1.2"

        ndk {
            // Только 64-битные ARM-процессоры (Samsung A37 и все современные телефоны) — APK меньше
            abiFilters += "arm64-v8a"
        }
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

dependencies {
    // Офлайн-синтез речи sherpa-onnx (голос Piper «Руслан»). Файл скачивается при сборке на GitHub.
    implementation(files("libs/sherpa-onnx.aar"))
}
