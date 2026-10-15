plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions에서 빌드할 때마다 버전 번호가 올라가도록
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.sawoo.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sawoo.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // 요즘 폰(64비트)용만 넣어서 용량을 줄인다
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    // 엔진 파일(.so)을 압축해서 넣어 다운로드 용량을 줄인다
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // 항상 같은 키로 서명해야 새 버전을 지우지 않고 바로 덮어 설치할 수 있다
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 글자 인식 (기기 안에서 동작)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 번역 (기기 안에서 동작, 언어 모델은 처음에 한 번 다운로드)
    implementation("com.google.mlkit:translate:17.0.3")
}
