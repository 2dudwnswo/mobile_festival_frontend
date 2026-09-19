package com.festivalpub.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.festivalpub.admin.AppViewModel
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.formatClock
import com.festivalpub.admin.data.kitchenOrders
import com.festivalpub.admin.data.minutesAgo
import com.festivalpub.admin.data.hasNewKitchenOrder

private const val SLOW_COOK_MIN = 15L

// ============================================================
// 1-6 주문 현황 (주방용)
//  입금확인된 주문만, 입금 순서대로. 조리완료는 되돌릴 수 없으므로 확인창.
// ============================================================
@Composable
fun KitchenScreen(vm: AppViewModel, snap: Snapshot, now: Long) {
    val orders = snap.kitchenOrders()
    var confirmId by remember { mutableStateOf<Int?>(null) }

    // 서버가 입금을 확인해 새 주문이 들어오면 짧은 알림음. 이 화면이 떠 있을 때만 동작한다
    // (다른 탭에서 돌아오면 prev 가 null 로 초기화되어 이미 쌓인 주문으로는 울리지 않음).
    val ids = orders.map { it.id }.toSet()
    var prevIds by remember { mutableStateOf<Set<Int>?>(null) }
    LaunchedEffect(ids) {
        if (hasNewKitchenOrder(prevIds, ids)) vm.newOrderChime()
        prevIds = ids
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "조리 대기 ${orders.size}건 · 입금이 확인된 주문만 표시됩니다",
                color = Color.Gray,
                fontSize = 13.sp,
            )
        }
        if (orders.isEmpty()) {
            item { Text("들어온 주문이 없습니다", color = Color.Gray, modifier = Modifier.padding(vertical = 24.dp)) }
        }
        items(orders, key = { it.id }) { o ->
            val since = o.paidAt ?: o.createdAt
            val mins = minutesAgo(now - since)
            val slow = mins >= SLOW_COOK_MIN
            Card(
                colors = CardDefaults.cardColors(containerColor = if (slow) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${o.tableNo}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        o.items.forEach { line ->
                            Text("${line.name} × ${line.qty}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "#${o.id} · 입금 ${formatClock(since)} · ${mins}분 경과",
                            fontSize = 13.sp,
                            color = if (slow) Color(0xFFE53935) else Color.Gray,
                        )
                    }
                    Button(
                        onClick = { confirmId = o.id },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
                        modifier = Modifier.height(56.dp),
                    ) { Text("조리완료", fontSize = 16.sp) }
                }
            }
        }
    }

    val target = confirmId?.let { id -> orders.find { it.id == id } }
    if (target != null) {
        ConfirmDialog(
            title = "${target.tableNo}번 · 주문 #${target.id}",
            text = target.items.joinToString("\n") { "${it.name} × ${it.qty}" } + "\n\n조리완료 처리하면 되돌릴 수 없습니다.",
            confirmLabel = "조리완료",
            onConfirm = { vm.cooked(target.id) },
            onDismiss = { confirmId = null },
        )
    }
}
