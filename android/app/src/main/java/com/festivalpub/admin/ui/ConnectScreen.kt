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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.festivalpub.admin.AppViewModel
import com.festivalpub.admin.Conn
import com.festivalpub.admin.data.AuthState
import com.festivalpub.admin.data.Snapshot

/**
 * 1-O 시작 화면
 *  1) 스태프 공용 계정 비밀번호 — 폰마다 처음 한 번만 (이후 로그인 유지)
 *  2) Firebase 연결 상태
 *  3) 담당자 선택 — 앱을 실행할 때마다
 */
@Composable
fun ConnectScreen(
    vm: AppViewModel,
    authState: AuthState,
    conn: Conn,
    snap: Snapshot?,
    snackbar: SnackbarHostState,
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("축제 주점 관리자", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            when (authState) {
                AuthState.CHECKING -> Text("로그인 확인 중…", color = Color.Gray)
                AuthState.SIGNED_OUT -> PasswordLogin(vm)
                AuthState.SIGNED_IN -> {
                    ConnectionStatus(conn, snap)
                    if (snap != null) StaffPicker(vm, snap)
                }
            }
        }
    }
}

@Composable
private fun PasswordLogin(vm: AppViewModel) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text("스태프 공용 비밀번호를 입력하세요. 이 폰에서는 처음 한 번만 입력하면 됩니다.", color = Color.Gray)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("비밀번호") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            busy = true
            vm.signIn(password) { busy = false }
        },
        enabled = password.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(if (busy) "로그인 중…" else "로그인", fontSize = 18.sp) }
}

@Composable
private fun ConnectionStatus(conn: Conn, snap: Snapshot?) {
    val (text, color) = when {
        snap != null && conn == Conn.CONNECTED -> "Firebase 연결됨 ✓" to Color(0xFF43A047)
        snap != null -> "인터넷 연결이 불안정합니다 — 저장된 화면으로 시작합니다" to Color(0xFFFB8C00)
        conn == Conn.DISCONNECTED -> "인터넷 연결 없음 — 와이파이(핫스팟)를 확인하세요. 연결되면 자동으로 이어집니다" to Color(0xFFE53935)
        else -> "Firebase 연결 중…" to Color.Gray
    }
    Text(text, color = color)
}

@Composable
private fun StaffPicker(vm: AppViewModel, snap: Snapshot) {
    var customName by remember { mutableStateOf("") }
    SectionTitle("담당자 선택")
    Text("선택한 이름이 착석·호출·조리완료 기록에 남습니다.", color = Color.Gray, fontSize = 13.sp)
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
