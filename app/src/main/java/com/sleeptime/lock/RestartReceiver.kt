package com.sleeptime.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 唯一的自愈入口。被三件事触发：
 *   1. 看门狗循环闹钟（主力 —— 能穿过进程死亡）
 *   2. onTaskRemoved 后的 1.2 秒急救闹钟（从最近任务划掉时）
 *   3. onDestroy 后的 1.5 秒急救闹钟（优雅结束时）
 *
 * 不管谁把它叫起来，都做同样三件事：重排每日闹钟、接力排下一次看门狗、把守护服务拉起来。
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = Prefs(ctx)
        if (!p.enabled) return

        val shouldLock = Scheduler.shouldLock(p)
        Log.i("SleepTime", "RestartReceiver: 自愈触发 该锁=$shouldLock")

        // 1) 每日闹钟绝不能丢
        runCatching { Scheduler.arm(ctx) }
        // 2) 接力排下一次看门狗（这条链只要接上就永不断）
        runCatching { Scheduler.armWatchdog(ctx) }
        // 2.5) 戳一下外部「唤醒器」：它是我们的生命线，别让它被系统晾死
        //      （显式广播，即使它被强行停止过也能拉起来；它醒来会回敬我们一次唤醒，不形成死循环）
        runCatching {
            ctx.sendBroadcast(
                Intent("com.sleeptime.waker.WAKE").setComponent(
                    android.content.ComponentName(
                        "com.sleeptime.waker", "com.sleeptime.waker.BootReceiver"
                    )
                )
            )
        }

        // 3) 拉起守护服务。它第一次 tick 会自己判断该不该锁
        LockService.start(ctx)
    }
}