package com.festivalpub.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.festivalpub.admin.AppViewModel
import com.festivalpub.admin.Conn
import com.festivalpub.admin.data.Snapshot

/** 서버 연결 + 1-O 담당자 선택 */
@Composable
fun ConnectScreen(
    vm: AppViewModel,
    conn: Conn,
    snap: Snapshot?,
    serverUrl: String,
    snackbar: SnackbarHostState,
) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl.removePrefix("http://")) }
    var customName by remember { mutableStateOf("") }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("축제 주점 관리자", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("핫스팟 와이파이에 연결한 뒤, 노트북 서버 주소를 입력하세요.", color = Color.Gray)

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("서버 주소 (예: 192.168.43.100:8080)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.connect(url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("연결", fontSize = 18.sp) }

            val statusText = when {
                snap != null -> "연결됨 ✓"
                conn == Conn.CONNECTING -> "연결 중…"
                serverUrl.isNotBlank() -> "연결 안 됨 — 주소와 와이파이(핫스팟)를 확인하세요. 계속 재시도합니다."
                else -> ""
            }
            if (statusText.isNotEmpty()) {
                Text(statusText, color = if (snap != null) Color(0xFF43A047) else Color(0xFFE53935))
            }

            if (snap != null) {
                SectionTitle("담당자 선택")
                Text("선택한 이름이 입금확인·착석·주문 기록에 남습니다.", color = Color.Gray, fontSize = 13.sp)
                snap.staff.forEach { name ->
                    OutlinedButton(
                        onClick = { vm.selectStaff(name) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(name, fontSize = 18.sp) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("목록에 없으면 이름 직접 입력") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.selectStaff(customName) },
                    enabled = customName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("이 이름으로 시작") }
            }
        }
    }
}
