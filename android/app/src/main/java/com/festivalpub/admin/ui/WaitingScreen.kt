package com.festivalpub.admin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.festivalpub.admin.AppViewModel
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.Waiting
import com.festivalpub.admin.data.activeWaitings
import com.festivalpub.admin.data.formatClock
import com.festivalpub.admin.data.formatElapsed
import com.festivalpub.admin.data.formatPhone
import com.festivalpub.admin.data.minutesAgo
import com.festivalpub.admin.data.noShowWaitings
import com.festivalpub.admin.data.waitingLabel
import com.festivalpub.admin.data.canMarkNoShow
import com.festivalpub.admin.dialPhone

// ============================================================
// 1-3 웨이팅 목록
//  - 맨 위 팀 탭 → 바로 전화 + 호출됨 기록
//  - 다른 팀 탭 / 길게 누르기 / ⋮ → 1-4 상세
//  - 호출 후 N분(기본 3분) 지나면 [무응답] 버튼
//  - 무응답 팀은 아래쪽에 흐리게(반투명) 모아서 표시
// ============================================================
@Composable
fun WaitingScreen(vm: AppViewModel, snap: Snapshot, now: Long) {
    val context = LocalContext.current
    val active = snap.activeWaitings()
    val noShows = snap.noShowWaitings()
    val noShowMs = snap.settings.noShowMinutes * 60_000L
    var detailId by remember { mutableStateOf<String?>(null) }
    var showVip by remember { mutableStateOf(false) }

    // 순위 라벨: VIP는 "VIP", 일반은 1,2,3…
    var g = 0
    val ranks = active.map { if (it.isVip) "VIP" else "${++g}" }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val vipCount = active.count { it.isVip }
                Text(
                    "대기 ${active.size}팀" + if (vipCount > 0) " (VIP $vipCount)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (active.isEmpty()) {
                item { Text("대기 중인 팀이 없습니다", color = Color.Gray, modifier = Modifier.padding(vertical = 24.dp)) }
            }
            itemsIndexed(active, key = { _, w -> "a${w.key}" }) { i, w ->
                WaitingCard(
                    waiting = w,
                    rank = ranks[i],
                    isTop = i == 0,
                    dimmed = false,
                    now = now,
                    noShowMs = noShowMs,
                    onClick = {
                        if (i == 0) {
                            vm.callWaiting(w)
                            dialPhone(context, w.phone)
                        } else {
                            detailId = w.key
                        }
                    },
                    onMore = { detailId = w.key },
                    onNoShow = { vm.noShow(w) },
                )
            }
            if (noShows.isNotEmpty()) {
                item {
                    Text(
                        "무응답 ${noShows.size}팀 — 늦게 오면 눌러서 대기 복귀/착석",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(noShows, key = { "n${it.key}" }) { w ->
                    WaitingCard(
                        waiting = w,
                        rank = snap.waitingLabel(w),
                        isTop = false,
                        dimmed = true,
                        now = now,
                        noShowMs = noShowMs,
                        onClick = { detailId = w.key },
                        onMore = { detailId = w.key },
                        onNoShow = {},
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showVip = true },
            icon = { Icon(Icons.Filled.Star, contentDescription = null) },
            text = { Text("VIP 등록") },
            containerColor = PubColors.Vip,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    val detail = detailId?.let { id -> snap.waitings.find { it.key == id } }
    if (detail != null) {
        WaitingDetailSheet(vm, snap, detail, now, onDismiss = { detailId = null })
    }
    if (showVip) {
        VipDialog(vm, onDismiss = { showVip = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WaitingCard(
    waiting: Waiting,
    rank: String,
    isTop: Boolean,
    dimmed: Boolean,
    now: Long,
    noShowMs: Long,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onNoShow: () -> Unit,
) {
    val container = when {
        isTop -> MaterialTheme.colorScheme.primaryContainer
        waiting.isVip -> PubColors.VipBg
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val calledElapsed = if (waiting.status == "WAITING" && waiting.calledAt != null) now - waiting.calledAt else null

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onMore),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (waiting.isVip) PubColors.Vip else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(rank, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (rank.length > 2) 12.sp else 16.sp)
            }
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Text(formatPhone(waiting.phone), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${waiting.partySize}명 · ${formatClock(waiting.createdAt)} 등록 (${minutesAgo(now - waiting.createdAt)}분 전)",
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
                when {
                    calledElapsed != null -> Text(
                        "호출됨 · ${formatElapsed(calledElapsed)} 경과",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (calledElapsed >= noShowMs) Color(0xFFE53935) else Color(0xFFFB8C00),
                    )
                    waiting.status == "NO_SHOW" -> Text("무응답", fontSize = 13.sp, color = Color.Gray)
                    isTop -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Call, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        HSpace(4)
                        Text("탭하면 바로 전화", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (calledElapsed != null && calledElapsed >= noShowMs) {
                Button(
                    onClick = onNoShow,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text("무응답") }
            }
            IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "상세") }
        }
    }
}

// ============================================================
// 1-4 웨이팅 상세 (바텀시트)
// ============================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WaitingDetailSheet(vm: AppViewModel, snap: Snapshot, w: Waiting, now: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmCancel by remember { mutableStateOf(false) }
    val emptyTables = snap.tables.filter { it.status == "EMPTY" }
    val active = w.status == "WAITING"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (w.isVip) {
                    Icon(Icons.Filled.Star, contentDescription = "VIP", tint = PubColors.Vip)
                    HSpace(4)
                }
                Text(formatPhone(w.phone), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            val statusLabel = when (w.status) {
                "WAITING" -> if (w.calledAt == null) "대기" else "호출됨 (${formatElapsed(now - w.calledAt)} 경과)"
                "NO_SHOW" -> "무응답"
                "SEATED" -> "착석"
                else -> "취소됨"
            }
            Text("${w.partySize}명 · 대기번호 ${snap.waitingLabel(w)} · ${formatClock(w.createdAt)} 등록 · $statusLabel", color = Color.Gray)

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (active) vm.callWaiting(w)
                    dialPhone(context, w.phone)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                HSpace(8)
                Text("전화 걸기", fontSize = 16.sp)
            }

            SectionTitle("착석 배정 — 빈 테이블 선택")
            if (emptyTables.isEmpty()) {
                Text("빈 테이블이 없습니다", color = Color.Gray)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    emptyTables.forEach { t ->
                        FilledTonalButton(
                            onClick = { vm.seat(t.no, w); onDismiss() },
                            modifier = Modifier.size(width = 64.dp, height = 52.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("${t.no}", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (w.canMarkNoShow(now)) {
                OutlinedButton(onClick = { vm.noShow(w); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("무응답 처리 (다음 팀으로 넘기기)")
                }
            }
            if (w.status == "NO_SHOW") {
                Button(onClick = { vm.restoreWaiting(w); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("대기 복귀 (원래 순서로)")
                }
            }
            TextButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) {
                Text("웨이팅 취소", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "웨이팅 취소",
            text = "${formatPhone(w.phone)} (${w.partySize}명) 웨이팅을 취소합니다.",
            confirmLabel = "웨이팅 취소",
            destructive = true,
            onConfirm = { vm.cancelWaiting(w); onDismiss() },
            onDismiss = { confirmCancel = false },
        )
    }
}

// ============================================================
// VIP 등록 (스태프 전용)
// ============================================================
@Composable
private fun VipDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    var party by remember { mutableIntStateOf(2) }
    val digits = phone.filter { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VIP 등록") },
        text = {
            Column {
                Text("VIP는 대기 순서와 관계없이 맨 위에 배치됩니다.", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                    label = { Text("전화번호") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Stepper("인원", party, { party = it }, 1..12, suffix = "명")
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.addVip(digits, party, onOk = onDismiss) }, enabled = digits.length >= 10) {
                Text("등록", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
