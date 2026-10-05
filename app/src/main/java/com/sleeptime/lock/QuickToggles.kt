package com.sleeptime.lock

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
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
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val canReadDnd = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
        val dndBefore = if (canReadDnd) runCatching { nm.currentInterruptionFilter }.getOrNull() else null

        am.ringerMode = if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL

        val dndAfter = if (canReadDnd) runCatching { nm.currentInterruptionFilter }.getOrNull() else null
        Log.i(TAG, "setSilent($on) -> ringerMode=${am.ringerMode} dndBefore=$dndBefore dndAfter=$dndAfter")

        // Some ROMs couple ringer-silent with DND. The silent button must not touch DND:
        // if DND changed, restore exactly the previous interruption filter.
        if (dndBefore != null && dndAfter != null && dndBefore != dndAfter) {
            runCatching { nm.setInterruptionFilter(dndBefore) }
            Log.w(TAG, "silent changed DND ($dndBefore -> $dndAfter), restored to $dndBefore")
        }
        true
    }.onFailure { Log.w(TAG, "setSilent($on) 失败", it) }.getOrDefault(false)

    fun hasDndAccess(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    /** Open the system notification-policy (DND) access page; fall back to the global list. */
    fun openDndAccessSettings(ctx: Context): Boolean {
        val detail = Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.APP_PACKAGE", ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(detail) }.isSuccess) return true

        val global = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(global) }.isSuccess
    }

    fun isDnd(ctx: Context): Boolean = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }.getOrDefault(false)

    fun setDnd(ctx: Context, on: Boolean): Boolean = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        Log.i(TAG, "setDnd($on) -> filter=${nm.currentInterruptionFilter}")
        true
    }.onFailure { Log.w(TAG, "setDnd($on) 失败", it) }.getOrDefault(false)

    fun isTorch(): Boolean = Torch.isOn

    fun setTorch(ctx: Context, on: Boolean): Boolean = Torch.set(ctx, on)
}