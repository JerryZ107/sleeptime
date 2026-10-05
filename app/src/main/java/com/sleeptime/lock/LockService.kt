package com.sleeptime.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 常驻守护服务。两种状态：
 *
 *   守护态：只挂一个前台通知，每秒判断「到点了没」。
 *   锁定态：压黑幕 + 全屏 Activity + 屏幕固定 + 看门狗。
 *
 * 为什么常驻：实测这台 realme/ColorOS 会把退到后台的 App 冻结，连带压住系统闹钟的投递
 * （实测被压了 4 分钟）。常驻前台服务不会被冻，到点由服务自己判断。
 * 系统闹钟仍然保留，作为「服务没活着时」的第二道保险。
 */
class LockService : Service() {

    companion object {
        private const val TAG = "SleepTime"
        private const val CHANNEL_ID = "sleeptime_guard"
        private const val NOTI_ID = 0x5EE9

        /**
         * 诊断日志总开关。改成 false 就彻底不再写文件。
         * 为什么要留着：这台 ROM 会屏蔽后台应用的 logcat，这个文件是唯一能看进去的窗口。
         * 只在「上锁/解锁/被冻结/自愈/异常」这类事件发生时才写，不再每 tick 写。
         */
        private const val TRACE_ENABLED = true

        /** 常态（没在锁）时主循环多久跑一次。1 秒纯属浪费：息屏时设备本来就在睡，亮屏时 2 秒也够用 */
        private const val IDLE_TICK_MS = 2000L



        @Volatile private var live: LockService? = null
        @Volatile private var lastHealAt = 0L

        /**
         * Safety hold. A normal call is shorter than this; if the IDLE broadcast
         * is lost, it is safer to leave the mic open than to re-mute during a call.
         */
        private const val CALL_HOLD_MS = 6 * 60 * 60_000L

        private fun unmuteMic(ctx: Context) {
            runCatching { ctx.getSystemService(AudioManager::class.java).isMicrophoneMute = false }
        }

        fun unmuteNow(ctx: Context) {
            unmuteMic(ctx)
            live?.forceUnmuteMic()
        }

        fun onCallActive(ctx: Context) {
            Prefs(ctx).callHoldUntil = System.currentTimeMillis() + CALL_HOLD_MS
            unmuteNow(ctx)
        }

        fun onCallIdle(ctx: Context) {
            Prefs(ctx).callHoldUntil = 0L
        }

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val r = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LockService::class.java)) }
            Log.i(TAG, "LockService.start " + (if (r.isSuccess) "OK" else "失败: " + r.exceptionOrNull()))
        }

        /** 手动/测试触发：立刻上锁，无视当前时间。服务没起来也没关系，第一次 tick 会消费它 */
        @Volatile
        private var forceLockRequested = false




        fun lockNow(ctx: Context) {
            Prefs(ctx).lastUnlockAt = 0L
            forceLockRequested = true
            // 落盘：测试锁要能顶住「不在锁定时段」，而且进程被杀/重启后还得记得
            Prefs(ctx).manualHold = true
            start(ctx)
            val svc = live
            if (svc != null) svc.h.postDelayed({ svc.consumeForceLock() }, 150L)
        }

        fun stop(ctx: Context) {
            unmuteNow(ctx)
            runCatching { ctx.stopService(Intent(ctx, LockService::class.java)) }
        }

        /** 解除本次锁定：本周期内不再锁，守护服务继续活着 */
        fun unlock(ctx: Context) {
            Prefs(ctx).lastUnlockAt = System.currentTimeMillis()
            Prefs(ctx).manualHold = false
            LockActivity.close()
            live?.exitLock()
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager

    private var overlay: SleepLockView? = null
    private var locked = false
    private var stopped = false
    private var lastPullAt = 0L
    /** 只用来算心跳落盘间隔（每 15 秒一次） */
    private var tickCount = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    /** 长按计时状态，跨悬浮窗重建保留 */
    private val holdState = HoldState()
    private var unfocusedTicks = 0
    private var lastReassertAt = 0L
    /** 本次锁定已经抢了多少次（上限 60，进锁时清零） */
    private var reassertTries = 0

    /** 麦克风是否处于我们设置的静音态 */
    private var micMuted = false

    private val h = Handler(Looper.getMainLooper())




    private val ticker = object : Runnable {
        override fun run() {
            if (stopped) return
            tickCount++
            runCatching { tick() }.onFailure { trace("tick 异常: $it") }
            h.postDelayed(this, if (locked) 1000L else IDLE_TICK_MS)
        }
    }

    /** 状态写文件 —— 绕过 ColorOS 对后台应用日志的屏蔽 */
    private fun trace(msg: String) {
        if (!TRACE_ENABLED) return
        if (::prefs.isInitialized && !prefs.traceEnabled) return
        runCatching {
            val f = File(filesDir, "trace.log")
            if (f.length() > 64_000L) f.delete()   // 只留事件日志，64KB 够用很久
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        running = true
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nm = getSystemService(NotificationManager::class.java)
        trace("onCreate canDraw=" + Settings.canDrawOverlays(this))
        // 立刻落一个心跳基线，否则进程秒死时连基线都没有
        prefs.lastTickAt = System.currentTimeMillis()
        // 清掉可能残留的静音态（上次被 force-stop 硬杀时会留下）。
        // 如果这次该锁，tick 会在 300ms 内重新静音。
        runCatching { getSystemService(AudioManager::class.java).isMicrophoneMute = false }
        micMuted = false
        // 常驻 partial wake lock 已经拆掉（实测没用，还挨了「异常耗电」的通报）：
        //   · 24 小时不放，设备续航直接崩，ColorOS 照样按 10 分钟一轮冻我们；
        //   · 现在改成「上锁时短暂持有几分钟」，靠 acquire(超时) 自动释放，见 holdWakeLockBriefly()。
        createChannel()
        goForeground()
        runCatching { CallStateMonitor.start(this) }.onFailure { trace("CallStateMonitor.start failed: $it") }
        runCatching { Scheduler.arm(this) }
        runCatching { Scheduler.armWatchdog(this) }   // 看门狗第一环
        h.postDelayed(ticker, 300L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        trace("onTaskRemoved")
        scheduleHeal(1200L)
    }

    override fun onDestroy() {
        trace("onDestroy stopped=$stopped")
        if (!stopped) scheduleHeal(1500L)
        stopped = true
        running = false
        live = null
        h.removeCallbacksAndMessages(null)
        runCatching { CallStateMonitor.stop(this) }.onFailure { trace("CallStateMonitor.stop failed: $it") }
        removeOverlay()
        runCatching { wakeLock?.release() }
        // 兜底：绝不能把麦克风留在静音态
        runCatching { getSystemService(AudioManager::class.java).isMicrophoneMute = false }
        micMuted = false
        wakeLock = null
        super.onDestroy()
    }

    private fun scheduleHeal(delayMs: Long) {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHealAt < 5000L) return
        lastHealAt = now
        runCatching { Scheduler.selfHeal(this, delayMs) }
    }

    private fun exitLock() {
        trace("exitLock (was locked=$locked)")
        prefs.lockedAt = 0L
        forceUnmuteMic()
        locked = false
        unfocusedTicks = 0
        removeOverlay()
        updateNotification()
    }

    // ------------------------------------------------------------- 主循环

    private fun tick() {
        detectFreeze()

        val inCall = CallGuard.detect(this) || isCallHoldActive()
        CallGuard.update(inCall)
        if (inCall) {
            // Release the global microphone mute during any call, regardless of allowCalls.
            forceUnmuteMic()
            if (prefs.enabled && prefs.allowCalls) {
                if (overlay != null) removeOverlay()
                if (LockActivity.visible) LockActivity.instance?.stepAsideForCall()
            }
            return
        }

        if (!prefs.enabled) {
            forceUnmuteMic()
            return
        }

        val shouldLock = Scheduler.shouldLock(prefs)


        // 到达解除时刻（或已不在时段内）-> 自动放行（手动测试锁不受此限）
        if (locked && !prefs.manualHold && !Scheduler.inLockPeriod(prefs)) {
            trace("到达解除时刻，自动放行")
            locked = false
            prefs.lockedAt = 0L
            unfocusedTicks = 0
            removeOverlay()
            LockActivity.close()
            forceUnmuteMic()
            updateNotification()
            return
        }

        // 到点就锁；lockNow 手动触发时无视时间；
        // 进程被杀又重启时，只要"本来锁着 + 手动测试锁"也把锁补回来
        if (!locked && (forceLockRequested || shouldLock || (prefs.manualHold && prefs.lockedAt > 0L))) {
            forceLockRequested = false
            enterLock()
            return
        }

        if (!locked) return

        setMicMuted(true)   // 锁定态：确保麦克风是静音的（幂等）

        val ov = overlay
        if (ov == null) {
            addOverlay()
        } else if (!ov.hasWindowFocus()) {
            // 被别的同层悬浮窗压住了（小布助手同为 type=2038 / layer=111000）。
            // 同层窗口谁后加谁在上面 -> 把自己摘掉再挂一次就能抢回顶层。
            unfocusedTicks++
            val nowMs = SystemClock.elapsedRealtime()
            // 连续 2 秒没焦点 = 确实有人压在我们上面 -> 抢回来。
            // 4 秒冷却，最多抢 60 次/次锁定，防止有人恶意刷导致耗电。
            if (unfocusedTicks >= 2 && nowMs - lastReassertAt > 4000L && reassertTries < 60) {
                lastReassertAt = nowMs
                reassertTries++
                unfocusedTicks = 0
                trace("检测到上层有窗口，第 $reassertTries 次抢回")
                reassertOverlay()
            }
        } else {
            unfocusedTicks = 0
        }

        if (!LockActivity.visible) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPullAt > 1200L) {
                lastPullAt = now
                LockActivity.launch(this)
            }
        }
    }

    /**
     * 只在「刚上锁」的几分钟里持一下 partial wake lock —— 帮悬浮窗和全屏 Activity 站稳。
     * acquire(超时) 让系统到点自己放掉，永远不会出现「忘了 release、整晚常亮」。
     */
    private fun holdWakeLockBriefly(ms: Long = 3 * 60_000L) {
        runCatching { wakeLock?.release() }
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTime:lock")
            wl.setReferenceCounted(false)
            wl.acquire(ms)
            wakeLock = wl
            trace("wakeLock 短暂持有 " + (ms / 1000) + "s")
        }.onFailure { trace("wakeLock 获取失败: $it") }
    }

    private fun setMicMuted(mute: Boolean) {
        val target = mute && prefs.micMute
        if (micMuted == target) return
        applyMicMuted(target)
    }

    /** Force the global microphone to unmuted and sync the local flag. */
    private fun forceUnmuteMic() {
        applyMicMuted(false)
    }

    /** Uses the existing Prefs instance; no SharedPreferences churn on every tick. */
    private fun isCallHoldActive(): Boolean =
        prefs.callHoldUntil > System.currentTimeMillis()

    private fun applyMicMuted(target: Boolean) {
        runCatching {
            getSystemService(AudioManager::class.java).isMicrophoneMute = target
            micMuted = target
            trace("mic mute = $target")
        }.onFailure { trace("setMicrophoneMute failed: $it") }
    }

    private fun consumeForceLock() {
        if (forceLockRequested && !locked) {
            forceLockRequested = false
            enterLock()
        }
    }

    /**
     * 自检：两次心跳之间断档太久 = 进程被 ROM 按住了。
     *
     * 只统计「屏幕亮着」时的断档 —— 息屏时设备本来就该睡，那不是冻结。
     * 老版本把「关机睡觉」也算成「被系统冻结」，误报了一堆。
     */
    private fun detectFreeze() {
        val now = System.currentTimeMillis()
        val last = prefs.lastTickAt
        if (last > 0L) {
            val gap = now - last
            if (gap > 40_000L) {
                val lit = runCatching {
                    getSystemService(PowerManager::class.java).isInteractive
                }.getOrDefault(false)
                if (lit) {
                    prefs.freezeCount = prefs.freezeCount + 1
                    if (gap > prefs.maxFreezeMs) prefs.maxFreezeMs = gap
                    trace("被冻结 ${gap / 1000} 秒（第 ${prefs.freezeCount} 次）")
                } else {
                    trace("息屏睡了 ${gap / 1000} 秒（不算冻结）")
                }
                prefs.lastTickAt = now   // 立刻重置基线，避免同一次断档被重复记账
                return
            }
        }
        // 心跳落盘：每 15 秒一次（判定阈值 40 秒，留足余量），与 tick 快慢无关
        if (now - last > 15_000L) prefs.lastTickAt = now
    }

    private fun enterLock() {
        trace("enterLock: 进入锁定态")
        prefs.lockedAt = System.currentTimeMillis()
        holdWakeLockBriefly()
        if (CallGuard.detect(this) || isCallHoldActive()) {
            forceUnmuteMic()
        } else {
            setMicMuted(true)
        }
        locked = true
        reassertTries = 0
        addOverlay()
        LockActivity.launch(this)
        runCatching { Scheduler.arm(this) }
        runCatching { Scheduler.armWatchdog(this) }   // 看门狗第一环
        updateNotification()
    }

    // ------------------------------------------------------------- 悬浮窗

    @Suppress("DEPRECATION")
    private fun addOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            trace("addOverlay 跳过: 没有悬浮窗权限")
            return
        }

        val v = SleepLockView(this, prefs.holdSeconds * 1000L, prefs.keepScreenOn, holdState) {
            unlock(this)
        }

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            // 关键：不带这个标志，锁屏一起来我们的悬浮窗就被 keyguard 挡住，
            // 而小布助手那种系统浮窗带了它，反而能显示出来。
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        if (prefs.keepScreenOn) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }

        val res = runCatching { wm.addView(v, lp) }
        if (res.isSuccess) {
            overlay = v
            trace("addOverlay OK")
        } else {
            trace("addOverlay FAIL: " + res.exceptionOrNull())
        }
    }

    /**
     * 抢回 z 序。
     * TYPE_APPLICATION_OVERLAY 只有一个层级，同层窗口按「谁后添加谁在上面」排序。
     * 小布助手用的就是同一个 type=2038 / layer=111000，只能靠重挂一次把它挤下去。
     * 长按计时存在 holdState 里，重挂不会清掉计时。
     */
    private fun reassertOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { wm.removeViewImmediate(v) }
        addOverlay()
        // 顺手把全屏 Activity 也顶到前面（重新应用 showWhenLocked / 沉浸式）
        LockActivity.launch(this)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { wm.removeViewImmediate(v) }
        trace("removeOverlay")
    }

    // ------------------------------------------------------------- 前台通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SleepTime 守护", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    private fun updateNotification() {
        runCatching { nm.notify(NOTI_ID, buildNotification()) }
    }

    private fun buildNotification(): android.app.Notification {
        val title: String
        val text: String
        if (locked) {
            title = "SleepTime 锁定中"
            text = if (prefs.holdSeconds < 60) {
                "长按屏幕 " + prefs.holdSeconds + " 秒可解除"
            } else {
                "长按屏幕 " + (prefs.holdSeconds / 60) + " 分钟可解除"
            }
        } else {
            title = "SleepTime 守护中"
            text = "每晚 " + String.format("%02d:%02d", prefs.hour, prefs.minute) + " 自动锁定"
        }

        val content = PendingIntent.getActivity(
            this,
            7,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(!locked)
            // 守护态：用系统渲染的倒计时，App 被冻住/杀掉也照样在走，不会再骗人
            .setUsesChronometer(!locked)
            .setChronometerCountDown(!locked)
            .setWhen(if (locked) System.currentTimeMillis() else Scheduler.nextTriggerMillis(prefs.hour, prefs.minute))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }
}