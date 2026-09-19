package com.festivalpub.admin

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.festivalpub.admin.data.Snapshot
import com.festivalpub.admin.data.activeWaitings
import com.festivalpub.admin.data.kitchenOrders
import com.festivalpub.admin.ui.AppTheme
import com.festivalpub.admin.ui.ConnectScreen
import com.festivalpub.admin.ui.HSpace
import com.festivalpub.admin.ui.KitchenScreen
import com.festivalpub.admin.ui.OrderInputScreen
import com.festivalpub.admin.ui.SettingsScreen
import com.festivalpub.admin.ui.TablesScreen
import com.festivalpub.admin.ui.WaitingScreen

class MainActivity : ComponentActivity() {
    // Compose 의 viewModel() 과 같은 인스턴스 (같은 액티비티 · 같은 기본 키)
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 행사 중 화면이 꺼지지 않게
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) handleTabExtra(intent)
        setContent { AppTheme { App(vm) } }
    }

    // "N번 테이블 시간 초과" 알림을 누르면 이미 떠 있는 화면으로 들어온다 (SINGLE_TOP)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTabExtra(intent)
    }

    private fun handleTabExtra(intent: Intent?) {
        val tab = intent?.getIntExtra(Notifications.EXTRA_TAB, -1) ?: -1
        if (tab >= 0) vm.requestTab(tab)
    }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val staff by vm.staff.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // 첫 실행 때 한 번에 요청:
    //  - 전화: 허용하면 웨이팅 탭 한 번에 바로 발신
    //  - 알림(Android 13+): "N번 테이블 시간 초과" 알림과 실행 중 상시 알림
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    val snap = snapshot
    val staffName = staff
    if (staffName == null || snap == null) {
        ConnectScreen(vm, conn, snap, serverUrl, snackbar)
    } else {
        MainScaffold(vm, snap, staffName, conn, serverUrl, snackbar)
    }
}

private data class TabDef(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    TabDef("테이블", Icons.Filled.Home),
    TabDef("웨이팅", Icons.Filled.List),
    TabDef("주문 입력", Icons.Filled.ShoppingCart),
    TabDef("주방", Icons.Filled.Notifications),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    vm: AppViewModel,
    snap: Snapshot,
    staff: String,
    conn: Conn,
    serverUrl: String,
    snackbar: SnackbarHostState,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val now by vm.now.collectAsStateWithLifecycle()
    val tabRequest by vm.tabRequest.collectAsStateWithLifecycle()

    // 알림을 눌러 들어오면 해당 탭으로 (설정 화면이 열려 있으면 닫고)
    LaunchedEffect(tabRequest) {
        tabRequest?.let {
            tab = it
            showSettings = false
            vm.consumeTabRequest()
        }
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(vm, snap, serverUrl, staff, snackbar, onClose = { showSettings = false })
        return
    }

    val waitingCount = snap.activeWaitings().size
    val kitchenCount = snap.kitchenOrders().size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs[tab].title) },
                actions = {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            when (conn) {
                                Conn.CONNECTED -> Color(0xFF43A047)
                                Conn.CONNECTING -> Color(0xFFFB8C00)
                                Conn.DISCONNECTED -> Color(0xFFE53935)
                            }
                        )
                    )
                    HSpace(6)
                    Text(staff, fontSize = 14.sp)
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    val badge = when (i) {
                        1 -> waitingCount
                        3 -> kitchenCount
                        else -> 0
                    }
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                                Icon(t.icon, contentDescription = t.title)
                            }
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (conn != Conn.CONNECTED) {
                Text(
                    "서버 연결 끊김 — 자동으로 다시 연결하는 중… (화면 정보가 최신이 아닐 수 있음)",
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFE53935)).padding(8.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TablesScreen(vm, snap, now)
                    1 -> WaitingScreen(vm, snap, now)
                    2 -> OrderInputScreen(vm, snap)
                    else -> KitchenScreen(vm, snap, now)
                }
            }
        }
    }
}
