package com.festivalpub.admin.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 스펙 v3 웨이팅 시나리오를 **앱의 FirebaseRepository 코드 그대로** Firebase 에뮬레이터에서 검증한다.
 *
 * 실행 조건: debug(에뮬레이터 모드) 빌드, `npm run emulators` 실행 중, 폰에 adb reverse tcp:8080/9099.
 * 운영 DB 보호: 에뮬레이터 모드 + demo- 프로젝트가 아니면 @Before 에서 즉시 실패한다.
 * 전화번호는 010-0000-00xx 가짜 번호만 쓴다.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryV3EmulatorTest {
    private val db get() = FirebaseConnection.db
    private lateinit var repo: FirebaseRepository
    private val now = System.currentTimeMillis()

    @Before fun setUp() = runBlocking(Dispatchers.IO) {
        check(FirebaseConnection.emulator) { "에뮬레이터 모드 debug 빌드에서만 실행한다 (운영 DB 보호)" }
        check(db.app.options.projectId?.startsWith("demo-") == true) { "demo- 프로젝트가 아니면 실행하지 않는다" }
        val auth = FirebaseConnection.auth
        runCatching { auth.signInWithEmailAndPassword(EMAIL, PASSWORD).await() }
            .onFailure { auth.createUserWithEmailAndPassword(EMAIL, PASSWORD).await() }
        // 이 테스트가 쓰는 문서만 초기화한다
        for (col in listOf(Fs.WAITING_PRIVATE, Fs.WAITING_PUBLIC)) {
            db.collection(col).get(Source.SERVER).await().documents.forEach { it.reference.delete().await() }
        }
        for (no in listOf(5, 6)) db.collection(Fs.TABLES).document("$no").set(emptyTable(no)).await()
        repo = FirebaseRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() { runBlocking(Dispatchers.Main) { repo.close() } }

    // ---------------- 도우미 ----------------

    private fun emptyTable(no: Int) = mapOf(Fs.TABLE_NO to no.toLong(), Fs.STATUS to "EMPTY", Fs.START_TIME to null,
        Fs.PAYMENT_CONFIRMED to false, Fs.EXTENDED_MINUTES to 0L, Fs.TOTAL_AMOUNT to 0L)

    /** private + public 짝을 만든다. [publicId] = null 이면 public_id 없는 private 만, [createPublic] = false 면 public 문서 없음 */
    private suspend fun pair(phone: String, status: String, publicId: String? = "pub-$phone", createPublic: Boolean = true,
                             vip: Boolean = false, calledAt: Long? = null) {
        val created = now - 10 * 60_000
        val p = mutableMapOf<String, Any?>(Fs.PHONE to phone, Fs.PARTY_SIZE to 2L, Fs.IS_VIP to vip, Fs.STATUS to status,
            Fs.CALLED_AT to calledAt, Fs.CREATED_AT to created)
        if (publicId != null) p[Fs.PUBLIC_ID] = publicId
        db.collection(Fs.WAITING_PRIVATE).document(phone).set(p).await()
        if (publicId != null && createPublic) db.collection(Fs.WAITING_PUBLIC).document(publicId)
            .set(mapOf(Fs.IS_VIP to vip, Fs.STATUS to status, Fs.CREATED_AT to created)).await()
    }
    private suspend fun private(phone: String) = db.collection(Fs.WAITING_PRIVATE).document(phone).get(Source.SERVER).await()
    private suspend fun public(id: String) = db.collection(Fs.WAITING_PUBLIC).document(id).get(Source.SERVER).await()
    private suspend fun table(no: Int) = db.collection(Fs.TABLES).document("$no").get(Source.SERVER).await()
    private suspend fun waiting(phone: String): Waiting = FirestoreMapper.waiting(phone, private(phone).data!!)!!
    private suspend fun expectAction(block: suspend () -> Unit): String {
        try { block() } catch (e: ActionException) { return e.message.orEmpty() }
        throw AssertionError("ActionException 이 나야 한다")
    }

    // ---------------- VIP 등록 ----------------

    @Test fun vip_신규_등록은_private_public_짝을_만든다() = runBlocking(Dispatchers.IO) {
        repo.addVip("010-0000-0011", 3)
        val p = private("01000000011")
        assertEquals(true, p.getBoolean(Fs.IS_VIP)); assertEquals("WAITING", p.getString(Fs.STATUS))
        assertEquals(3L, p.getLong(Fs.PARTY_SIZE)); assertNull(p.get(Fs.CALLED_AT))
        val pub = public(p.getString(Fs.PUBLIC_ID)!!)
        assertTrue(pub.exists()); assertEquals(true, pub.getBoolean(Fs.IS_VIP)); assertEquals("WAITING", pub.getString(Fs.STATUS))
        assertEquals(p.getLong(Fs.CREATED_AT), pub.getLong(Fs.CREATED_AT)); assertFalse(pub.contains(Fs.PHONE))
    }

    @Test fun vip_기존_WAITING_NO_SHOW_번호는_차단하고_짝을_건드리지_않는다() = runBlocking(Dispatchers.IO) {
        pair("01000000012", "WAITING"); pair("01000000013", "NO_SHOW")
        for (phone in listOf("01000000012", "01000000013")) {
            assertEquals("이미 대기 중인 번호입니다", expectAction { repo.addVip(phone, 2) })
            val p = private(phone)
            assertEquals(false, p.getBoolean(Fs.IS_VIP)); assertEquals("pub-$phone", p.getString(Fs.PUBLIC_ID))
        }
        assertEquals(2, db.collection(Fs.WAITING_PUBLIC).get(Source.SERVER).await().size())
    }

    @Test fun vip_CANCELLED_번호는_덮어쓰고_새_public_을_만든다() = runBlocking(Dispatchers.IO) {
        pair("01000000014", "CANCELLED")
        repo.addVip("01000000014", 4)
        val p = private("01000000014")
        assertEquals(true, p.getBoolean(Fs.IS_VIP)); assertEquals("WAITING", p.getString(Fs.STATUS))
        val newId = p.getString(Fs.PUBLIC_ID)!!
        assertNotEquals("pub-01000000014", newId)
        assertEquals("WAITING", public(newId).getString(Fs.STATUS))
        assertEquals("CANCELLED", public("pub-01000000014").getString(Fs.STATUS)) // 예전 사본은 그대로
    }

    // ---------------- 상태 변경 (private + public 동시) ----------------

    @Test fun 무응답_복귀_취소는_private_public_둘_다_바꾸고_등록시각은_유지() = runBlocking(Dispatchers.IO) {
        pair("01000000015", "WAITING", calledAt = now - 4 * 60_000)
        val created = private("01000000015").getLong(Fs.CREATED_AT)
        for (status in listOf("NO_SHOW", "WAITING", "CANCELLED")) {
            repo.setWaitingStatus(waiting("01000000015"), status)
            assertEquals(status, private("01000000015").getString(Fs.STATUS))
            assertEquals(status, public("pub-01000000015").getString(Fs.STATUS))
            assertEquals(created, private("01000000015").getLong(Fs.CREATED_AT))
        }
    }

    @Test fun 호출은_private_의_called_at_만() = runBlocking(Dispatchers.IO) {
        pair("01000000016", "WAITING")
        repo.callWaiting(waiting("01000000016"))
        assertNotNull(private("01000000016").getLong(Fs.CALLED_AT))
        assertFalse(public("pub-01000000016").contains(Fs.CALLED_AT))
    }

    // ---------------- 착석 트랜잭션 ----------------

    @Test fun 착석은_테이블_private_public_세_문서를_같이_바꾼다() = runBlocking(Dispatchers.IO) {
        pair("01000000017", "WAITING")
        repo.seat(5, waiting("01000000017"))
        val t = table(5)
        assertEquals("SEATED_PENDING_PAYMENT", t.getString(Fs.STATUS)); assertNotNull(t.getLong(Fs.START_TIME))
        assertEquals("SEATED", private("01000000017").getString(Fs.STATUS))
        assertEquals("SEATED", public("pub-01000000017").getString(Fs.STATUS))
    }

    @Test fun 같은_테이블_동시_착석은_하나만_성공() = runBlocking(Dispatchers.IO) {
        pair("01000000018", "WAITING"); pair("01000000019", "NO_SHOW")
        val a = waiting("01000000018"); val b = waiting("01000000019")
        val results = listOf(async { runCatching { repo.seat(6, a) } }, async { runCatching { repo.seat(6, b) } }).map { it.await() }
        assertEquals(1, results.count { it.isSuccess })
        val seated = listOf("01000000018", "01000000019").count { private(it).getString(Fs.STATUS) == "SEATED" }
        assertEquals(1, seated)
        assertEquals("SEATED_PENDING_PAYMENT", table(6).getString(Fs.STATUS))
    }

    // ---------------- 짝이 깨진 문서 ----------------

    @Test fun public_id_없는_문서도_목록에_나오고_상태변경_착석이_멈추지_않는다() = runBlocking(Dispatchers.IO) {
        pair("01000000020", "WAITING", publicId = null)                    // public_id 없음
        pair("01000000021", "WAITING", publicId = "gone", createPublic = false) // public 문서 없음
        pair("01000000022", "WAITING", publicId = "gone2", createPublic = false)
        // 목록(리스너)에 나온다
        val expected = listOf("01000000020", "01000000021", "01000000022")
        var listed = emptyList<String>()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            listed = repo.snapshot.value?.waitings.orEmpty().map { it.key }
            if (listed.containsAll(expected)) break
            Thread.sleep(100)
        }
        assertTrue("목록: $listed", listed.containsAll(expected))
        // 상태 변경: private 만 바뀐다
        repo.setWaitingStatus(waiting("01000000020"), "CANCELLED")
        assertEquals("CANCELLED", private("01000000020").getString(Fs.STATUS))
        repo.setWaitingStatus(waiting("01000000021"), "CANCELLED")
        assertEquals("CANCELLED", private("01000000021").getString(Fs.STATUS))
        assertFalse(public("gone").exists()) // 없는 짝을 새로 만들지 않는다
        // 착석: 테이블 + private 만
        repo.seat(5, waiting("01000000022"))
        assertEquals("SEATED", private("01000000022").getString(Fs.STATUS))
        assertEquals("SEATED_PENDING_PAYMENT", table(5).getString(Fs.STATUS))
        assertFalse(public("gone2").exists())
    }

    companion object {
        // 에뮬레이터 전용 가짜 계정 (firebase/scripts/seed.mjs 와 같음). 운영 계정 아님.
        private const val EMAIL = "staff@example.test"
        private const val PASSWORD = "emulator-only-1234"
    }
}
