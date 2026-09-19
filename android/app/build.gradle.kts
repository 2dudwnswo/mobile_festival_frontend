plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// 운영 디버그는 매 빌드에 -PfirebaseDebugProduction=true를 명시해야 한다.
val debugProduction = providers.gradleProperty("firebaseDebugProduction").orNull.let {
    require(it == null || it == "true" || it == "false") { "firebaseDebugProduction은 true/false만 허용합니다" }
    it == "true"
}

android {
    namespace = "com.festivalpub.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.festivalpub.admin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 앱 Repository 를 Firebase 에뮬레이터에 붙여 검증하는 계측 테스트 (debug 에뮬레이터 모드 전용)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", (!debugProduction).toString())
        }
        release {
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "false")
            isMinifyEnabled = false
            // 행사용 내부 배포: 디버그 키로 서명해서 APK를 바로 설치할 수 있게 함
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Firebase Task 를 코루틴에서 await
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Firebase: 버전은 BoM 하나로 관리 (Firestore 데이터 + 스태프 공용 계정 로그인)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // data/Logic.kt 순수 함수 단위 테스트 (./gradlew test). APK 에는 포함되지 않음
    testImplementation("junit:junit:4.13.2")
    // 에뮬레이터 시나리오 계측 테스트 (connectedDebugAndroidTest). APK 에는 포함되지 않음
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
