package com.sleeptime.waker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 唤醒器的入口：开机 / 被系统拉起 / 被 SleepTime 反向叫醒时，
 * 都做同一件事 —— 去把 SleepTime 叫起来，然后把自己那两条命续上。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(Waker.TAG, "onReceive " + action)
        Waker.wake(ctx, action ?: "unknown")
        Waker.armJob(ctx)
        Waker.armWatchdog(ctx)
    }
}