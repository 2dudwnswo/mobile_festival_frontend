package com.festivalpub.admin

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.festivalpub.admin.data.ApiClient
import com.festivalpub.admin.data.ApiException
import com.festivalpub.admin.data.Settings
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.TableState
import com.festivalpub.admin.data.WsMessage
import com.festivalpub.admin.data.state
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

private const val OFFLINE_RETRIES = 30 // 2초 간격 → 약 1분

class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("pub", Context.MODE_PRIVATE)
    private val alerts = Alerts(app)

    private val _serverUrl = MutableStateFlow(prefs.getString("serverUrl", "") ?: "")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _conn = MutableStateFlow(Conn.DISCONNECTED)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** 1-O 담당자. 앱을 새로 실행할 때마다 다시 선택한다. */
    private val _staff = MutableStateFlow<String?>(null)
    val staff: StateFlow<String?> = _staff.asStateFlow()

    /** 서버 시계 기준 현재 시각. 1초마다 갱신 → 모든 타이머가 이 값으로 계산됨 */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** 알림을 눌러 열었을 때 이동할 하단 탭. 화면이 소비하면 null 로 되돌린다. */
    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    @Volatile private var clockOffset = 0L
    private var api: ApiClient? = null
    private var socketJob: Job? = null

    // 알림 상태 (init 보다 먼저 초기화되어야 함)
    private var knownOvertime = emptySet<Int>()
    private var knownImminent = emptySet<Int>()
    private var lastOvertimeAlert = 0L

    init {
        Notifications.createChannels(app)
        if (_serverUrl.value.isNotBlank()) connect(_serverUrl.value)
        viewModelScope.launch {
            while (isActive) {
                val t = System.currentTimeMillis() + clockOffset
                _now.value = t
                checkAlerts(t)
                delay(1000 - (System.currentTimeMillis() % 1000))
            }
        }
    }

    // ---------------- 연결 ----------------

    fun connect(rawUrl: String) {
        if (rawUrl.isBlank()) return
        val client = ApiClient(rawUrl)
        _serverUrl.value = client.baseUrl
        prefs.edit().putString("serverUrl", client.baseUrl).apply()

        socketJob?.cancel()
        api?.shutdown()
        api = client
        _snapshot.value = null

        socketJob = viewModelScope.launch {
            var backoff = 1000L
            while (isActive) {
                _conn.value = Conn.CONNECTING
                val closed = CompletableDeferred<Unit>()
                var opened = false
                val ws = client.openSocket(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        _conn.value = Conn.CONNECTED
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = runCatching { ApiClient.json.decodeFromString(WsMessage.serializer(), text) }.getOrNull()
                        val data = msg?.data
                        if (msg?.type == "snapshot" && data != null) applySnapshot(data)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(Unit)
                    }
                })
                try {
                    closed.await()
                } finally {
                    ws.cancel()
                }
                _conn.value = Conn.DISCONNECTED
                if (opened) backoff = 1000L
                delay(backoff) // 서버 재시작/핫스팟 순단 시 자동 재연결
                backoff = (backoff * 2).coerceAtMost(5000L)
            }
        }
    }

    private fun applySnapshot(s: Snapshot) {
        if (s.serverTime > 0) clockOffset = s.serverTime - System.currentTimeMillis()
        _snapshot.value = s
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
        val s = _snapshot.value ?: return
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

    // ---------------- 서버 요청 ----------------

    private fun request(
        path: String,
        method: String = "POST",
        extra: JsonObject? = null,
        onOk: () -> Unit = {},
        // 두 번 보내도 결과가 같은 요청만 true. 서버에 닿지 못하면 잠시 재시도한다.
        retryWhileOffline: Boolean = false,
    ) {
        val client = api ?: run { _messages.tryEmit("서버에 연결되지 않았습니다"); return }
        val body = buildJsonObject {
            put("staff", _staff.value ?: "")
            extra?.forEach { (k, v) -> put(k, v) }
        }
        viewModelScope.launch {
            var attempt = 0
            while (true) {
                try {
                    client.send(method, path, body)
                    onOk()
                    return@launch
                } catch (e: ApiException) {
                    if (retryWhileOffline && e.offline && ++attempt < OFFLINE_RETRIES) {
                        delay(2000)
                        continue
                    }
                    _messages.tryEmit(e.message ?: "요청 실패")
                    return@launch
                }
            }
        }
    }

    // 테이블
    fun seat(tableNo: Int, waitingId: Int?) = request(
        "/api/tables/$tableNo/seat",
        extra = buildJsonObject { if (waitingId != null) put("waitingId", waitingId) },
    )

    fun extend(tableNo: Int, minutes: Int) = request(
        "/api/tables/$tableNo/extend",
        extra = buildJsonObject { put("minutes", minutes) },
    )

    fun release(tableNo: Int) = request("/api/tables/$tableNo/release")

    // 웨이팅
    fun addVip(phone: String, partySize: Int, onOk: () -> Unit) = request(
        "/api/waitings",
        extra = buildJsonObject {
            put("phone", phone.filter { it.isDigit() })
            put("partySize", partySize)
            put("isVip", true)
        },
        onOk = onOk,
    )

    // 탭 즉시 전화가 걸리므로, 서버가 잠깐 끊겨 있어도 호출 기록이 남도록 재시도한다.
    // (전화 앱이 앞에 떠 있는 동안에는 오류 안내를 볼 수 없어서 [무응답] 버튼이 영영 안 나오게 됨)
    fun callWaiting(id: Int) = request("/api/waitings/$id/call", retryWhileOffline = true)
    fun noShow(id: Int) = request("/api/waitings/$id/no-show")
    fun restoreWaiting(id: Int) = request("/api/waitings/$id/restore")
    fun cancelWaiting(id: Int) = request("/api/waitings/$id/cancel")

    // 주문
    fun createOrder(tableNo: Int, cart: Map<Int, Int>, onOk: () -> Unit) = request(
        "/api/orders",
        extra = buildJsonObject {
            put("tableNo", tableNo)
            put("source", "STAFF")
            putJsonArray("items") {
                cart.filterValues { it > 0 }.forEach { (menuId, qty) ->
                    addJsonObject {
                        put("menuId", menuId)
                        put("qty", qty)
                    }
                }
            }
        },
        onOk = onOk,
    )

    // 입금확인·주문취소는 서버가 한다 (API.md v0.2). 앱은 조리완료만 보낸다.
    fun cooked(orderId: Int) = request("/api/orders/$orderId/cooked")

    /** 주방 탭에 새 주문(서버가 PAID 로 바꾼 주문)이 들어왔을 때 짧은 알림음 */
    fun newOrderChime() = alerts.newOrder()

    // 설정 (시간 값만. 테이블 배치 rows/cols 는 웹서버가 관리하므로 보내지 않는다)
    fun saveSettings(s: Settings) = request(
        "/api/settings",
        method = "PUT",
        extra = buildJsonObject {
            put("rotationMinutes", s.rotationMinutes)
            put("imminentMinutes", s.imminentMinutes)
            put("noShowMinutes", s.noShowMinutes)
        },
        onOk = { _messages.tryEmit("설정을 저장했습니다") },
    )

    override fun onCleared() {
        // 앱을 완전히 종료(액티비티 finish)하면 서비스도 멈춘다
        stopBackground()
        socketJob?.cancel()
        api?.shutdown()
        alerts.release()
    }
}
