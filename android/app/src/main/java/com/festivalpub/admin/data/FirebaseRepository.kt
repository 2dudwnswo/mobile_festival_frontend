package com.festivalpub.admin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Date

/** 스낵바로 그대로 보여 줄 한국어 메시지를 담은 실패 */
class ActionException(message: String) : Exception(message)

enum class AuthState { CHECKING, SIGNED_OUT, SIGNED_IN }

/**
 * Firebase(Firestore + Auth) 데이터 계층. 노트북 서버의 REST + WebSocket 을 대체한다.
 *
 * - 읽기: 컬렉션 리스너들의 결과를 지금과 같은 [Snapshot] 하나로 합쳐 [snapshot] 으로 내보낸다.
 *   화면은 언제나 이 스냅샷에서 그린다 (쓰기 결과로 로컬 상태를 직접 고치지 않는 원칙 유지).
 * - 쓰기: 여러 문서를 조건부로 바꾸는 작업(착석·종료·VIP 등록·직원 주문)은 트랜잭션 → 오프라인이면 실패.
 *   단순 상태 변경(호출·무응답·복귀·취소·연장·조리완료·설정)은 update → 오프라인이면 쓰기 큐에 쌓였다가 전송.
 *   허용되는 상태 전이는 보안 규칙(firebase/firestore.rules)이 서버에서 검사한다.
 * - 경로·필드 이름은 FirestoreMapper.kt 의 Fs / FirestoreMapper 에 모여 있다.
 */
class FirebaseRepository(
    context: Context,
    /** Firebase 에뮬레이터에 붙을 때는 폰의 네트워크 상태와 무관하므로 네트워크 확인을 끈다 */
    private val checkNetwork: Boolean = true,
) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _authState = MutableStateFlow(AuthState.CHECKING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** 폰에 (검증된) 인터넷이 있는지. 끊기면 즉시 false */
    private val _network = MutableStateFlow(true)
    val network: StateFlow<Boolean> = _network.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** 모든 리스너가 서버와 동기화된 상태인지 (하나라도 캐시에서만 온 데이터면 false) */
    private val _synced = MutableStateFlow(false)
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    /** 아직 서버로 전송되지 않은 쓰기가 반영된 문서 수 (오프라인 쓰기 큐) */
    private val _pendingWrites = MutableStateFlow(0)
    val pendingWrites: StateFlow<Int> = _pendingWrites.asStateFlow()

    /** 리스너 오류 등 화면에 알려야 할 메시지 */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** 서버 시각 − 기기 시각 (ms). 모든 타이머는 기기 시각 + 이 값으로 계산한다 */
    @Volatile
    var clockOffset: Long = 0L
        private set

    // ---- 리스너 상태 (모든 콜백은 메인 스레드) ----
    private val registrations = mutableListOf<ListenerRegistration>()
    private var settings: Settings? = null
    private var staff: List<String> = emptyList()
    private var menu: List<MenuItem> = emptyList()
    private var tables: List<TableInfo>? = null
    private var waitings: List<Waiting> = emptyList()
    private var vipWaitings: List<Waiting> = emptyList()
    private var orders: List<Order> = emptyList()
    private val fromCache = mutableMapOf<String, Boolean>()
    private val pending = mutableMapOf<String, Int>()
    private var clockJob: Job? = null

    private val authListener = FirebaseAuth.AuthStateListener { a ->
        val user = a.currentUser
        if (user != null && user.email == Fs.STAFF_EMAIL) {
            _authState.value = AuthState.SIGNED_IN
            startListening()
        } else {
            _authState.value = AuthState.SIGNED_OUT
            stopListening()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _network.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        override fun onLost(network: Network) {
            _network.value = false
        }
    }

    init {
        if (checkNetwork) {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            _network.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            runCatching { cm?.registerDefaultNetworkCallback(networkCallback) }
        }
        auth.addAuthStateListener(authListener)
    }

    // ================= 인증 (스태프 공용 계정) =================

    suspend fun signIn(password: String) {
        try {
            auth.signInWithEmailAndPassword(Fs.STAFF_EMAIL, password).await()
        } catch (e: Exception) {
            throw ActionException(
                when (e) {
                    is FirebaseAuthInvalidCredentialsException -> "비밀번호가 맞지 않습니다"
                    is FirebaseAuthInvalidUserException -> "스태프 계정이 없습니다. README 의 Firebase 설정을 확인하세요"
                    is FirebaseNetworkException -> "인터넷 연결을 확인하세요"
                    is FirebaseTooManyRequestsException -> "시도가 너무 많습니다. 잠시 후 다시 시도하세요"
                    else -> "로그인 실패: ${e.message}"
                },
            )
        }
    }

    fun signOut() = auth.signOut()

    // ================= 읽기: 리스너 → Snapshot =================

    private fun startListening() {
        if (registrations.isNotEmpty()) return
        val config = db.collection(Fs.CONFIG)
        listenDoc("settings", config.document(Fs.SETTINGS_DOC)) { settings = FirestoreMapper.settings(it) }
        listenDoc("staff", config.document(Fs.STAFF_DOC)) { staff = FirestoreMapper.staff(it) }
        listenQuery("menu", db.collection(Fs.MENU)) { docs ->
            menu = docs.mapNotNull { (id, d) -> FirestoreMapper.menuItem(id, d) }.sortedBy { it.id }
        }
        listenQuery("tables", db.collection(Fs.TABLES)) { docs ->
            tables = docs.mapNotNull { (id, d) -> FirestoreMapper.table(id, d) }
        }
        listenQuery("waitings", db.collection(Fs.WAITINGS).whereIn("status", Fs.ACTIVE_WAITING)) { docs ->
            waitings = docs.mapNotNull { (id, d) -> FirestoreMapper.waiting(id, d, isVip = false) }
        }
        listenQuery("vipWaitings", db.collection(Fs.VIP_WAITINGS).whereIn("status", Fs.ACTIVE_WAITING)) { docs ->
            vipWaitings = docs.mapNotNull { (id, d) -> FirestoreMapper.waiting(id, d, isVip = true) }
        }
        // 오늘(한국 시간 0시 이후)의 PAID 주문만. 보안 규칙도 PAID 만 읽게 한다.
        val since = Timestamp(Date(FirestoreMapper.todayStartKst(System.currentTimeMillis() + clockOffset)))
        listenQuery(
            "orders",
            db.collection(Fs.ORDERS).whereEqualTo("paymentStatus", "PAID").whereGreaterThanOrEqualTo("createdAt", since),
        ) { docs -> orders = docs.mapNotNull { (id, d) -> FirestoreMapper.order(id, d) } }

        clockJob = scope.launch {
            // 연결될 때마다 + 10분마다 서버 시각과 맞춘다
            var wasSynced = false
            launch {
                synced.collect { now ->
                    if (now && !wasSynced) syncClock()
                    wasSynced = now
                }
            }
            while (isActive) {
                delay(10 * 60_000L)
                if (synced.value) syncClock()
            }
        }
    }

    private fun stopListening() {
        registrations.forEach { it.remove() }
        registrations.clear()
        clockJob?.cancel()
        settings = null
        tables = null
        staff = emptyList()
        menu = emptyList()
        waitings = emptyList()
        vipWaitings = emptyList()
        orders = emptyList()
        fromCache.clear()
        pending.clear()
        _snapshot.value = null
        _synced.value = false
        _pendingWrites.value = 0
    }

    private fun listenDoc(key: String, ref: DocumentReference, apply: (Map<String, Any?>?) -> Unit) {
        registrations += ref.addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) return@addSnapshotListener onListenError(key, err)
            snap ?: return@addSnapshotListener
            fromCache[key] = snap.metadata.isFromCache
            pending[key] = if (snap.metadata.hasPendingWrites()) 1 else 0
            apply(snap.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE))
            publish()
        }
    }

    private fun listenQuery(key: String, query: Query, apply: (List<Pair<String, Map<String, Any?>>>) -> Unit) {
        registrations += query.addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) return@addSnapshotListener onListenError(key, err)
            snap ?: return@addSnapshotListener
            fromCache[key] = snap.metadata.isFromCache
            pending[key] = snap.documents.count { it.metadata.hasPendingWrites() }
            apply(
                snap.documents.mapNotNull { doc ->
                    doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.let { doc.id to it }
                },
            )
            publish()
        }
    }

    private fun onListenError(key: String, e: FirebaseFirestoreException) {
        _errors.tryEmit(
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "데이터를 읽을 권한이 없습니다 ($key). 스태프 계정·보안 규칙을 확인하세요"
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "Firestore 색인이 필요합니다 ($key). README 의 색인 배포를 확인하세요"
                else -> "데이터를 불러오지 못했습니다 ($key): ${e.message}"
            },
        )
    }

    private fun publish() {
        val s = settings ?: return
        val t = tables ?: return
        _snapshot.value = Snapshot(
            settings = s,
            tables = FirestoreMapper.tablesForLayout(t, s),
            waitings = waitings + vipWaitings,
            orders = orders,
            menu = menu,
            staff = staff,
        )
        _synced.value = fromCache.size == registrations.size && fromCache.values.none { it }
        _pendingWrites.value = pending.values.sum()
    }

    /**
     * 기기 시계 보정: clocks/{uid} 에 serverTimestamp 를 쓰고, 서버가 기록한 시각을
     * 요청~응답 중간 시점과 비교한다. 오차는 왕복 시간의 절반 정도 (1초 단위 타이머에 충분).
     */
    private suspend fun syncClock() {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection(Fs.CLOCKS).document(uid)
        runCatching {
            val t0 = System.currentTimeMillis()
            withTimeout(15_000) { ref.set(mapOf("at" to FieldValue.serverTimestamp())).await() }
            val t1 = System.currentTimeMillis()
            val at = FirestoreMapper.millis(withTimeout(15_000) { ref.get(Source.SERVER).await() }.get("at"))
            if (at != null) clockOffset = at - (t0 + t1) / 2
        }
    }

    // ================= 쓰기 =================

    private fun tableRef(no: Int) = db.collection(Fs.TABLES).document(no.toString())
    private fun waitingRef(w: Waiting) =
        db.collection(if (w.isVip) Fs.VIP_WAITINGS else Fs.WAITINGS).document(w.id.toString())
    private fun orderRef(id: Int) = db.collection(Fs.ORDERS).document(id.toString())
    private fun counterRef(name: String) = db.collection(Fs.COUNTERS).document(name)

    /** 착석: 테이블 EMPTY → OCCUPIED 와 웨이팅 → SEATED 를 한 트랜잭션으로. 같은 테이블 동시 착석은 하나만 성공 */
    suspend fun seat(staff: String, tableNo: Int, waiting: Waiting?) = transaction("착석을") { tx ->
        val tRef = tableRef(tableNo)
        val t = tx.get(tRef)
        if (!t.exists()) throw ActionException("${tableNo}번 테이블 정보가 서버에 없습니다")
        if (t.getString("status") == "OCCUPIED") throw ActionException("${tableNo}번 테이블은 이미 이용 중입니다")
        var partySize: Int? = null
        var phone: String? = null
        if (waiting != null) {
            val wRef = waitingRef(waiting)
            val w = tx.get(wRef)
            if (!w.exists() || w.getString("status") !in Fs.ACTIVE_WAITING) throw ActionException("이미 처리된 웨이팅입니다")
            partySize = w.getLong("partySize")?.toInt()
            phone = w.getString("phone")
            tx.update(wRef, FirestoreMapper.seatedWaitingFields(staff, tableNo))
        }
        tx.update(tRef, FirestoreMapper.seatFields(staff, partySize, phone, waiting?.id, waiting?.isVip ?: false))
    }

    /**
     * 이용 종료. 화면에서 본 착석 시각([expectedSeatedAt])과 같을 때만 비운다.
     * (다른 스태프가 이미 종료하고 새 손님을 앉혔는데 늦게 갱신된 화면에서 종료를 누르는 경우 방지)
     * 입금 전 주문은 건드리지 않는다 — 처리는 서버가 정한다.
     */
    suspend fun release(staff: String, tableNo: Int, expectedSeatedAt: Long?) = transaction("이용 종료를") { tx ->
        val ref = tableRef(tableNo)
        val t = tx.get(ref)
        if (t.getString("status") != "OCCUPIED") throw ActionException("${tableNo}번 테이블은 이미 정리되었습니다")
        val seatedAt = FirestoreMapper.millis(t.get("seatedAt"))
        if (expectedSeatedAt != null && seatedAt != expectedSeatedAt) {
            throw ActionException("${tableNo}번 테이블에 새 손님이 앉았습니다. 화면을 확인하고 다시 눌러주세요")
        }
        tx.update(ref, FirestoreMapper.releaseFields(staff))
    }

    suspend fun extend(staff: String, tableNo: Int, minutes: Int) =
        queued(tableRef(tableNo), FirestoreMapper.extendFields(staff, minutes))

    /** 호출·무응답·복귀·취소: 오프라인이어도 로컬에 바로 반영되고 연결되면 전송된다 */
    suspend fun setWaitingStatus(staff: String, w: Waiting, status: String) =
        queued(waitingRef(w), FirestoreMapper.waitingStatusFields(staff, status))

    /** VIP 등록: 앱 전용 카운터(counters/vip)로 번호를 매겨 vipWaitings 에 만든다 */
    suspend fun addVip(staff: String, phone: String, partySize: Int) {
        val dup = _snapshot.value?.waitings.orEmpty().any { it.phone == phone }
        if (dup) throw ActionException("이미 대기 중입니다")
        transaction("VIP 등록을") { tx ->
            val cRef = counterRef(Fs.COUNTER_VIP)
            val n = tx.get(cRef).getLong("next")?.toInt() ?: 1
            tx.set(cRef, mapOf("next" to n + 1))
            tx.set(db.collection(Fs.VIP_WAITINGS).document(n.toString()), FirestoreMapper.vipWaitingDoc(n, phone, partySize, staff))
        }
    }

    /** 1-5 직원 주문: PENDING 으로 생성. 착석한 테이블만, 품절 메뉴 불가 */
    suspend fun createOrder(staff: String, tableNo: Int, cart: Map<Int, Int>) = transaction("주문을") { tx ->
        val cRef = counterRef(Fs.COUNTER_ORDERS)
        val n = tx.get(cRef).getLong("next")?.toInt() ?: 1
        if (tx.get(tableRef(tableNo)).getString("status") != "OCCUPIED") {
            throw ActionException("착석 처리된 테이블만 주문할 수 있습니다")
        }
        val lines = cart.filterValues { it > 0 }.map { (menuId, qty) ->
            val m = tx.get(db.collection(Fs.MENU).document(menuId.toString())).data
                ?.let { FirestoreMapper.menuItem(menuId.toString(), it) }
                ?: throw ActionException("없는 메뉴입니다")
            if (m.soldOut) throw ActionException("${m.name}은(는) 품절입니다")
            OrderLine(menuId = m.id, name = m.name, price = m.price, qty = qty)
        }
        if (lines.isEmpty()) throw ActionException("메뉴를 선택해 주세요")
        tx.set(cRef, mapOf("next" to n + 1))
        tx.set(orderRef(n), FirestoreMapper.staffOrderDoc(n, tableNo, lines, staff))
    }

    suspend fun cooked(staff: String, orderId: Int) = queued(orderRef(orderId), FirestoreMapper.cookedFields(staff))

    suspend fun saveTimeSettings(staff: String, s: Settings) =
        queued(db.collection(Fs.CONFIG).document(Fs.SETTINGS_DOC), FirestoreMapper.timeSettingsFields(s, staff))

    // ---- 공통 ----

    private suspend fun <T> transaction(what: String, block: (Transaction) -> T): T {
        if (checkNetwork && !_network.value) throw ActionException(offlineMessage(what))
        try {
            return db.runTransaction { tx -> block(tx) }.await()
        } catch (e: Exception) {
            throw translate(e, what)
        }
    }

    private suspend fun queued(ref: DocumentReference, fields: Map<String, Any?>) {
        try {
            ref.update(fields).await()
        } catch (e: Exception) {
            throw translate(e, "변경을")
        }
    }

    private fun offlineMessage(what: String) = "인터넷 연결이 끊겨 $what 저장하지 못했습니다. 연결되면 다시 눌러주세요"

    private fun translate(e: Throwable, what: String): ActionException {
        generateSequence(e) { it.cause }.filterIsInstance<ActionException>().firstOrNull()?.let { return it }
        val fe = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        return ActionException(
            when (fe?.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE -> offlineMessage(what)
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "인터넷이 불안정해 결과를 확인하지 못했습니다. 화면을 확인하고 필요하면 다시 눌러주세요"
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "이미 다른 기기에서 처리되었거나 허용되지 않는 변경입니다"
                FirebaseFirestoreException.Code.NOT_FOUND -> "서버에 해당 항목이 없습니다"
                FirebaseFirestoreException.Code.ABORTED -> "다른 기기와 동시에 처리되어 저장하지 못했습니다. 다시 눌러주세요"
                else -> "저장하지 못했습니다: ${e.message}"
            },
        )
    }

    fun close() {
        auth.removeAuthStateListener(authListener)
        stopListening()
        if (checkNetwork) runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
    }
}
