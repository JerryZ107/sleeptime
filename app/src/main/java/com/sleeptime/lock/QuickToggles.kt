package com.sleeptime.lock

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * 锁屏下拉面板上的开关 —— 只做第三方 App **真能做**的那几个。
 *
 * 能做：
 *   手电筒    CameraManager.setTorchMode                不需要任何权限（本机实测）
 *   静音      AudioManager.adjustStreamVolume(MUTE)    只静音铃声/通知/系统音，不碰勿扰
 *   免打扰    NotificationManager.setInterruptionFilter  需要系统勿扰权限
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

    private val SILENT_STREAMS = intArrayOf(
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_DTMF
    )

    fun isSilent(ctx: Context): Boolean = Prefs(ctx).silentOn

    /**
     * Reconcile our app-owned silent state with the real system state when the
     * panel opens. DND is treated as a separate mode: if DND is on, its ringer
     * changes must not rewrite the silent button state. If DND is off, ringer
     * silent or stream mute means the system really is silent.
     */
    fun syncSilentState(ctx: Context) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val dndOn = nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (dndOn) return@runCatching

        val am = ctx.getSystemService(AudioManager::class.java)
        val silent = am.ringerMode == AudioManager.RINGER_MODE_SILENT ||
            am.isStreamMute(AudioManager.STREAM_RING) ||
            am.isStreamMute(AudioManager.STREAM_NOTIFICATION)
        Prefs(ctx).silentOn = silent
    }.onFailure { Log.w(TAG, "syncSilentState failed", it) }

    /**
     * Mute/unmute only the notification/ring/system streams.
     * This deliberately does NOT call setRingerMode: ColorOS couples ringer mode
     * with ZenModeHelper/DND, which made the old silent button toggle DND.
     */
    fun setSilent(ctx: Context, on: Boolean): Boolean = runCatching {
        val am = ctx.getSystemService(AudioManager::class.java)
        val direction = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val ringOk = runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_RING, direction, 0)
        }.isSuccess
        SILENT_STREAMS.filter { it != AudioManager.STREAM_RING }.forEach { stream ->
            runCatching { am.adjustStreamVolume(stream, direction, 0) }
        }
        if (!on) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val dndOn = nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            if (!dndOn && am.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
            }
        }
        if (ringOk) Prefs(ctx).silentOn = on
        Log.i(TAG, "setSilent($on) ringOk=$ringOk streams=${SILENT_STREAMS.joinToString()}")
        ringOk
    }.onFailure { Log.w(TAG, "setSilent($on) failed", it) }.getOrDefault(false)

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
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
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

    /**
     * Best-effort background cleanup.
     *
     * Android does not expose a public "clear all recents" API to third-party apps.
     * With KILL_BACKGROUND_PROCESSES we can only ask the system to kill the
     * background processes of user-installed apps. Foreground services and system
     * apps are intentionally left alone.
     */
    fun clearBackground(ctx: Context, done: (Int) -> Unit) {
        Thread {
            var requested = 0
            runCatching {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val apps = ctx.packageManager.getInstalledApplications(0)
                for (app in apps) {
                    if (app.packageName == ctx.packageName) continue
                    if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                    if (runCatching { am.killBackgroundProcesses(app.packageName) }.isSuccess) {
                        requested++
                    }
                }
            }.onFailure { Log.w(TAG, "clearBackground failed", it) }
            Log.i(TAG, "clearBackground requested=$requested")
            Handler(Looper.getMainLooper()).post { done(requested) }
        }.start()
    }

    fun isTorch(): Boolean = Torch.isOn

    fun setTorch(ctx: Context, on: Boolean): Boolean = Torch.set(ctx, on)
}