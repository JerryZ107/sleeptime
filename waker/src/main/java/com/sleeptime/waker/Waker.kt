package com.sleeptime.waker

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 「微信平民版」。
 *
 * 微信靠 ROM 的推送常驻服务把它拉起来（HeyTap / HMS / MiPush…），普通 App 没这个待遇。
 * 那就自己养一个小的「唤醒器」：开机时、以及每 15 分钟，给 SleepTime 发一条**显式广播**。
 *
 * 为什么这条广播能救命：Android 只对**隐式广播**把 stopped 的包筛掉，
 * 显式 intent（指名到组件）照样投递，投递时系统还会顺手把 stopped 清回 false。
 * 所以哪怕 SleepTime 被「强行停止」过，这条广播也能把它叫醒（本机实测）。
 */
object Waker {

    const val TAG = "SleepTimeWaker"

    /** 目标：SleepTime 的唤醒入口（exported，专门留给外部叫醒用） */
    const val TARGET_PKG = "com.sleeptime.lock"
    const val TARGET_CLS = "com.sleeptime.lock.WakeReceiver"
    const val TARGET_ACTION = "com.sleeptime.lock.WAKE"

    /** 自己（给 SleepTime 反向叫醒用） */
    const val SELF_CLS = "com.sleeptime.waker.BootReceiver"
    const val SELF_ACTION = "com.sleeptime.waker.WAKE"

    private const val JOB_ID = 3001
    private const val RC_WATCHDOG = 3002
    private const val INTERVAL = 15 * 60 * 1000L
    private const val PREF = "waker"

    /** 叫一次 SleepTime，并记一笔账（界面上能看它到底干没干活） */
    fun wake(ctx: Context, reason: String): Boolean {
        val i = Intent(TARGET_ACTION).setComponent(ComponentName(TARGET_PKG, TARGET_CLS))
        val ok = runCatching { ctx.sendBroadcast(i) }.isSuccess
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit()
            .putLong("last_at", System.currentTimeMillis())
            .putInt("count", sp.getInt("count", 0) + 1)
            .putString("last_reason", reason)
            .putBoolean("last_ok", ok)
            .commit()
        Log.i(TAG, "wake($reason) ok=$ok")
        return ok
    }

    fun stats(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = sp.getInt("count", 0)
        if (n == 0) return "还没叫过"
        val at = sp.getLong("last_at", 0L)
        val fmt = java.text.SimpleDateFormat("M月d日 HH:mm:ss", java.util.Locale.CHINA)
        val ok = if (sp.getBoolean("last_ok", true)) "" else " · 发送失败"
        return "共叫醒 " + n + " 次，最近一次 " + fmt.format(java.util.Date(at)) +
            "（" + sp.getString("last_reason", "?") + ok + "）"
    }

    /** 15 分钟一次的持久化任务：重启后由系统自己重排，不依赖广播能不能到 */
    fun armJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        // 已经在排就什么都别做：正在跑的时候再 schedule 一次，
        // 系统会立刻重投，直接变成每秒几百次的死循环（踩过一次）
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WakeJobService::class.java))
            .setPersisted(true)
            .setPeriodic(INTERVAL)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { js.schedule(job) }.onFailure { Log.e(TAG, "armJob 失败", it) }
    }

    /** 15 分钟一次的看门狗闹钟：进程被杀了也能把自己叫回来，再顺手去叫 SleepTime */
    fun armWatchdog(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, RC_WATCHDOG,
            Intent(ctx, BootReceiver::class.java).setAction(SELF_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + INTERVAL
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) } }
    }
}