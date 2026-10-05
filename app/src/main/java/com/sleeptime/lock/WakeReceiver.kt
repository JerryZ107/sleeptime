package com.sleeptime.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * External wake entry. Explicit broadcasts can pass through the stopped state
 * on Android, so Waker uses this receiver to revive SleepTime and to notify it
 * about call state changes.
 */
class WakeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CALL_START = "com.sleeptime.lock.CALL_START"
        const val ACTION_CALL_END = "com.sleeptime.lock.CALL_END"
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = Prefs(ctx)
        val action = intent?.action
        Log.i(
            "SleepTime",
            "WakeReceiver action=$action enabled=${p.enabled} shouldLock=${Scheduler.shouldLock(p)}"
        )

        when (action) {
            ACTION_CALL_START -> {
                LockService.onExternalCallStart(ctx)
                if (p.enabled) {
                    runCatching { Scheduler.arm(ctx) }
                    runCatching { Scheduler.armGuardJob(ctx) }
                    LockService.start(ctx)
                }
                return
            }
            ACTION_CALL_END -> {
                LockService.onExternalCallEnd(ctx)
                if (!p.enabled) {
                    LockService.unmuteNow(ctx)
                    return
                }
                LockService.start(ctx)
                return
            }
        }

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
