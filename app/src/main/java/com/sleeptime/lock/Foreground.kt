package com.sleeptime.lock

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 「现在前台是哪个 App」。
 *
 * 用途：锁屏上的电话按钮把系统拨号盘放出来之后，要盯着前台 ——
 * 只要前台还是电话/拨号盘就继续让路，**一旦离开就立刻把锁放回来**。
 *
 * 拿前台应用只有两条路：
 *   1) 使用情况访问（`android:get_usage_stats` appop，用户去设置里点一下，免无障碍）；
 *   2) `getRunningAppProcesses()` 兜底 —— Android 5 之后对第三方基本只返回自己，
 *      但有些 ROM 会给全量，试一下不亏。
 * 两条都拿不到就返回 null，调用方退回「保守让路 90 秒」。
 */
object Foreground {

    /** 电话相关的包：拨号盘 / 通话界面。ColorOS 上拨号盘就是 com.android.contacts */
    private val PHONE_PKGS = setOf(
        "com.android.contacts",      // ColorOS 拨号盘（DialtactsActivityAlias）
        "com.android.dialer",
        "com.android.incallui",      // 通话界面
        "com.android.server.telecom",
        "com.android.phone",
        "com.coloros.contacts",
        "com.oplus.contacts"
    )

    fun isPhoneApp(pkg: String?): Boolean = pkg != null && pkg in PHONE_PKGS

    /** 使用情况访问有没有授权（appop GET_USAGE_STATS） */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 最近一个被切到前台的应用包名；拿不到返回 null */
    @Suppress("DEPRECATION")
    fun packageName(ctx: Context): String? {
        if (hasUsageAccess(ctx)) {
            runCatching {
                val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return@runCatching
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 10 * 60_000L, now)
                var last: String? = null
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
                }
                if (last != null) return last
            }.onFailure { android.util.Log.w("SleepTime", "queryEvents 失败", it) }
        }
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
            return am.runningAppProcesses
                ?.firstOrNull { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
                ?.processName
        }
        return null
    }
}