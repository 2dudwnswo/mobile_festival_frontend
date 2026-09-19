package com.festivalpub.admin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 앱이 백그라운드(은행 앱, 통화 등)에 있어도 프로세스가 얼지 않게 붙잡아 두는 포그라운드 서비스.
 * "주점 관리자 실행 중" 상시 알림만 띄운다. WebSocket·타이머·알림 판단은 AppViewModel 에 그대로 있다.
 *
 * - 담당자를 선택하면 시작, 담당자 변경(로그아웃)이나 앱 종료 시 멈춘다.
 * - 최근 앱 목록에서 스와이프로 닫으면 stopWithTask(매니페스트)로 함께 멈춘다.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_RUNNING, Notifications.running(this), type)
        // 프로세스가 죽으면 ViewModel 도 없으므로 서비스만 되살릴 필요가 없다.
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
