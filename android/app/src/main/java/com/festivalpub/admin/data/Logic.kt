package com.festivalpub.admin.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면에 보이는 테이블 상태. 임박·초과는 두 착석 상태 모두에서 앱이 계산한다. */
enum class TableState(val label: String) {
    EMPTY("빈자리"),
    IN_USE("이용중"),
    IMMINENT("임박"),
    OVERTIME("초과"),
}

private const val MINUTE = 60_000L

/** 착석 시각 + (회전시간 + 연장) */
fun TableInfo.endAt(s: Settings): Long? =
    startTime?.let { it + (s.rotationMinutes + extendedMinutes) * MINUTE }

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

/** 대기/호출 중인 팀. 호출 여부는 calledAt으로 판단한다. */
fun Snapshot.activeWaitings(): List<Waiting> = waitings.filter { it.status == "WAITING" }
    .sortedWith(compareBy<Waiting>({ !it.isVip }, { it.createdAt }, { it.key }))
fun Snapshot.noShowWaitings(): List<Waiting> = waitings.filter { it.status == "NO_SHOW" }
    .sortedWith(compareBy<Waiting>({ !it.isVip }, { it.createdAt }, { it.key }))
fun Snapshot.waitingLabel(w: Waiting): String = if (w.isVip) "VIP" else {
    val general = (activeWaitings() + noShowWaitings()).filter { !it.isVip }
    general.indexOfFirst { it.key == w.key }.takeIf { it >= 0 }?.let { "${it + 1}" } ?: "-"
}
fun Waiting.canMarkNoShow(now: Long): Boolean =
    status == "WAITING" && calledAt != null && now - calledAt >= Settings().noShowMinutes * 60_000L
fun TableInfo.paymentOverdue(now: Long): Boolean =
    status == "SEATED_PENDING_PAYMENT" && !paymentConfirmed && startTime != null && now - startTime >= 5 * 60_000L
fun canRelease(table: TableInfo, expectedStartTime: Long?): Boolean =
    table.occupied && expectedStartTime != null && table.startTime == expectedStartTime

/** 입금과 무관하게 모든 조리 대기 줄을 표시한다. */
fun Snapshot.kitchenOrders(): List<OrderLine> = pendingOrders.filter { it.status == "PENDING" }
    .sortedWith(compareBy<OrderLine> { it.createdAt }.thenBy { it.id })
fun Snapshot.kitchenGroups(): List<KitchenGroup> = kitchenOrders()
    .groupBy { it.tableNo to it.createdAt }
    .map { (key, lines) -> KitchenGroup(key.first, key.second, lines) }
    .sortedWith(compareBy<KitchenGroup> { it.createdAt }.thenBy { it.tableNo })
fun hasNewKitchenOrder(prev: Set<String>?, current: Set<String>): Boolean =
    prev != null && (current - prev).isNotEmpty()
fun Snapshot.ordersForTable(t: TableInfo): List<OrderLine> {
    if (!t.occupied) return emptyList()
    val since = t.startTime ?: return emptyList()
    return orders.filter { it.tableNo == t.no && it.createdAt >= since }.sortedBy { it.createdAt }
}
fun orderTotal(lines: List<OrderLine>): Long = lines.fold(0L) { sum, line ->
    Math.addExact(sum, Math.multiplyExact(line.price, line.qty))
}

// ---------- 대시보드 격자 (스크롤 없이 한 화면) ----------

/** 격자 줄 수. 기본값은 5행이며, 테이블 수가 rows*cols 보다 많으면 모자란 줄을 늘린다. */
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

fun formatWon(v: Long): String = String.format(Locale.KOREA, "%,d원", v)

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
