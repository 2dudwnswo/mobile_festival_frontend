package com.festivalpub.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.festivalpub.admin.AppViewModel
import com.festivalpub.admin.data.Order
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.TableInfo
import com.festivalpub.admin.data.TableState
import com.festivalpub.admin.data.activeWaitings
import com.festivalpub.admin.data.endAt
import com.festivalpub.admin.data.formatClock
import com.festivalpub.admin.data.formatPhone
import com.festivalpub.admin.data.formatRemaining
import com.festivalpub.admin.data.formatWon
import com.festivalpub.admin.data.ordersForTable
import com.festivalpub.admin.data.remainingMs
import com.festivalpub.admin.data.state

// ============================================================
// 1-1 테이블 현황 대시보드
// ============================================================
@Composable
fun TablesScreen(vm: AppViewModel, snap: Snapshot, now: Long) {
    var selectedNo by remember { mutableStateOf<Int?>(null) }
    val s = snap.settings
    val states = snap.tables.associate { it.no to it.state(s, now) }

    Column(Modifier.fillMaxSize()) {
        // 상태 요약
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TableState.entries.forEach { st ->
                val count = states.values.count { it == st }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(PubColors.of(st)))
                    HSpace(4)
                    Text("${st.label} $count", fontSize = 13.sp)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(s.cols.coerceAtLeast(1)),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(snap.tables, key = { it.no }) { t ->
                TableTile(
                    table = t,
                    state = states[t.no] ?: TableState.EMPTY,
                    remainingMs = t.remainingMs(s, now),
                    now = now,
                    onClick = { selectedNo = t.no },
                )
            }
        }
    }

    val selected = selectedNo?.let { no -> snap.tables.find { it.no == no } }
    if (selected != null) {
        TableDetailSheet(vm, snap, selected, now, onDismiss = { selectedNo = null })
    }
}

@Composable
private fun TableTile(
    table: TableInfo,
    state: TableState,
    remainingMs: Long?,
    now: Long,
    onClick: () -> Unit,
) {
    val blink = state == TableState.OVERTIME && (now / 1000) % 2 == 0L
    val bg = PubColors.of(state).copy(alpha = if (blink) 0.6f else 1f)
    val fg = if (state == TableState.EMPTY) Color(0xFF37474F) else Color.White

    Box(
        Modifier
            .aspectRatio(0.9f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(2.dp),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${table.no}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fg)
            if (remainingMs != null) {
                Text(formatRemaining(remainingMs), fontSize = 12.sp, color = fg, maxLines = 1)
            } else {
                Text("빈자리", fontSize = 11.sp, color = fg.copy(alpha = 0.6f), maxLines = 1)
            }
        }
    }
}

// ============================================================
// 1-2 테이블 상세 (바텀시트)
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableDetailSheet(vm: AppViewModel, snap: Snapshot, table: TableInfo, now: Long, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            if (table.occupied) {
                OccupiedTableContent(vm, snap, table, now, onDismiss)
            } else {
                EmptyTableContent(vm, snap, table, onDismiss)
            }
        }
    }
}

@Composable
private fun EmptyTableContent(vm: AppViewModel, snap: Snapshot, table: TableInfo, onDismiss: () -> Unit) {
    Text("${table.no}번 테이블 · 빈자리", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("착석 처리하는 순간 ${snap.settings.rotationMinutes}분 타이머가 시작됩니다.", color = Color.Gray, fontSize = 13.sp)

    SectionTitle("웨이팅 팀 배정")
    val waitings = snap.activeWaitings().take(5)
    if (waitings.isEmpty()) {
        Text("대기 중인 팀이 없습니다", color = Color.Gray)
    }
    var generalRank = 0
    waitings.forEach { w ->
        val rank = if (w.isVip) "VIP" else "${++generalRank}순위"
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (w.isVip) PubColors.VipBg else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$rank · ${formatPhone(w.phone)}", fontWeight = FontWeight.Bold)
                Text("${w.partySize}명 · ${if (w.status == "CALLED") "호출됨" else "대기"}", fontSize = 13.sp, color = Color.Gray)
            }
            Button(onClick = { vm.seat(table.no, w.id); onDismiss() }) { Text("착석") }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { vm.seat(table.no, null); onDismiss() },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("웨이팅 없이 바로 착석 (현장 손님)") }
}

@Composable
private fun OccupiedTableContent(vm: AppViewModel, snap: Snapshot, table: TableInfo, now: Long, onDismiss: () -> Unit) {
    val s = snap.settings
    val st = table.state(s, now)
    val remaining = table.remainingMs(s, now) ?: 0L
    var confirmRelease by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${table.no}번 테이블", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        HSpace(8)
        Text(
            st.label,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(PubColors.of(st)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    Text(
        if (remaining >= 0) "남은 시간 ${formatRemaining(remaining)}" else "시간 초과 ${formatRemaining(remaining)}",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = if (st == TableState.IN_USE) MaterialTheme.colorScheme.onSurface else PubColors.of(st),
        modifier = Modifier.padding(vertical = 4.dp),
    )
    val info = buildList {
        add("착석 ${formatClock(table.seatedAt)}")
        add("종료 ${formatClock(table.endAt(s))}")
        table.partySize?.let { add("${it}명") }
        if (table.extendedMinutes > 0) add("연장 +${table.extendedMinutes}분")
    }.joinToString(" · ")
    Text(info, color = Color.Gray)
    table.phone?.let { Text(formatPhone(it), color = Color.Gray) }

    SectionTitle("시간 연장")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 20, 30).forEach { m ->
            OutlinedButton(onClick = { vm.extend(table.no, m) }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("+${m}분")
            }
        }
    }

    // 주문 내역 + 합계 (서버가 입금을 확인한 주문만)
    val orders = snap.ordersForTable(table)
    SectionTitle("주문 내역")
    Text("주문 합계 ${formatWon(orders.sumOf { it.total })}", fontWeight = FontWeight.Bold)
    Text("입금이 확인된 주문만 표시됩니다", color = Color.Gray, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    if (orders.isEmpty()) Text("아직 주문이 없습니다", color = Color.Gray)
    orders.forEach { o ->
        OrderCard(o)
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { confirmRelease = true },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("이용 종료 (빈자리로)", fontSize = 16.sp) }

    if (confirmRelease) {
        ConfirmDialog(
            title = "${table.no}번 테이블 이용 종료",
            text = "테이블을 정리하고 빈자리로 돌립니다.",
            confirmLabel = "종료",
            destructive = true,
            onConfirm = { vm.release(table.no); onDismiss() },
            onDismiss = { confirmRelease = false },
        )
    }
}

// ============================================================
// 주문 카드 (테이블 상세). 입금확인된 주문만 들어온다.
// ============================================================
@Composable
private fun OrderCard(order: Order) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            val head = buildString {
                append("#${order.id} · ${formatClock(order.createdAt)}")
                append(if (order.source == "QR") " · QR" else " · 직원(${order.addedBy ?: "-"})")
            }
            Text(head, fontSize = 13.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            order.items.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text("${line.name} × ${line.qty}", Modifier.weight(1f))
                    Text(formatWon(line.price * line.qty), color = Color.Gray)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatWon(order.total), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val cook = if (order.cookStatus == "DONE") "조리완료" else "조리중"
                Text(cook, fontSize = 13.sp, color = Color(0xFF2E7D32))
            }
        }
    }
}
