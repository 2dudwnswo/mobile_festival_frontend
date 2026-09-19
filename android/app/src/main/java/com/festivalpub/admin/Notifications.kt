package com.festivalpub.admin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 상태바 알림: 실행 중(상시) + 테이블 시간 초과 */
object Notifications {
    const val ID_RUNNING = 1
    private const val ID_OVERTIME = 2
    private const val CH_RUNNING = "running"
    private const val CH_OVERTIME = "overtime"

    /** 알림을 눌렀을 때 열 하단 탭 번호 (0 = 테이블) */
    const val EXTRA_TAB = "tab"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "실행 상태", NotificationManager.IMPORTANCE_LOW).apply {
                description = "백그라운드에서도 시간 초과 알림을 받기 위한 상시 알림"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_OVERTIME, "테이블 시간 초과", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "이용 시간이 끝난 테이블"
                // 진동·소리는 Alerts 가 알람 용도로 직접 낸다 (1분마다 반복). 알림 자체는 조용히 띄운다.
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun running(context: Context): Notification =
        NotificationCompat.Builder(context, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_pub)
            .setContentTitle("주점 관리자 실행 중")
            .setContentText("백그라운드에서도 테이블 시간 초과를 알려 드립니다")
            .setOngoing(true)
            .setContentIntent(openApp(context, tab = null))
            .build()

    /**
     * 초과 테이블 목록으로 알림을 갱신한다. 비어 있으면 알림을 지운다.
     * [newlyOver] 가 true 일 때만 헤드업으로 띄우고, 목록만 바뀐 경우엔 조용히 갱신한다.
     */
    fun overtime(context: Context, tableNos: List<Int>, newlyOver: Boolean) {
        val nm = NotificationManagerCompat.from(context)
        if (tableNos.isEmpty()) {
            nm.cancel(ID_OVERTIME)
            return
        }
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_OVERTIME)
            .setSmallIcon(R.drawable.ic_stat_pub)
            .setContentTitle("${tableNos.joinToString(", ")}번 테이블 시간 초과")
            .setContentText("눌러서 테이블 현황 열기")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(!newlyOver)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, tab = 0))
            .build()
        runCatching { nm.notify(ID_OVERTIME, n) }
    }

    fun cancelOvertime(context: Context) = NotificationManagerCompat.from(context).cancel(ID_OVERTIME)

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context, tab: Int?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (tab != null) intent.putExtra(EXTRA_TAB, tab)
        return PendingIntent.getActivity(
            context,
            tab ?: -1, // 탭 지정 알림과 단순 열기 알림이 서로 덮어쓰지 않게 requestCode 분리
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
