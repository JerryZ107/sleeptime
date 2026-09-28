package com.sleeptime.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 唤醒入口（对外的正式契约）。
 *
 * 为什么需要它：App 被「强行停止」（或 ColorOS 的「禁止后台运行」、清理工具、系统的闲置休眠）
 * 之后会被打成 stopped 状态 —— 进程被杀、闹钟和 Job 全部撤销，它自己没有任何办法自救。
 * 但 Android 只对**隐式广播**把 stopped 的包筛掉，**显式 intent 照样投递**，
 * 而且投递时系统还会顺手把 stopped 清回 false。实测（ColorOS 15）：
 *   am force-stop com.sleeptime.lock          → stopped=true，闹钟 4 条变 0，Job 消失
 *   am broadcast -a com.sleeptime.lock.WAKE \
 *      -n com.sleeptime.lock/.WakeReceiver    → stopped=false，闹钟 4 条全回来，Job 也回来
 *
 * 于是在任何自动化工具 / 伴侣 App / adb 里，一行就能把它救活：
 *   sendBroadcast(Intent("com.sleeptime.lock.WAKE")
 *       .setComponent(ComponentName("com.sleeptime.lock", "com.sleeptime.lock.WakeReceiver")))
 * 幂等：没被停止时这条广播只是重排一遍闹钟，没有副作用。
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = Prefs(ctx)
        Log.i("SleepTime", "WakeReceiver: 被叫醒 action=${intent?.action} enabled=${p.enabled} 该锁=${Scheduler.shouldLock(p)}")
        if (!p.enabled) return

        // 1) 每日闹钟 + 预热闹钟 + 备份闹钟，重排
        runCatching { Scheduler.arm(ctx) }
        // 2) 持久化任务重建（force-stop 会把它一起撤掉）
        runCatching { Scheduler.armGuardJob(ctx) }
        // 3) 守护服务拉起来
        LockService.start(ctx)
        // 4) 如果正该锁着（比如刚被救活时已经过点了），立刻补上
        if (Scheduler.shouldLock(p) && Settings.canDrawOverlays(ctx)) {
            LockActivity.launch(ctx)
        }
    }
}