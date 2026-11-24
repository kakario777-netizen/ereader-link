plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kakario.ereaderlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kakario.ereaderlink"
        minSdk = 26
        targetSdk = 35
        // GitHub 자동 빌드 번호를 버전으로 사용 (새 APK가 기존 앱 위에 덮어 설치되도록)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // 서명 키는 GitHub Secrets에서 받음 (빌드할 때만 임시 파일로 풀림) — 키는 바꾸지 않고 같은 키 사용
        getByName("debug") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            storeFile = if (!ks.isNullOrEmpty()) file(ks) else file("debug.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "androiddebugkey"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 아주 작은 HTTP 서버 (PC가 파일 목록·전송에 사용)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
