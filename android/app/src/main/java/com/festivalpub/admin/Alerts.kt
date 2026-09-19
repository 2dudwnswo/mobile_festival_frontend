package com.festivalpub.admin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 시간 초과(강한 진동 + 소리), 임박(짧은 진동) 알림 */
class Alerts(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()

    fun overtime() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
    }

    /** 주방: 새 주문 도착. 진동 없이 짧은 소리 한 번 */
    fun newOrder() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
    }

    fun imminent() {
        vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // 알람 용도로 진동해야 '터치 진동 끄기' 설정에 묻히지 않는다 (기본값은 TOUCH/UNKNOWN 취급)
    private fun vibrate(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    fun release() {
        tone?.release()
    }
}
