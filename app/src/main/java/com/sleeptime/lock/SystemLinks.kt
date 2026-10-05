package com.sleeptime.lock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * ColorOS ROM entry points. Only public Intents are used here; the real
 * auto-start / power pages are signature-protected, so callers fall back to
 * Settings home or the app-details page.
 */
object SystemLinks {

    private const val PKG_BATTERY = "com.oplus.battery"
    private const val ACT_STARTUP = "com.oplus.startupapp.view.StartupAppListActivity"
    private const val ACT_POWER_CONTROL = "com.oplus.powermanager.fuelgaue.PowerControlActivity"
    private const val ACT_POWER_USAGE = "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
    private const val ACTION_STARTUP = "com.oplus.battery.permission.startup.StartupAppListActivity"
    private const val ACTION_POWER_CONTROL = "oplus.intent.action.power_control"

    fun jumpStartup(ctx: Context): String {
        val direct = Intent(ACTION_STARTUP)
            .setComponent(ComponentName(PKG_BATTERY, ACT_STARTUP))
        if (start(ctx, direct)) {
            return "已打开「自启动管理」：在列表里找到 SleepTime，把开关打开"
        }

        if (start(ctx, Intent(Settings.ACTION_SETTINGS))) {
            return "自启动页被 ColorOS 签名权限锁死，普通 App 无法直达。已打开系统设置，请进「应用」-「自启动」打开 SleepTime"
        }

        if (start(ctx, appDetails(ctx))) {
            return "自启动页被 ColorOS 签名权限锁死，普通 App 无法直达。已打开应用详情，请点里面的「自启动」"
        }
        return "跳转失败，请手动去 设置-应用-自启动"
    }

    fun jumpBackground(ctx: Context): String {
        val keys = arrayOf("package_name", "pkg_name", "android.intent.extra.PACKAGE_NAME")
        val direct = Intent()
            .setComponent(ComponentName(PKG_BATTERY, ACT_POWER_CONTROL))
            .also { i -> keys.forEach { i.putExtra(it, ctx.packageName) } }
        if (start(ctx, direct)) {
            return "已打开「耗电管理」：找到 SleepTime -> 选「允许完全后台行为」"
        }

        val usage = Intent(ACTION_POWER_CONTROL)
            .setComponent(ComponentName(PKG_BATTERY, ACT_POWER_USAGE))
            .also { i -> keys.forEach { i.putExtra(it, ctx.packageName) } }
        if (start(ctx, usage)) {
            return "已打开「耗电管理」：找到 SleepTime -> 选「允许完全后台行为」"
        }

        if (start(ctx, appDetails(ctx))) {
            return "耗电管理页被 ColorOS 权限锁死，普通 App 无法直达。已打开应用详情，请点「耗电管理」->「允许完全后台行为」"
        }
        return "跳转失败，请手动去 应用详情-耗电管理"
    }

    private fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    private fun start(ctx: Context, i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }
}
