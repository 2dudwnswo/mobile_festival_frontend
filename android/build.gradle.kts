plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // Firebase: android/app/google-services.json 을 읽어 프로젝트 설정을 넣는다 (파일은 git 에 올리지 않음)
    id("com.google.gms.google-services") version "4.4.2" apply false
}
