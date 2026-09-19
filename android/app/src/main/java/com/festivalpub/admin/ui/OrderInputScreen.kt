package com.festivalpub.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.festivalpub.admin.AppViewModel
import com.festivalpub.admin.data.MenuItem
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.formatWon

// ============================================================
// 1-5 주문 입력 (서빙 스태프용 · QR 주문의 예비 수단)
//  테이블 선택 → 메뉴 탭해서 담기 → 수량 조절 → 전송(확인창 1회)
//  직원 주문은 조리 대기 줄로 생성된다.
// ============================================================
@Composable
fun OrderInputScreen(vm: AppViewModel, snap: Snapshot) {
    val sending by vm.orderSending.collectAsStateWithLifecycle()
    var tableNo by remember { mutableStateOf<Int?>(null) }
    val cart = remember { mutableStateMapOf<String, Int>() } // menuId → qty
    var confirm by remember { mutableStateOf(false) }

    val occupied = snap.tables.filter { it.occupied }
    val menuById = snap.menu.associateBy { it.id }
    val total = cart.entries.sumOf { (id, qty) -> (menuById[id]?.price ?: 0L) * qty }

    // 선택했던 테이블이 종료되면 선택 해제
    LaunchedEffect(occupied.map { it.no }) {
        if (tableNo != null && occupied.none { it.no == tableNo }) tableNo = null
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "테이블 선택 (이용 중인 테이블만)",
            modifier = Modifier.padding(start = 12.dp, top = 10.dp),
            fontSize = 13.sp,
            color = Color.Gray,
        )
        if (occupied.isEmpty()) {
            Text("이용 중인 테이블이 없습니다", modifier = Modifier.padding(12.dp), color = Color.Gray)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(occupied.size) { i ->
                    val t = occupied[i]
                    FilterChip(
                        selected = tableNo == t.no,
                        enabled = !sending,
                        onClick = { tableNo = t.no },
                        label = { Text("${t.no}번", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    )
                }
            }
        }
        HorizontalDivider()

        // 메뉴 그리드 (카테고리별)
        val grouped = mapOf("메뉴" to snap.menu)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            grouped.forEach { (category, list) ->
                item(key = "h_$category", span = { GridItemSpan(maxLineSpan) }) {
                    Text(category, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
                items(list, key = { it.id }) { m ->
                    MenuButton(m, cart[m.id] ?: 0, enabled = !sending) { cart[m.id] = (cart[m.id] ?: 0) + 1 }
                }
            }
        }

        // 장바구니
        if (cart.isNotEmpty()) {
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                cart.entries.sortedBy { it.key }.forEach { (id, qty) ->
                    val m = menuById[id] ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, Modifier.weight(1f), fontSize = 16.sp)
                        QtyButton("−", enabled = !sending) {
                            if (qty <= 1) {
                                cart.remove(id)
                            } else {
                                cart[id] = qty - 1
                            }
                        }
                        Text("$qty", Modifier.padding(horizontal = 4.dp).size(width = 32.dp, height = 24.dp), textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        QtyButton("+", enabled = !sending) { cart[id] = qty + 1 }
                        Text(formatWon(m.price * qty), Modifier.padding(start = 8.dp).size(width = 80.dp, height = 24.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { cart.clear() }, enabled = cart.isNotEmpty() && !sending) { Text("비우기") }
            Text(formatWon(total), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Button(
                onClick = { confirm = true },
                enabled = tableNo != null && cart.isNotEmpty() && !sending,
                modifier = Modifier.height(52.dp),
            ) { Text(if (sending) "전송 중…" else if (tableNo == null) "테이블 선택" else "${tableNo}번에 전송", fontSize = 16.sp) }
        }
    }

    val selectedTable = tableNo
    if (confirm && selectedTable != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("${selectedTable}번 테이블", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    cart.entries.sortedBy { it.key }.forEach { (id, qty) ->
                        Text("${menuById[id]?.name ?: "?"} × $qty", fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("합계 ${formatWon(total)} · 전송하면 바로 주방에 표시됩니다", color = Color.Gray, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.createOrder(selectedTable, cart.toMap(), onOk = { cart.clear() })
                }) { Text("전송", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun MenuButton(item: MenuItem, qty: Int, enabled: Boolean, onClick: () -> Unit) {
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = when {
                qty > 0 -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Column(
                Modifier.padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center,
                    color = Color.Unspecified)
                Text(formatWon(item.price), fontSize = 12.sp, color = Color.Gray)
            }
        }
        if (qty > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("$qty", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun QtyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(label, fontSize = 20.sp) }
}
