package com.sleeptime.lock

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object Scheduler {

    const val EXTRA_BACKUP = "backup"

    private const val TAG = "SleepTime"
    private const val RC = 1001
    private const val RC_HEAL = 1002
    private const val RC_BACKUP = 1003
    private const val RC_WATCHDOG = 1004
    private const val RC_PREWARM = 1005

    /** 预热：锁定时刻前 2 分钟先把服务叫醒，免得到点那一刻才冷启动 */
    private const val PREWARM_MS = 2 * 60_000L

    /** 持久化任务（JobScheduler）。重启后系统自己重排，是闹钟之外的第二条命 */
    const val JOB_ID = 2001
    private const val DAY = 24 * 60 * 60 * 1000L

    private const val BACKUP_DELAY = 90_000L

    fun todayTriggerMillis(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val t = todayTriggerMillis(hour, minute)
        return if (t <= System.currentTimeMillis()) t + DAY else t
    }

    /**
     * 现在是否处于每日锁定时段内。
     * 支持跨午夜：锁定 23:30 / 解除 07:00 时，凌晨 3 点算「在时段内」。
     */
    fun inLockPeriod(p: Prefs): Boolean {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val lock = p.hour * 60 + p.minute
        val unlock = p.unlockHour * 60 + p.unlockMinute
        if (lock == unlock) return false
        return if (lock < unlock) now >= lock && now < unlock
        else now >= lock || now < unlock
    }

    /** 当前这个锁定时段的起点时间戳（跨午夜时会是「昨天」的那个点） */
    fun currentPeriodStart(p: Prefs): Long {
        val today = todayTriggerMillis(p.hour, p.minute)
        return if (System.currentTimeMillis() >= today) today else today - DAY
    }

    /**
     * 现在该不该处于锁定态：
     *   在锁定时段内，且本时段还没被手动解除过。
     */
    fun shouldLock(p: Prefs): Boolean {
        if (!p.enabled) return false
        if (!inLockPeriod(p)) return false
        return p.lastUnlockAt < currentPeriodStart(p)
    }

    /** 排下一次锁定：主闹钟 @ 锁定时刻，备份闹钟 @ +90s */
    fun arm(ctx: Context) {
        armInternal(ctx, withBackup = true)
    }

    /** 主闹钟刚触发时用：只重排明天的主闹钟，别把今天还没开的备份冲掉 */
    fun armAfterFire(ctx: Context) {
        armInternal(ctx, withBackup = false)
    }

    private fun armInternal(ctx: Context, withBackup: Boolean) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = nextTriggerMillis(p.hour, p.minute)

        Log.i(TAG, "armInternal at=$at enabled=" + p.enabled)
        armOne(am, at, pending(ctx, RC, false), primary = true, tag = "主")
        if (withBackup) {
            armOne(am, at - PREWARM_MS, prewarmPending(ctx), primary = false, tag = "预热")
            armOne(am, at + BACKUP_DELAY, pending(ctx, RC_BACKUP, true), primary = false, tag = "备")
        }
    }

    private fun armOne(am: AlarmManager, at: Long, pi: PendingIntent, primary: Boolean, tag: String) {
        val first = runCatching {
            if (primary && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
        if (first.isSuccess) {
            Log.i(TAG, "arm-$tag OK @$at")
            return
        }
        val second = runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        if (second.isSuccess) {
            Log.i(TAG, "arm-$tag 降级为 setExactAndAllowWhileIdle @$at")
            return
        }
        val third = runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        if (third.isSuccess) {
            Log.i(TAG, "arm-$tag 降级为不精确闹钟 @$at")
        } else {
            Log.e(TAG, "arm-$tag 全部失败", first.exceptionOrNull() ?: second.exceptionOrNull() ?: third.exceptionOrNull())
        }
    }

    /**
     * 看门狗：定期自检。
     *
     * 为什么需要它：ColorOS 在后台杀进程走的是 SIGKILL，
     * onTaskRemoved / onDestroy 一个都不会回调 —— 那两条自愈路径直接失效。
     * 只有系统闹钟能穿过进程死亡，所以用一条循环闹钟当兜底：
     *   醒来 -> 重排每日闹钟 -> 该锁就把服务拉起来 -> 再排下一次。
     *
     * 锁定时段内 2 分钟一次（保证掉线后最快恢复），平时 10 分钟一次。
     */
    fun watchdogDelayMs(p: Prefs): Long =
        if (inLockPeriod(p)) 2 * 60_000L else 10 * 60_000L

    fun armWatchdog(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.enabled) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val delay = watchdogDelayMs(p)
        val at = System.currentTimeMillis() + delay
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogPending(ctx)) }
            .onFailure { runCatching { am.set(AlarmManager.RTC_WAKEUP, at, watchdogPending(ctx)) } }
        Log.i(TAG, "watchdog armed in ${delay / 1000}s")
    }

    private fun watchdogPending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RC_WATCHDOG, Intent(ctx, RestartReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 预热闹钟：走的是自愈入口，效果就是「提前把服务拉起来」 */
    private fun prewarmPending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RC_PREWARM, Intent(ctx, RestartReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 15 分钟一次的持久化 Job（setPersisted=true）。
     * 为什么还要它：闹钟归 AlarmManager 管，被 ROM 按住会迟到；JobScheduler 归系统管，
     * 重启后由系统自己重排 —— App 被杀、开机广播没收到，它照样能把我们叫回来。
     */
    fun armGuardJob(ctx: Context) {
        val p = Prefs(ctx)
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!p.enabled) {
            runCatching { js.cancel(JOB_ID) }
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, GuardJobService::class.java))
            .setPersisted(true)                 // 重启后系统自动重排
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { js.schedule(job) }
            .onFailure { Log.e(TAG, "armGuardJob 失败", it) }
    }

    fun cancelGuardJob(ctx: Context) {
        runCatching { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pending(ctx, RC, false)) }
        runCatching { am.cancel(pending(ctx, RC_BACKUP, true)) }
        runCatching { am.cancel(watchdogPending(ctx)) }
        runCatching { am.cancel(prewarmPending(ctx)) }
    }

    /** 自愈：过 delayMs 把自己拉回来 */
    fun selfHeal(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, RC_HEAL, Intent(ctx, RestartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + delayMs
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) } }
    }

    private fun pending(ctx: Context, rc: Int, backup: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        if (backup) i.putExtra(EXTRA_BACKUP, true)
        return PendingIntent.getBroadcast(
            ctx, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}