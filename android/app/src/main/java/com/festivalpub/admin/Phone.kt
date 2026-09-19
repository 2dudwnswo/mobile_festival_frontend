package com.festivalpub.admin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * 전화 권한이 있으면 즉시 발신(ACTION_CALL),
 * 없으면 번호가 입력된 다이얼러(ACTION_DIAL)를 연다.
 */
fun dialPhone(context: Context, phone: String) {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED
    val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
    val intent = Intent(action, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
