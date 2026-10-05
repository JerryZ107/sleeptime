package com.sleeptime.lock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Manifest fallback in the same app.
 *
 * It unmutes immediately even when LockService is not running, then wakes the
 * service only when that is actually useful.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != "android.intent.action.PHONE_STATE") return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val active = state == TelephonyManager.EXTRA_STATE_RINGING ||
            state == TelephonyManager.EXTRA_STATE_OFFHOOK
        Log.i("SleepTime", "PhoneStateReceiver state=$state active=$active")

        val p = Prefs(ctx)
        if (active) {
            LockService.onCallActive(ctx)
            if (p.enabled) LockService.start(ctx)
            return
        }

        LockService.onCallIdle(ctx)
        if (!p.enabled) {
            LockService.unmuteNow(ctx)
            return
        }

        // IDLE is noisy; only restart the service if it may need to restore a lock.
        if (LockService.running || p.lockedAt > 0L || Scheduler.shouldLock(p)) {
            LockService.start(ctx)
        }
    }
}
