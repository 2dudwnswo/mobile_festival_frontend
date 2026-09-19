package com.festivalpub.admin.data

import android.content.Context
import android.annotation.SuppressLint
import com.festivalpub.admin.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 초기화의 유일한 진입점. 실패 시 운영으로 재시도하지 않는다. */
object FirebaseConnection {
    /** 운영 프로젝트: release 와 운영 debug(-PfirebaseDebugProduction=true)에서만 쓴다. */
    const val PRODUCTION_PROJECT_ID = "mobokfestivalpub"
    /** 에뮬레이터 전용. demo- 로 시작하는 ID는 실제 Firebase 리소스에 절대 연결되지 않는다. */
    const val EMULATOR_PROJECT_ID = "demo-festival-pub"
    const val HOST = "127.0.0.1" // USB 실기기: adb reverse
    const val FIRESTORE_PORT = 8080
    const val AUTH_PORT = 9099
    val emulator: Boolean get() = BuildConfig.USE_FIREBASE_EMULATOR
    var initializationError: String? = null
        private set
    lateinit var auth: FirebaseAuth
        private set
    // Application Context로만 생성하는 프로세스 수명 SDK 인스턴스다. Activity를 보관하지 않는다.
    @SuppressLint("StaticFieldLeak")
    lateinit var db: FirebaseFirestore
        private set

    fun initialize(context: Context) {
        try {
            // 테스트는 실제 API 키를 사용하지 않는다. 운영과 앱 이름·세션·캐시도 분리한다.
            val options = if (emulator) FirebaseOptions.Builder()
                .setProjectId(EMULATOR_PROJECT_ID.also { check(it.startsWith("demo-")) })
                .setApplicationId("1:1234567890:android:emulatoronly")
                .setApiKey("fake-emulator-key").build()
            else FirebaseOptions.fromResource(context)?.also {
                check(it.projectId == PRODUCTION_PROJECT_ID) { "Firebase 프로젝트 설정을 확인하세요" }
            } ?: error("Firebase 설정 파일이 없습니다")
            val app = FirebaseApp.initializeApp(context, options, if (emulator) "local-emulator" else "production")
            auth = FirebaseAuth.getInstance(app)
            if (emulator) auth.useEmulator(HOST, AUTH_PORT)
            db = FirebaseFirestore.getInstance(app)
            if (emulator) {
                db.useEmulator(HOST, FIRESTORE_PORT)
                db.firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            }
        } catch (e: Exception) {
            initializationError = "Firebase 초기화 실패. 연결 설정을 확인한 뒤 앱을 다시 시작하세요. 운영 DB로 전환하지 않습니다."
        }
    }

    suspend fun emulatorReachable(): Boolean = withContext(Dispatchers.IO) {
        if (!emulator) return@withContext true
        listOf(FIRESTORE_PORT, AUTH_PORT).all { port ->
            runCatching { Socket().use { it.connect(InetSocketAddress(HOST, port), 800) }; true }.getOrDefault(false)
        }
    }
}
