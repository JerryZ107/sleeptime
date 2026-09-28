package com.sleeptime.lock

import android.content.Context
import android.media.AudioManager

/**
 * 来电检测。
 * 用 AudioManager.getMode() 判断，不需要任何运行时权限：
 *   MODE_RINGTONE / MODE_IN_CALL / MODE_IN_COMMUNICATION 全部算「有电话进来」。
 * 微信语音通话会落到 MODE_IN_COMMUNICATION，所以微信电话也会放行 —— 符合预期。
 */
object CallGuard {

    @Volatile
    var inCall: Boolean = false
        private set

    fun detect(ctx: Context): Boolean = try {
        when (ctx.getSystemService(AudioManager::class.java).mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
    } catch (t: Throwable) {
        false
    }

    fun update(value: Boolean) {
        inCall = value
    }
}