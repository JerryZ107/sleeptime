@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package com.sleeptime.lock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * In-process phone state monitor used while LockService is alive.
 * PhoneStateReceiver remains the fallback when the service is not running.
 */
object CallStateMonitor {

    private var appContext: Context? = null
    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    private var lastActive: Boolean? = null

    fun start(ctx: Context) {
        if (!hasPermission(ctx)) return
        appContext = ctx.applicationContext
        stopInternal(ctx)
        lastActive = null

        runCatching {
            val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        onState(state)
                    }
                }
                telephonyCallback = callback
                tm.registerTelephonyCallback(ctx.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onState(state)
                    }
                }
                phoneStateListener = listener
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        }
    }

    fun stop(ctx: Context) {
        stopInternal(ctx)
        appContext = null
        lastActive = null
    }

    private fun stopInternal(ctx: Context) {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            phoneStateListener = null
        }
    }

    private fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun onState(state: Int) {
        val ctx = appContext ?: return
        val active = state != TelephonyManager.CALL_STATE_IDLE
        if (active == lastActive) return
        lastActive = active
        if (active) {
            LockService.onCallActive(ctx)
        } else {
            LockService.onCallIdle(ctx)
        }
    }
}
