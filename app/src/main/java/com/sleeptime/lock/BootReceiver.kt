package com.sleeptime.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * 开机 / 更新 / 改时间 / 改时区后：
 *   1. 重排每日闹钟（Android 重启会清掉所有闹钟，不排就永远不会锁）
 *   2. 检查「现在是否在睡眠时段内」（锁定时刻 ~ 解除时刻），是的话立刻锁定
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = Prefs(ctx)
        val action = intent?.action

        // 自启动自检的命根子：系统只要把开机广播送进来，就先盖戳。
        // 盖在 enabled 判断之前 —— 自检问的是「广播有没有到」，跟总开关无关。
        // LOCKED_BOOT_COMPLETED 是「开机但还没解锁」时发的，比 BOOT_COMPLETED 早得多，
        // 而且那时候 CE 存储还没挂上 —— 配置改存 DE 之后，这条广播才真正用得上。
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            p.bootStamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            p.bootSeenAt = System.currentTimeMillis()
        }
        val inPeriod = Scheduler.inLockPeriod(p)
        val shouldLock = Scheduler.shouldLock(p)
        Log.i("SleepTime", "BootReceiver: $action enabled=${p.enabled} 时段内=$inPeriod 该锁=$shouldLock")

        if (!p.enabled) return

        // 1) 闹钟一定得重排 —— 重启后系统把它们全清了
        runCatching { Scheduler.arm(ctx) }
        // 1.2) 回礼：把外部「唤醒器」也叫一下（它是我们的生命线，互相保活；
        //      万一它被强行停止过，这条显式广播也能把它拉起来）
        runCatching {
            ctx.sendBroadcast(
                Intent("com.sleeptime.waker.WAKE").setComponent(
                    android.content.ComponentName(
                        "com.sleeptime.waker", "com.sleeptime.waker.BootReceiver"
                    )
                )
            )
        }

        // 1.5) 持久化任务也重排一次（正常由系统自己恢复，这里兜个底）
        runCatching { Scheduler.armGuardJob(ctx) }

        // 2) 拉起守护服务。服务第一次 tick 会自己再判一次该不该锁
        LockService.start(ctx)

        // 3) 正在睡眠时段内 -> 立刻把全屏界面顶上去，不等那 300ms
        if (shouldLock && Settings.canDrawOverlays(ctx)) {
            Log.i("SleepTime", "BootReceiver: 处于睡眠时段，立即锁定")
            LockActivity.launch(ctx)
        }
    }
}