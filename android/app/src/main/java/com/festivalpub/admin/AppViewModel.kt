package com.festivalpub.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.festivalpub.admin.data.ActionException
import com.festivalpub.admin.data.AuthState
import com.festivalpub.admin.data.FirebaseRepository
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.TableState
import com.festivalpub.admin.data.Waiting
import com.festivalpub.admin.data.state
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val alerts = Alerts(app)
    private val repo = FirebaseRepository(app)

    /** 스태프 공용 계정 로그인 상태. 폰마다 한 번 로그인하면 유지된다. */
    val authState: StateFlow<AuthState> = repo.authState

    /** Firebase 리스너 결과를 합친 전체 상태. 화면은 언제나 이 값에서 그린다. */
    val snapshot: StateFlow<Snapshot?> = repo.snapshot

    /** 연결 상태: 인터넷이 없으면 끊김, 서버와 아직 동기화 전이면 연결 중 */
    val conn: StateFlow<Conn> = combine(repo.network, repo.synced) { net, synced ->
        when {
            !net -> Conn.DISCONNECTED
            !synced -> Conn.CONNECTING
            else -> Conn.CONNECTED
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Conn.CONNECTING)

    /** 끊긴 동안 쌓여 아직 서버로 가지 않은 쓰기 수 (연결되면 자동 전송) */
    val emulatorError = repo.emulatorError

    val pendingWrites: StateFlow<Int> = repo.pendingWrites

    /** 1-O 담당자. 앱을 새로 실행할 때마다 다시 선택한다. */
    private val _staff = MutableStateFlow<String?>(null)
    val staff: StateFlow<String?> = _staff.asStateFlow()

    /** 기기 시계 기준 현재 시각. 1초마다 갱신 → 모든 타이머가 이 값으로 계산됨 */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** 알림을 눌러 열었을 때 이동할 하단 탭. 화면이 소비하면 null 로 되돌린다. */
    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // 알림 상태 (init 보다 먼저 초기화되어야 함)
    private var knownOvertime = emptySet<Int>()
    private var knownImminent = emptySet<Int>()
    private var lastOvertimeAlert = 0L

    init {
        Notifications.createChannels(app)
        viewModelScope.launch {
            while (isActive) {
                val t = System.currentTimeMillis()
                _now.value = t
                checkAlerts(t)
                delay(1000 - (System.currentTimeMillis() % 1000))
            }
        }
        viewModelScope.launch { repo.errors.collect { _messages.tryEmit(it) } }
        // 계정 로그아웃(또는 다른 기기에서 비밀번호 변경 등으로 로그인이 풀림) → 담당자·서비스도 정리
        viewModelScope.launch {
            repo.authState.collect { if (it == AuthState.SIGNED_OUT && _staff.value != null) logoutStaff() }
        }
    }

    // ---------------- 계정 · 담당자 ----------------

    fun signIn(email: String, password: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                repo.signIn(email, password)
            } catch (e: ActionException) {
                _messages.tryEmit(e.message ?: "로그인 실패")
            } finally {
                onDone()
            }
        }
    }

    /** 설정 화면의 [로그아웃]: 공용 계정에서 로그아웃. 다시 쓰려면 비밀번호를 입력해야 한다. */
    fun signOut() {
        logoutStaff()
        repo.signOut()
    }

    fun selectStaff(name: String) {
        _staff.value = name.trim().ifBlank { null }
        // 담당자를 고르면 백그라운드에서도 알림이 끊기지 않도록 포그라운드 서비스 시작
        if (_staff.value != null) KeepAliveService.start(app)
    }

    fun logoutStaff() {
        _staff.value = null
        stopBackground()
    }

    fun requestTab(tab: Int) {
        _tabRequest.value = tab
    }

    fun consumeTabRequest() {
        _tabRequest.value = null
    }

    private fun stopBackground() {
        KeepAliveService.stop(app)
        Notifications.cancelOvertime(app)
        knownOvertime = emptySet()
        knownImminent = emptySet()
    }

    // ---------------- 알림 ----------------

    private fun checkAlerts(now: Long) {
        val s = snapshot.value ?: return
        if (_staff.value == null) return
        val over = s.tables.filter { it.state(s.settings, now) == TableState.OVERTIME }.map { it.no }.toSet()
        val imminent = s.tables.filter { it.state(s.settings, now) == TableState.IMMINENT }.map { it.no }.toSet()
        val real = System.currentTimeMillis()
        when {
            // 새로 초과된 테이블 → 즉시 알림
            (over - knownOvertime).isNotEmpty() -> { alerts.overtime(); lastOvertimeAlert = real }
            // 초과 테이블이 정리되지 않고 남아 있으면 1분마다 반복
            over.isNotEmpty() && real - lastOvertimeAlert >= 60_000 -> { alerts.overtime(); lastOvertimeAlert = real }
            (imminent - knownImminent).isNotEmpty() -> alerts.imminent()
        }
        // 상태바 알림: 초과 테이블 목록이 바뀔 때만 갱신 (새로 초과된 테이블이 있으면 헤드업)
        if (over != knownOvertime) {
            Notifications.overtime(app, over.sorted(), newlyOver = (over - knownOvertime).isNotEmpty())
        }
        knownOvertime = over
        knownImminent = imminent
    }

    private val _orderSending = MutableStateFlow(false)
    val orderSending: StateFlow<Boolean> = _orderSending.asStateFlow()

    // ---------------- Firebase 쓰기 ----------------

    /** 담당자 선택 뒤 액션을 허용한다. 담당자 이름은 주문 added_by에만 기록한다. */
    private fun write(onOk: () -> Unit = {}, block: suspend (staff: String) -> Unit) {
        val staff = _staff.value ?: run { _messages.tryEmit("담당자를 먼저 선택하세요"); return }
        viewModelScope.launch {
            try {
                block(staff)
                onOk()
            } catch (e: ActionException) {
                _messages.tryEmit(e.message ?: "저장하지 못했습니다")
            }
        }
    }

    fun seat(tableNo: Int, waiting: Waiting?) = write { repo.seat(tableNo, waiting) }
    fun extend(tableNo: Int, minutes: Int) = write { repo.extend(tableNo, minutes) }
    // 확인창을 열 때 보던 착석 시각을 그대로 전달한다.
    fun release(tableNo: Int, expectedStartTime: Long?) = write { repo.release(tableNo, expectedStartTime) }
    fun confirmPayment(tableNo: Int, expectedStartTime: Long?, expectedAmount: Long) = write {
        val current = snapshot.value?.tables?.find { it.no == tableNo }
        if (expectedStartTime == null || current?.startTime != expectedStartTime ||
            current.totalAmount != expectedAmount || current.status != "SEATED_PENDING_PAYMENT") {
            throw ActionException("테이블 또는 금액이 바뀌었습니다. 다시 확인해 주세요")
        }
        repo.confirmPayment(tableNo)
    }
    fun addVip(phone: String, partySize: Int, onOk: () -> Unit) =
        write(onOk) { repo.addVip(phone.filter { c -> c.isDigit() }, partySize) }
    fun callWaiting(w: Waiting) = write { repo.callWaiting(w) }
    fun noShow(w: Waiting) = write { repo.setWaitingStatus(w, "NO_SHOW") }
    fun restoreWaiting(w: Waiting) = write { repo.setWaitingStatus(w, "WAITING") }
    fun cancelWaiting(w: Waiting) = write { repo.setWaitingStatus(w, "CANCELLED") }
    fun createOrder(tableNo: Int, cart: Map<String, Int>, onOk: () -> Unit) {
        if (_orderSending.value) return
        val operator = _staff.value ?: return
        _orderSending.value = true
        viewModelScope.launch {
            try {
                repo.createOrder(operator, tableNo, cart)
                onOk()
            } catch (e: ActionException) {
                _messages.tryEmit(e.message ?: "주문을 저장하지 못했습니다")
            } finally {
                _orderSending.value = false
            }
        }
    }
    fun cooked(ids: List<String>) = write { repo.cooked(ids) }
    fun newOrderChime() = alerts.newOrder()

    override fun onCleared() {
        // 앱을 완전히 종료(액티비티 finish)하면 서비스도 멈춘다
        stopBackground()
        repo.close()
        alerts.release()
    }
}
