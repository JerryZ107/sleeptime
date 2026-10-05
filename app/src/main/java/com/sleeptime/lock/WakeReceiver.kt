package com.sleeptime.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * External wake entry. Explicit broadcasts can pass through the stopped state
 * on Android, so adb / automation can revive SleepTime with:
 *   am broadcast -a com.sleeptime.lock.WAKE -n com.sleeptime.lock/.WakeReceiver
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = Prefs(ctx)
        Log.i(
            "SleepTime",
            "WakeReceiver action=${intent?.action} enabled=${p.enabled} shouldLock=${Scheduler.shouldLock(p)}"
        )

        if (!p.enabled) {
            LockService.unmuteNow(ctx)
            return
        }

        runCatching { Scheduler.arm(ctx) }
        runCatching { Scheduler.armGuardJob(ctx) }
        LockService.start(ctx)
        if (Scheduler.shouldLock(p) && Settings.canDrawOverlays(ctx)) {
            LockActivity.launch(ctx)
        }
    }
}
