package com.festivalpub.admin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ActionException(message: String) : Exception(message)
enum class AuthState { CHECKING, SIGNED_OUT, SIGNED_IN }

/** 동현 스펙 v3. 로그인 후에만 구독하며 실제 데이터 쓰기는 화면의 액션에서만 실행한다. */
class FirebaseRepository(context: Context, private val checkNetwork: Boolean = !FirebaseConnection.emulator) {
    private val auth = FirebaseConnection.auth
    private val db = FirebaseConnection.db
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _emulatorError = MutableStateFlow<String?>(null)
    val emulatorError = _emulatorError.asStateFlow()
    private val _authState = MutableStateFlow(AuthState.CHECKING)
    val authState = _authState.asStateFlow()
    private val _network = MutableStateFlow(true)
    val network = _network.asStateFlow()
    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot = _snapshot.asStateFlow()
    private val _synced = MutableStateFlow(false)
    val synced = _synced.asStateFlow()
    private val _pendingWrites = MutableStateFlow(0)
    val pendingWrites = _pendingWrites.asStateFlow()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()
    /** 권한이 거부되어 읽지 못하는 구독 (구독 키 → 화면에 보여 줄 문구). 로그아웃하지 않고 상단에 표시한다. */
    private val _blocked = MutableStateFlow<Map<String, String>>(emptyMap())
    val blocked = _blocked.asStateFlow()
    private fun collectionLabel(key: String) = when (key) {
        "tables" -> "테이블(${Fs.TABLES})"
        "menu" -> "메뉴(${Fs.MENU})"
        "waiting" -> "웨이팅 목록(${Fs.WAITING_PRIVATE})"
        "kitchen" -> "주방 주문(${Fs.ORDERS})"
        "history" -> "주문 내역(${Fs.ORDERS})"
        else -> key
    }
    private val registrations = mutableMapOf<String, ListenerRegistration>()
    private val fromCache = mutableMapOf<String, Boolean>()
    private val pending = mutableMapOf<String, Set<String>>()
    private var tables: List<TableInfo>? = null
    private var waitings = emptyList<Waiting>()
    private var menu = emptyList<MenuItem>()
    private var orders = emptyList<OrderLine>()
    private var kitchen = emptyList<OrderLine>()
    private var dayJob: Job? = null
    private var generation = 0
    private val authListener = FirebaseAuth.AuthStateListener { a ->
        if (a.currentUser != null) {
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
        override fun onLost(network: Network) { _network.value = false }
    }
    init {
        if (checkNetwork) {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            _network.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            runCatching { cm?.registerDefaultNetworkCallback(networkCallback) }
        }
        auth.addAuthStateListener(authListener)
        if (FirebaseConnection.emulator) scope.launch {
            while (isActive) {
                val reachable = FirebaseConnection.emulatorReachable()
                _network.value = reachable
                _emulatorError.value = if (reachable) null else
                    "테스트 DB 연결 실패: Firebase 에뮬레이터와 USB 연결(adb reverse)을 확인하세요. 운영 DB로 전환하지 않습니다."
                delay(5_000)
            }
        }
    }
    suspend fun signIn(email: String, password: String) {
        if (FirebaseConnection.emulator && !FirebaseConnection.emulatorReachable())
            throw ActionException("테스트 DB 연결 실패: 에뮬레이터와 adb reverse를 확인하세요. 운영 DB로 전환하지 않습니다")
        try {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val code = when (e) {
                is FirebaseAuthException -> e.errorCode
                is FirebaseNetworkException -> "NETWORK"
                is FirebaseTooManyRequestsException -> "TOO_MANY_REQUESTS"
                else -> "UNKNOWN"
            }
            // 원인 파악용 로그 (이메일은 가림, 비밀번호는 다루지 않음)
            Log.w("FestivalAuth", "로그인 실패 code=$code type=${e.javaClass.simpleName} detail=${maskEmails(e.message.orEmpty())}")
            throw ActionException(loginErrorMessage(code, e.message))
        }
    }
    fun signOut() { stopListening(); _authState.value = AuthState.SIGNED_OUT; auth.signOut() }

    private fun startListening() {
        if (registrations.isNotEmpty()) return
        listen("menu", db.collection(Fs.MENU)) { docs ->
            menu = docs.mapNotNull { (id, d) -> FirestoreMapper.menuItem(id, d) }
                .sortedWith(compareBy<MenuItem> { it.id.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.id })
        }
        listen("tables", db.collection(Fs.TABLES)) { docs ->
            tables = docs.mapNotNull { (id, d) -> FirestoreMapper.table(id, d) }.sortedBy { it.no }
        }
        listen("waiting", db.collection(Fs.WAITING_PRIVATE).whereIn(Fs.STATUS, Fs.ACTIVE_WAITING)
            .orderBy(Fs.IS_VIP, Query.Direction.DESCENDING).orderBy(Fs.CREATED_AT)) { docs ->
            waitings = docs.mapNotNull { (id, d) -> FirestoreMapper.waiting(id, d) }
        }
        listen("kitchen", db.collection(Fs.ORDERS).whereEqualTo(Fs.STATUS, "PENDING")
            .orderBy(Fs.CREATED_AT)) { docs ->
            kitchen = docs.mapNotNull { (id, d) -> FirestoreMapper.order(id, d) }
        }
        var day = FirestoreMapper.businessDayStartKst(System.currentTimeMillis())
        listenHistory(day)
        dayJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val next = FirestoreMapper.businessDayStartKst(System.currentTimeMillis())
                if (next != day) { day = next; listenHistory(day) }
            }
        }
    }
    private fun listenHistory(since: Long) {
        listen("history", db.collection(Fs.ORDERS).whereGreaterThanOrEqualTo(Fs.CREATED_AT, since)) { docs ->
            orders = docs.mapNotNull { (id, d) -> FirestoreMapper.order(id, d) }
        }
    }
    private fun listen(key: String, query: Query, apply: (List<Pair<String, Map<String, Any?>>>) -> Unit) {
        registrations.remove(key)?.remove()
        fromCache.remove(key)
        pending.remove(key)
        val epoch = generation
        registrations[key] = query.addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (epoch != generation) return@addSnapshotListener
            if (err != null) {
                Log.w("FestivalData", "구독 실패 ${collectionLabel(key)} code=${err.code}")
                when (err.code) {
                    // 세션 만료: 로그인 화면으로
                    FirebaseFirestoreException.Code.UNAUTHENTICATED -> {
                        signOut()
                        _errors.tryEmit("로그인이 만료되었습니다. 다시 로그인해 주세요")
                    }
                    // 권한 거부: 로그아웃하지 않고 막힌 컬렉션만 표시. 나머지 구독은 계속 동작한다.
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                        _blocked.value = _blocked.value + (key to "${collectionLabel(key)}을(를) 읽을 권한이 없습니다 — 관리자에게 문의")
                        if (key == "tables") tables = emptyList()
                        fromCache[key] = false // 막힌 구독 때문에 '연결 중'으로 남지 않게
                        publish()
                    }
                    else -> {
                        fromCache[key] = true
                        publish()
                        _errors.tryEmit(translate(err, "불러오기를").message ?: "데이터를 불러오지 못했습니다")
                    }
                }
                return@addSnapshotListener
            }
            snap ?: return@addSnapshotListener
            if (_blocked.value.containsKey(key)) _blocked.value = _blocked.value - key
            if (fromCache[key] == null && !snap.metadata.isFromCache) Log.i("FestivalData", "구독 성공 ${collectionLabel(key)} ${snap.size()}건")
            fromCache[key] = snap.metadata.isFromCache
            pending[key] = snap.documents.filter { it.metadata.hasPendingWrites() }.map { it.reference.path }.toSet()
            apply(snap.documents.mapNotNull { doc -> doc.data?.let { doc.id to it } })
            publish()
        }
    }
    private fun publish() {
        val t = tables ?: return
        _snapshot.value = Snapshot(tables = t, waitings = waitings, menu = menu, orders = orders, pendingOrders = kitchen)
        _synced.value = fromCache.size == 5 && fromCache.values.none { it }
        _pendingWrites.value = pending.values.flatten().toSet().size
    }
    private fun stopListening() {
        generation++
        registrations.values.forEach { it.remove() }
        registrations.clear()
        dayJob?.cancel()
        tables = null; waitings = emptyList(); menu = emptyList(); orders = emptyList(); kitchen = emptyList()
        fromCache.clear(); pending.clear(); _blocked.value = emptyMap()
        _snapshot.value = null; _synced.value = false; _pendingWrites.value = 0
    }
    private fun tableRef(no: Int): DocumentReference {
        require(no in 1..30)
        return db.collection(Fs.TABLES).document(no.toString())
    }
    private fun privateRef(phone: String) = db.collection(Fs.WAITING_PRIVATE).document(phone)
    private fun publicRef(id: String) = db.collection(Fs.WAITING_PUBLIC).document(id)

    /**
     * 착석: 트랜잭션 하나로 테이블 EMPTY 확인 + waiting_private 상태(WAITING/NO_SHOW) 확인 →
     * 테이블 SEATED_PENDING_PAYMENT + start_time, private·public 모두 SEATED.
     * public_id 가 없거나 public 문서가 없으면 private 만 바꾸고 경고 로그를 남긴다.
     */
    suspend fun seat(tableNo: Int, waiting: Waiting?) = transaction("착석을") { tx ->
        val ref = tableRef(tableNo)
        val table = tx.get(ref)
        if (!table.exists() || table.getString(Fs.STATUS) != "EMPTY")
            throw ActionException("빈 테이블인지 다시 확인해 주세요")
        // 트랜잭션의 읽기를 모두 마친 뒤 쓰기를 시작한다.
        if (waiting != null) {
            val pRef = privateRef(waiting.key)
            val p = tx.get(pRef)
            if (!p.exists() || p.getString(Fs.STATUS) !in Fs.ACTIVE_WAITING)
                throw ActionException("이미 처리된 웨이팅입니다")
            // 화면 캐시가 아니라 서버 문서의 public_id 를 쓴다
            val plan = FirestoreMapper.statusPlan(p.getString(Fs.PUBLIC_ID), "SEATED")
            val pub = plan.publicId?.let { publicRef(it) }?.takeIf { tx.get(it).exists() }
            tx.update(pRef, plan.privateFields)
            if (pub != null && plan.publicFields != null) tx.update(pub, plan.publicFields)
            else Log.w("FestivalData", "착석: public 짝 문서 없음 → private 만 SEATED 처리")
        }
        tx.update(ref, FirestoreMapper.seatFields(System.currentTimeMillis()))
    }
    suspend fun release(tableNo: Int, expectedStartTime: Long?) = transaction("이용 종료를") { tx ->
        val ref = tableRef(tableNo)
        val table = tx.get(ref).data?.let { FirestoreMapper.table(tableNo.toString(), it) }
        if (table == null || !table.occupied) throw ActionException("이미 정리되었거나 테이블 정보가 없습니다")
        if (!canRelease(table, expectedStartTime))
            throw ActionException("착석 정보가 바뀌었습니다. 화면을 확인하고 다시 눌러주세요")
        tx.update(ref, FirestoreMapper.releaseFields())
    }
    suspend fun confirmPayment(tableNo: Int) = update(tableRef(tableNo), FirestoreMapper.confirmPaymentFields(), "입금확인을")
    suspend fun extend(tableNo: Int, minutes: Int) = update(tableRef(tableNo), FirestoreMapper.extendFields(minutes), "시간 연장을")
    /** 호출: waiting_private 의 called_at 만 (public 에는 호출 정보가 없다). 오프라인이면 쓰기 큐. */
    suspend fun callWaiting(w: Waiting) = update(privateRef(w.key), FirestoreMapper.callFields(System.currentTimeMillis()), "호출 기록을")

    /**
     * 무응답·복귀·취소: private 와 public 을 같은 status 로 WriteBatch 한 번에 (오프라인이면 쓰기 큐).
     * public_id 가 없으면 private 만. public 문서가 없어서 배치가 NOT_FOUND 로 실패하면 private 만 다시 쓴다.
     */
    suspend fun setWaitingStatus(w: Waiting, status: String) {
        if (status == "NO_SHOW" && !w.canMarkNoShow(System.currentTimeMillis()))
            throw ActionException("호출 후 3분이 지나면 무응답 처리할 수 있습니다")
        val what = when (status) { "NO_SHOW" -> "무응답 처리를"; "WAITING" -> "대기 복귀를"; "CANCELLED" -> "웨이팅 취소를"; else -> "변경을" }
        val plan = FirestoreMapper.statusPlan(w.publicId, status)
        action(what) {
            if (plan.publicId == null) {
                Log.w("FestivalData", "$status: public_id 없음 → private 만 변경")
                privateRef(w.key).update(plan.privateFields).await()
                return@action
            }
            try {
                db.batch().update(privateRef(w.key), plan.privateFields)
                    .update(publicRef(plan.publicId), plan.publicFields!!).commit().await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.NOT_FOUND) throw e
                Log.w("FestivalData", "$status: public 짝 문서 없음 → private 만 변경")
                privateRef(w.key).update(plan.privateFields).await()
            }
        }
    }

    /**
     * VIP 등록: 트랜잭션으로 waiting_private/{번호} 확인.
     * 없거나 CANCELLED/SEATED → 새 public(autoId) + private(public_id 연결) 생성(덮어쓰기).
     * WAITING/NO_SHOW → 덮어쓰지 않는다 (기존 private·public 짝이 끊어지지 않게).
     */
    suspend fun addVip(rawPhone: String, partySize: Int) = transaction("VIP 등록을") { tx ->
        val phone = FirestoreMapper.normalizePhone(rawPhone) ?: throw ActionException("전화번호를 확인해 주세요")
        if (partySize <= 0) throw ActionException("인원을 확인해 주세요")
        val pRef = privateRef(phone)
        val existing = tx.get(pRef)
        if (!FirestoreMapper.canRegisterVip(if (existing.exists()) existing.getString(Fs.STATUS) else null))
            throw ActionException("이미 대기 중인 번호입니다")
        val now = System.currentTimeMillis()
        val pub = db.collection(Fs.WAITING_PUBLIC).document()
        tx.set(pub, FirestoreMapper.vipPublicDoc(now))
        tx.set(pRef, FirestoreMapper.vipPrivateDoc(phone, partySize, now, pub.id))
    }
    suspend fun createOrder(staff: String, tableNo: Int, cart: Map<String, Int>) = action("주문을") {
        // 배치는 원자적 쓰기만 보장한다. 전송 직전 서버 상태를 확인하지만 동시 종료와의 경쟁은 남는다.
        requireOnline("주문을")
        val ref = tableRef(tableNo)
        val table = ref.get(Source.SERVER).await().data?.let { FirestoreMapper.table(tableNo.toString(), it) }
        if (table?.occupied != true) throw ActionException("착석 처리된 테이블만 주문할 수 있습니다")
        val now = System.currentTimeMillis()
        val lines = cart.filterValues { it > 0 }.map { (menuId, qty) ->
            val m = db.collection(Fs.MENU).document(menuId).get(Source.SERVER).await().data
                ?.let { FirestoreMapper.menuItem(menuId, it) } ?: throw ActionException("없는 메뉴입니다")
            OrderLine(db.collection(Fs.ORDERS).document().id, tableNo, menuId, m.name, m.price, qty.toLong(), staff, createdAt = now)
        }
        if (lines.isEmpty()) throw ActionException("메뉴를 선택해 주세요")
        if (lines.size > 499) throw ActionException("한 번에 전송할 메뉴가 너무 많습니다")
        val total = orderTotal(lines)
        val batch = db.batch()
        lines.forEach { batch.set(db.collection(Fs.ORDERS).document(it.id), FirestoreMapper.orderDoc(it)) }
        batch.update(ref, FirestoreMapper.amountFields(total))
        batch.commit().await()
    }
    suspend fun cooked(ids: List<String>) = action("조리완료를") {
        val distinct = ids.distinct()
        if (distinct.isEmpty() || distinct.size > 500) throw ActionException("조리완료할 주문을 다시 확인하세요")
        val batch = db.batch()
        distinct.forEach { batch.update(db.collection(Fs.ORDERS).document(it), FirestoreMapper.cookedFields()) }
        batch.commit().await()
    }
    private suspend fun update(ref: DocumentReference, fields: Map<String, Any?>, what: String) = action(what) { ref.update(fields).await(); Unit }
    private suspend fun <T> transaction(what: String, block: (Transaction) -> T): T = action(what) {
        requireOnline(what)
        db.runTransaction { block(it) }.await()
    }
    private fun requireOnline(what: String) {
        if ((checkNetwork || FirebaseConnection.emulator) && !_network.value) throw ActionException(offlineMessage(what))
    }
    private suspend fun <T> action(what: String, block: suspend () -> T): T {
        if (auth.currentUser == null) throw ActionException("먼저 로그인해 주세요")
        try { return block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw translate(e, what) }
    }
    private fun offlineMessage(what: String) = "인터넷 연결이 끊겨 $what 저장하지 못했습니다. 연결되면 다시 눌러주세요"
    private fun translate(e: Throwable, what: String): ActionException {
        val causes = generateSequence(e) { it.cause }.toList()
        causes.filterIsInstance<ActionException>().firstOrNull()?.let { return it }
        val fe = causes.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        // 로그인 화면으로 보내는 것은 세션 만료(UNAUTHENTICATED)와 계정 비활성화·삭제뿐이다.
        if (fe?.code == FirebaseFirestoreException.Code.UNAUTHENTICATED || causes.any { it is FirebaseAuthInvalidUserException }) {
            signOut()
            return ActionException("로그인이 만료되었거나 계정을 쓸 수 없습니다. 다시 로그인해 주세요")
        }
        // 권한 거부: 로그아웃하지 않고 어떤 작업이 막혔는지 알린다.
        if (fe?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            Log.w("FestivalData", "쓰기 권한 거부: $what")
            return ActionException("권한이 없어 $what 저장하지 못했습니다 — 관리자에게 문의 (PERMISSION_DENIED)")
        }
        return ActionException(when (fe?.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE -> offlineMessage(what)
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "Firestore 색인 또는 설정을 확인해야 합니다. 담당자에게 알려주세요"
            FirebaseFirestoreException.Code.NOT_FOUND -> "서버에 해당 항목이 없습니다"
            FirebaseFirestoreException.Code.ABORTED -> "다른 기기에서 동시에 처리했습니다. 화면을 확인해 주세요"
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> "결과를 확인하지 못했습니다. 중복 입력 전에 화면을 확인해 주세요"
            else -> "저장하지 못했습니다: ${e.message}"
        })
    }
    fun close() {
        auth.removeAuthStateListener(authListener)
        stopListening()
        if (checkNetwork) runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
    }
}
