plugins {
    id("com.android.application")
}

// GitHub Actions에서 빌드할 때마다 버전 번호가 올라가도록
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.sawoo.gametranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sawoo.gametranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // 항상 같은 키로 서명해야 새 버전을 지우지 않고 덮어 설치할 수 있다
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 기기 안에서 동작하는 무료 번역
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    // 번역된 APK 서명
    implementation("com.android.tools.build:apksig:8.7.3")
}
