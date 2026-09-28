package com.sleeptime.lock

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ColorOS / realme UI（OPPO 系）那两页系统设置：怎么跳过去、怎么自检。
 *
 * 本机实测（realme RMX5010 / ColorOS 15）：
 *   · 自启动管理 = com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity
 *   · 耗电管理   = com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity
 *   这两个页面（连配套的 StartupProvider）都被 oplus.permission.OPLUS_COMPONENT_SAFE
 *   （signature 级签名权限）守着 —— 第三方 App 一 startActivity 就抛 SecurityException，
 *   adb shell am start 也照样被拒（uid 2000 同样没这个权限）。所以别指望硬跳。
 *
 * 「直达」= 降级链：先试原生页面（换 ROM / 以后放开权限就能成），
 * 被拦就退到「应用详情」页 —— 那一页里正好摆着「耗电管理」入口，用户再点一下就到。
 *
 * 「自检」= 实测反推，因为系统什么查询接口都没给：
 *   · 自启动   → 这次开机到底有没有收到 BOOT_COMPLETED（BootReceiver 盖的开机戳）
 *   · 后台运行 → 守护服务心跳有没有断档（真被冻过一定记着）+ 系统待机分桶
 */
object SystemLinks {

    private const val PKG_BATTERY = "com.oplus.battery"
    private const val ACT_STARTUP = "com.oplus.startupapp.view.StartupAppListActivity"
    private const val ACT_POWER_CONTROL = "com.oplus.powermanager.fuelgaue.PowerControlActivity"
    private const val ACT_POWER_USAGE = "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
    private const val ACTION_STARTUP = "com.oplus.battery.permission.startup.StartupAppListActivity"
    private const val ACTION_POWER_CONTROL = "oplus.intent.action.power_control"

    /** 开机戳容差：5 分钟（中途改系统时间也只在这个范围内抖） */
    private const val BOOT_TOLERANCE_MS = 5 * 60 * 1000L

    /** 冻结记录保鲜期：7 天。太久以前的证据不算数，免得冤案 */
    private const val FREEZE_FRESH_MS = 7L * 24 * 3600 * 1000

    const val OK = 1
    const val UNKNOWN = 0
    const val BAD = -1

    /** 一行自检的结论：state 决定颜色，text 是给人看的话 */
    data class Check(val state: Int, val text: String)

    // ---------------------------------------------------------------- 直达

    /** 直达「自启动管理」。返回一句给 toast 用的话。 */
    fun jumpStartup(ctx: Context): String {
        val direct = Intent(ACTION_STARTUP)
            .setComponent(ComponentName(PKG_BATTERY, ACT_STARTUP))
        if (start(ctx, direct)) {
            return "已打开「自启动管理」：在列表里找到 SleepTime，把开关打开"
        }
        if (start(ctx, appDetails(ctx))) {
            return "「自启动管理」被 ColorOS 的签名权限锁着，第三方 App 跳不进去。" +
                "已打开本应用的应用详情页 —— 自启动要回 设置 → 应用 → 自启动，列表里搜 SleepTime"
        }
        return "跳转失败，请手动走 设置 → 应用 → 自启动 → SleepTime"
    }

    /** 直达「耗电管理」（后台管理 → 允许完全后台行为）。返回一句给 toast 用的话。 */
    fun jumpBackground(ctx: Context): String {
        val keys = arrayOf("package_name", "pkg_name", "android.intent.extra.PACKAGE_NAME")
        val direct = Intent()
            .setComponent(ComponentName(PKG_BATTERY, ACT_POWER_CONTROL))
            .also { i -> keys.forEach { i.putExtra(it, ctx.packageName) } }
        if (start(ctx, direct)) {
            return "已打开「耗电管理」：找到 SleepTime → 选「允许完全后台行为」"
        }
        val usage = Intent(ACTION_POWER_CONTROL)
            .setComponent(ComponentName(PKG_BATTERY, ACT_POWER_USAGE))
            .also { i -> keys.forEach { i.putExtra(it, ctx.packageName) } }
        if (start(ctx, usage)) {
            return "已打开「耗电管理」：找到 SleepTime → 选「允许完全后台行为」"
        }
        if (start(ctx, appDetails(ctx))) {
            return "ColorOS 不让第三方 App 直跳「耗电管理」。已打开应用详情页：" +
                "点列表里的「耗电管理」→ 选「允许完全后台行为」"
        }
        return "跳转失败，请手动走 设置 → 应用 → SleepTime → 耗电管理"
    }

    private fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    /**
     * 真跳。SecurityException（签名权限拦）和 ActivityNotFoundException（ROM 没这页）
     * 都在这里吃掉 —— 返回 false 就换下一条路。
     */
    private fun start(ctx: Context, i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    // ---------------------------------------------------------------- 自检

    /**
     * 自启动自检：只看一件事 —— 这次开机，系统到底有没有把 BOOT_COMPLETED 送进来。
     * 开关关着的话广播根本不会到本 App，那个戳就永远停在上一回开机。
     * 判据：开机戳（由 wall clock 反推的开机时刻）与「这一回开机时刻」是否吻合。
     */
    fun checkStartup(ctx: Context, p: Prefs): Check {
        val thisBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val marked = p.bootStamp
        val seen = p.bootSeenAt
        val installedAt = installedAt(ctx)
        return when {
            marked > 0L && kotlin.math.abs(marked - thisBoot) <= BOOT_TOLERANCE_MS ->
                Check(OK, "实测通过：这次开机（" + stamp(seen) + "）系统把开机广播送到了本 App，自启动是开着的")

            installedAt > thisBoot ->
                Check(UNKNOWN, "本次开机之后才装/更新过，还测不出来 —— 重启一次手机，再回来看这行")

            marked == 0L ->
                Check(UNKNOWN, "还没重启过手机，测不出来 —— 重启后回来看这行（没开的话开机广播会被系统吞掉）")

            else ->
                Check(BAD, "这次开机没收到开机广播（上一次收到是 " + stamp(seen) +
                    "）—— 要么自启动被拦，要么 App 被「强行停止」/闲置休眠过。" +
                    "去应用详情把「管理闲置应用」的休眠关掉，再打开一次本 App")
        }
    }

    /**
     * 后台运行（「允许完全后台行为」）自检：系统同样没有查询接口，只能拿两条
     * 观测到的证据反推，两条都是硬的：
     *   1) 守护服务心跳断档 —— 真被冻过就一定会记下来（最直接）
     *   2) 系统待机分桶 —— EXEMPTED/ACTIVE 说明没被压制，RARE 及以下就要当心
     */
    fun checkBackground(ctx: Context, p: Prefs): Check {
        val bucket = standbyBucket(ctx)
        // 最直接的证据：闹钟排好了、到点了，却没锁上 —— 那就是系统把 App 按住了
        val missed = p.enabled && p.pendingTrigger > 0L &&
            p.pendingTrigger < System.currentTimeMillis() - 90_000L &&
            p.lastLockAt < p.pendingTrigger
        val frozenFresh = p.freezeCount > 0 &&
            p.lastFreezeAt > 0L &&
            System.currentTimeMillis() - p.lastFreezeAt < FREEZE_FRESH_MS
        return when {
            missed ->
                Check(BAD, "预定 " + stamp(p.pendingTrigger) + " 那次锁定没锁上（App 被系统按住/冻住了）——" +
                    " 这是「到点不锁」最硬的证据。去「耗电管理」把「允许完全后台行为」打开，再点「直达」确认一遍")

            frozenFresh ->
                Check(BAD, "实测被系统冻结过 " + p.freezeCount + " 次（最长 " +
                    (p.maxFreezeMs / 1000) + " 秒，最近 " + stamp(p.lastFreezeAt) +
                    "）—— 这条是守护服务心跳记下的，说明「允许完全后台行为」没生效")

            // 老版本记下的冻结（没有时间戳）分不清是开之前还是开之后 —— 不冤判，也不放过
            p.freezeCount > 0 && p.lastFreezeAt <= 0L ->
                Check(UNKNOWN, "待机分桶 = " + bucketName(bucket) + "（没被压制）；但还留着 " +
                    p.freezeCount + " 次升级前记的旧冻结（最长 " + (p.maxFreezeMs / 1000) +
                    " 秒），分不清是开之前还是开之后 —— 点「自检」可以清掉旧记录重新实测")

            bucket >= 40 ->
                Check(BAD, "系统待机分桶 = " + bucketName(bucket) +
                    "，后台会被压制 —— 去「耗电管理」里选「允许完全后台行为」")

            bucket in 1..10 -> {
                val last = if (p.lastLockAt > 0L)
                    "最近一次到点锁定（" + stamp(p.lastLockAt) + "）是准的"
                else
                    "还没攒出「到点锁定」的记录"
                Check(OK, "待机分桶 = " + bucketName(bucket) + "（没被压制），" + last +
                    " —— 合起来看是允许的")
            }

            else ->
                Check(UNKNOWN, "暂时没抓到被冻结的证据，待机分桶 = " + bucketName(bucket) +
                    " —— 这一项只能靠实测：睡一晚、明早回来看这行有没有变红")
        }
    }

    /** 装机/升级时间。晚于本次开机 = 本次开机不可能收到开机广播，别急着判死刑 */
    private fun installedAt(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        maxOf(pi.firstInstallTime, pi.lastUpdateTime)
    }.getOrDefault(0L)

    private fun standbyBucket(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        return runCatching {
            ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        }.getOrDefault(-1)
    }

    private fun bucketName(b: Int) = when (b) {
        5 -> "EXEMPTED（豁免）"
        10 -> "ACTIVE（活跃）"
        20 -> "WORKING_SET（常用）"
        30 -> "FREQUENT（偶尔用）"
        40 -> "RARE（很少用）"
        45 -> "RESTRICTED（受限）"
        50 -> "NEVER（从不）"
        else -> "查不到（$b）"
    }

    private fun stamp(ms: Long): String {
        if (ms <= 0L) return "从没记过"
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))
    }
}