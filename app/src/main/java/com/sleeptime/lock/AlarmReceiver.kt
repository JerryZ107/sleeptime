package com.sleeptime.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 到点触发。
 *   主闹钟 @ 锁定时刻        -> 把守护服务拉起来（该不该锁由服务自己判断）
 *   备份闹钟 @ 锁定时刻 +90s -> 只在「应该锁但没锁上」时补刀
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = Prefs(ctx)
        if (!p.enabled) {
            LockService.unmuteNow(ctx)
            return
        }

        val isBackup = intent?.getBooleanExtra(Scheduler.EXTRA_BACKUP, false) == true
        Log.i("SleepTime", "AlarmReceiver 触发 isBackup=$isBackup 该锁=${Scheduler.shouldLock(p)}")

        if (isBackup && !Scheduler.shouldLock(p)) {
            Log.i("SleepTime", "备份闹钟: 本周期已解除或不在时段内，跳过")
            Scheduler.arm(ctx)
            return
        }

        // 到点就直接把锁抬起来，不再只押注「服务能起来并 tick 到」：
        // 闹钟广播自带 10 秒的临时白名单，此刻启动 Activity 是允许的；
        // 就算被系统拦下，下面的悬浮窗（SYSTEM_ALERT_WINDOW）照样铺满屏幕。
        if (Scheduler.shouldLock(p)) {
            LockActivity.launch(ctx)
        }
        LockService.start(ctx)

        if (isBackup) Scheduler.arm(ctx) else Scheduler.armAfterFire(ctx)
    }
}