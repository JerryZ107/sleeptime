package com.sleeptime.lock

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 锁屏下拉面板上的开关 —— 只做第三方 App **真能做**的那几个。
 *
 * 能做：
 *   手电筒  CameraManager.setTorchMode                     不需要任何权限（本机实测）
 *   静音    AudioManager.setRingerMode(RINGER_MODE_SILENT)  需要用户授予系统「勿扰权限」
 *   免打扰  NotificationManager.setInterruptionFilter       同一个「勿扰权限」
 *
 * 做不了（照着手机下拉面板列过一遍，都是系统/签名级权限，普通 App 一律没戏，所以面板里不放）：
 *   蓝牙     Android 13 起 BluetoothAdapter.enable/disable 对普通 App 封死（deprecated）
 *   WiFi     Android 10 起 WifiManager.setWifiEnabled 只对系统 App 生效
 *   热点     TetheringManager 起停需要 TETHER_PRIVILEGED（signature|privileged）
 *   护眼模式 Settings.Secure.night_display_activated 需要 WRITE_SECURE_SETTINGS
 *   省电模式 PowerManager.setPowerSaveModeEnabled 是 @SystemApi
 *   NFC      NfcAdapter.enable/disable 是 @SystemApi
 */
object QuickToggles {

    private const val TAG = "SleepTime"

    fun isSilent(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT
    }.getOrDefault(false)

    /** 开/关静音；没授「勿扰权限」会失败并返回 false */
    fun setSilent(ctx: Context, on: Boolean): Boolean = runCatching {
        ctx.getSystemService(AudioManager::class.java)
            .ringerMode = if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
        true
    }.onFailure { Log.w(TAG, "setSilent($on) 失败", it) }.getOrDefault(false)

    fun hasDndAccess(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    fun isDnd(ctx: Context): Boolean = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }.getOrDefault(false)

    fun setDnd(ctx: Context, on: Boolean): Boolean = runCatching {
        ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        true
    }.onFailure { Log.w(TAG, "setDnd($on) 失败", it) }.getOrDefault(false)

    fun isTorch(): Boolean = Torch.isOn

    fun setTorch(ctx: Context, on: Boolean): Boolean = Torch.set(ctx, on)
}