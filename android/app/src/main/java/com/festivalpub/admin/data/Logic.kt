package com.festivalpub.admin.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면에 보이는 테이블 상태. 서버는 EMPTY/OCCUPIED만 알고, 임박·초과는 앱이 계산한다. */
enum class TableState(val label: String) {
    EMPTY("빈자리"),
    IN_USE("이용중"),
    IMMINENT("임박"),
    OVERTIME("초과"),
}

private const val MINUTE = 60_000L

/** 착석 시각 + (회전시간 + 연장) */
fun TableInfo.endAt(s: Settings): Long? =
    seatedAt?.let { it + (s.rotationMinutes + extendedMinutes) * MINUTE }

fun TableInfo.remainingMs(s: Settings, now: Long): Long? = endAt(s)?.let { it - now }

fun TableInfo.state(s: Settings, now: Long): TableState {
    if (!occupied) return TableState.EMPTY
    val r = remainingMs(s, now) ?: return TableState.IN_USE
    return when {
        r <= 0 -> TableState.OVERTIME
        r <= s.imminentMinutes * MINUTE -> TableState.IMMINENT
        else -> TableState.IN_USE
    }
}

/** 대기/호출 중인 팀. VIP 먼저, 그 다음 등록 시간순. */
fun Snapshot.activeWaitings(): List<Waiting> =
    waitings.filter { it.status == "WAITING" || it.status == "CALLED" }
        .sortedWith(compareBy<Waiting>({ !it.isVip }, { it.createdAt }))

/** 무응답 처리된 팀 (흐리게 아래쪽에 표시) */
fun Snapshot.noShowWaitings(): List<Waiting> =
    waitings.filter { it.status == "NO_SHOW" }.sortedBy { it.createdAt }

/**
 * 앱이 쓰는 주문은 서버가 입금을 확인한(PAID) 것뿐이다.
 * 스냅샷에 PENDING/CANCELLED 가 섞여 와도 여기서 걸러 무시한다. (API.md v0.2)
 */
val Order.paid: Boolean get() = paymentStatus == "PAID"

/** 주방: 입금확인된 주문 중 조리 전인 것, 입금 순서대로 */
fun Snapshot.kitchenOrders(): List<Order> =
    orders.filter { it.paid && it.cookStatus == "WAITING" }
        .sortedBy { it.paidAt ?: it.createdAt }

/** 주방에 새로 들어온 주문이 있는지. 처음 화면을 열었을 때(prev == null)는 알리지 않는다. */
fun hasNewKitchenOrder(prev: Set<Int>?, current: Set<Int>): Boolean =
    prev != null && (current - prev).isNotEmpty()

/** 현재 착석 팀의 입금확인된 주문만 (이전 손님 주문 제외) */
fun Snapshot.ordersForTable(t: TableInfo): List<Order> {
    val since = t.seatedAt ?: return emptyList()
    return orders.filter { it.tableNo == t.no && it.createdAt >= since && it.paid }
        .sortedBy { it.createdAt }
}

// ---------- 대시보드 격자 (스크롤 없이 한 화면) ----------

/** 격자 줄 수. 보통 서버의 rows 이고, 테이블 수가 rows*cols 보다 많으면 모자란 줄을 늘린다. */
fun gridRows(tableCount: Int, rows: Int, cols: Int): Int {
    val c = cols.coerceAtLeast(1)
    val needed = (tableCount + c - 1) / c
    return maxOf(rows, needed, 1)
}

/**
 * 정사각형 타일 한 변의 길이(dp) = min(가로 폭 ÷ 열 수, 사용 가능한 높이 ÷ 줄 수).
 * 타일 사이 간격 [gap] 은 먼저 빼고 나눈다. 공간이 없으면 0.
 */
fun gridTileSize(width: Float, height: Float, rows: Int, cols: Int, gap: Float): Float {
    val c = cols.coerceAtLeast(1)
    val r = rows.coerceAtLeast(1)
    val byWidth = (width - gap * (c - 1)) / c
    val byHeight = (height - gap * (r - 1)) / r
    return minOf(byWidth, byHeight).coerceAtLeast(0f)
}

// ---------- 표시용 포맷 ----------

fun formatPhone(p: String): String = when (p.length) {
    11 -> "${p.substring(0, 3)}-${p.substring(3, 7)}-${p.substring(7)}"
    10 -> "${p.substring(0, 3)}-${p.substring(3, 6)}-${p.substring(6)}"
    else -> p
}

fun formatWon(v: Int): String = String.format(Locale.KOREA, "%,d원", v)

/** 남은 시간: "42:10", 초과면 "+3:05" (분은 60을 넘어도 그대로 표시) */
fun formatRemaining(ms: Long): String {
    val neg = ms < 0
    val total = kotlin.math.abs(ms) / 1000
    val text = String.format(Locale.KOREA, "%d:%02d", total / 60, total % 60)
    return if (neg) "+$text" else text
}

fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    return String.format(Locale.KOREA, "%d:%02d", total / 60, total % 60)
}

fun minutesAgo(ms: Long): Long = (ms.coerceAtLeast(0)) / MINUTE

private val clockFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
fun formatClock(epoch: Long?): String = if (epoch == null) "-" else clockFormat.format(Date(epoch))
