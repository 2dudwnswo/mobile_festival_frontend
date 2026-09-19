package com.festivalpub.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** 1-S 설정: 테이블 배치 보기(읽기 전용), 회전/임박/무응답 시간, 담당자 변경, 계정 로그아웃 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    snap: Snapshot,
    staff: String,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    val current = snap.settings
    var rotation by remember(current) { mutableIntStateOf(current.rotationMinutes) }
    var imminent by remember(current) { mutableIntStateOf(current.imminentMinutes) }
    var noShow by remember(current) { mutableIntStateOf(current.noShowMinutes) }
    var confirmSignOut by remember { mutableStateOf(false) }

    // 테이블 배치(rows/cols)는 웹서버가 관리한다. 앱은 시간 값만 수정한다.
    val edited = current.copy(rotationMinutes = rotation, imminentMinutes = imminent, noShowMinutes = noShow)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            SectionTitle("테이블 배치 (웹서버에서 관리)")
            Text(
                "가로 ${current.cols} × 세로 ${current.rows} · 총 ${snap.tables.size}개",
                fontWeight = FontWeight.Bold,
            )
            Text(
                "배치는 웹서버에서 바꾸면 이 앱에 자동으로 반영됩니다.",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            LayoutPreview(current.rows, current.cols)

            SectionTitle("시간")
            Stepper("회전 시간", rotation, { rotation = it }, 30..240, step = 5, suffix = "분")
            Spacer(Modifier.height(8.dp))
            Stepper("임박 표시 (종료 전)", imminent, { imminent = it }, 5..60, step = 5, suffix = "분")
            Spacer(Modifier.height(8.dp))
            Stepper("무응답 버튼 (호출 후)", noShow, { noShow = it }, 1..10, suffix = "분")

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.saveSettings(edited) },
                enabled = edited != current,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("저장 (모든 기기에 적용)", fontSize = 16.sp) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()

            SectionTitle("담당자")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("현재: $staff", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { vm.logoutStaff(); onClose() }) { Text("담당자 변경") }
            }

            SectionTitle("스태프 계정")
            Text(
                "이 폰은 스태프 공용 계정으로 로그인되어 있습니다. 로그아웃하면 비밀번호를 다시 입력해야 합니다.",
                color = Color.Gray,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Text("로그아웃", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // 로그아웃은 비밀번호를 아는 사람만 되돌릴 수 있으므로 확인창을 띄운다
    if (confirmSignOut) {
        ConfirmDialog(
            title = "로그아웃",
            text = "이 폰에서 스태프 계정을 로그아웃합니다. 다시 쓰려면 공용 비밀번호를 입력해야 합니다.",
            confirmLabel = "로그아웃",
            destructive = true,
            onConfirm = { vm.signOut(); onClose() },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** 배치 미리보기: 실제 대시보드와 같은 가로×세로 격자를 작은 고정 크기로 (읽기 전용) */
@Composable
private fun LayoutPreview(rows: Int, cols: Int) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F5F5))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in 0 until cols) {
                    val no = r * cols + c + 1
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFB0BEC5)),
                        contentAlignment = Alignment.Center,
                    ) { Text("$no", fontSize = 9.sp, color = Color.White, maxLines = 1) }
                }
            }
        }
    }
}
