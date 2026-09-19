package com.festivalpub.admin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
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

/** 동현 v2. 로그인 후에만 구독하며 실제 데이터 쓰기는 화면의 액션에서만 실행한다. */
class FirebaseRepository(context: Context, private val checkNetwork: Boolean = true) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    }
    suspend fun signIn(email: String, password: String) {
        try {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            throw ActionException(when (e) {
                is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> "이메일 또는 비밀번호를 확인하세요"
                is FirebaseNetworkException -> "인터넷 연결을 확인하세요"
                is FirebaseTooManyRequestsException -> "시도가 너무 많습니다. 잠시 후 다시 시도하세요"
                else -> "로그인하지 못했습니다. 계정과 연결 상태를 확인하세요"
            })
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
        listen("waiting", db.collection(Fs.WAITING).whereIn(Fs.STATUS, Fs.ACTIVE_WAITING)
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
                fromCache[key] = true
                publish()
                _errors.tryEmit(translate(err, "불러오기를").message ?: "데이터를 불러오지 못했습니다")
                return@addSnapshotListener
            }
            snap ?: return@addSnapshotListener
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
        fromCache.clear(); pending.clear()
        _snapshot.value = null; _synced.value = false; _pendingWrites.value = 0
    }
    private fun tableRef(no: Int): DocumentReference {
        require(no in 1..30)
        return db.collection(Fs.TABLES).document(no.toString())
    }
    private fun waitingRef(w: Waiting) = db.collection(Fs.WAITING).document(w.key)
    suspend fun seat(tableNo: Int, waiting: Waiting?) = transaction("착석을") { tx ->
        val ref = tableRef(tableNo)
        val table = tx.get(ref)
        if (!table.exists() || table.getString(Fs.STATUS) != "EMPTY")
            throw ActionException("빈 테이블인지 다시 확인해 주세요")
        // 트랜잭션의 읽기를 모두 마친 뒤 쓰기를 시작한다.
        if (waiting != null) {
            val wRef = waitingRef(waiting)
            val w = tx.get(wRef)
            if (!w.exists() || w.getString(Fs.STATUS) !in Fs.ACTIVE_WAITING)
                throw ActionException("이미 처리된 웨이팅입니다")
            tx.update(wRef, FirestoreMapper.waitingStatusFields("SEATED"))
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
    suspend fun confirmPayment(tableNo: Int) = update(tableRef(tableNo), FirestoreMapper.confirmPaymentFields())
    suspend fun extend(tableNo: Int, minutes: Int) = update(tableRef(tableNo), FirestoreMapper.extendFields(minutes))
    suspend fun callWaiting(w: Waiting) = update(waitingRef(w), FirestoreMapper.callFields(System.currentTimeMillis()))
    suspend fun setWaitingStatus(w: Waiting, status: String) {
        if (status == "NO_SHOW" && !w.canMarkNoShow(System.currentTimeMillis()))
            throw ActionException("호출 후 3분이 지나면 무응답 처리할 수 있습니다")
        update(waitingRef(w), FirestoreMapper.waitingStatusFields(status))
    }
    suspend fun addVip(phone: String, partySize: Int) = action("VIP 등록을") {
        if (_snapshot.value?.waitings.orEmpty().any { it.phone == phone }) throw ActionException("이미 대기 중입니다")
        db.collection(Fs.WAITING).document().set(FirestoreMapper.vipWaitingDoc(phone, partySize, System.currentTimeMillis())).await()
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
    private suspend fun update(ref: DocumentReference, fields: Map<String, Any?>) = action("변경을") { ref.update(fields).await(); Unit }
    private suspend fun <T> transaction(what: String, block: (Transaction) -> T): T = action(what) {
        requireOnline(what)
        db.runTransaction { block(it) }.await()
    }
    private fun requireOnline(what: String) {
        if (checkNetwork && !_network.value) throw ActionException(offlineMessage(what))
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
        if (fe?.code in listOf(FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED) ||
            causes.any { it is FirebaseAuthInvalidUserException }) {
            signOut()
            return ActionException("로그인 또는 데이터 권한을 확인해 주세요. 다시 로그인해야 합니다")
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
