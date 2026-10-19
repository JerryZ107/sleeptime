package com.sleeptime.lock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全屏黑幕 + 沉浸式 + 可选进入系统「屏幕固定」。
 * 单实例、独立 task、不进最近任务，所以 Home / 最近任务都找不到它。
 */
class LockActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: LockActivity? = null
            private set

        @Volatile
        var visible: Boolean = false
            private set

        fun launch(ctx: Context) {
            if (visible) return
            val i = Intent(ctx, LockActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            runCatching { ctx.startActivity(i) }
        }

        fun close() {
            val a = instance
            instance = null
            visible = false
            if (a == null) return
            // 关键：处于「屏幕固定」的 task，系统会拒绝 finish。必须先解固定，否则
            // 服务停了、黑屏却永远留在屏幕上。
            runCatching { a.stopLockTask() }
            runCatching { a.finishAndRemoveTask() }
            // stopLockTask 在部分 ROM 上是异步的，补两刀
            val dv = a.window?.decorView ?: return
            dv.postDelayed({ runCatching { a.finishAndRemoveTask() } }, 250L)
            dv.postDelayed({
                runCatching { a.stopLockTask() }
                runCatching { a.finishAndRemoveTask() }
                runCatching { a.finish() }
            }, 900L)
        }
    }

    private lateinit var prefs: Prefs
    private var lastPinAttemptAt = 0L

    private val h = Handler(Looper.getMainLooper())
    private var sawService = false
    private var checks = 0

    /** 兜底：只要「服务曾经在跑、现在没了」，就说明锁已解除，这个界面必须滚蛋 */
    private val selfCheck = object : Runnable {
        override fun run() {
            checks++
            if (LockService.running) {
                sawService = true
            } else if (sawService || checks >= 10) {
                close()
                return
            }
            h.postDelayed(this, 1200L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // 正在通话 -> 直接把舞台让出去，让系统通话界面浮上来
        if (prefs.allowCalls && CallGuard.detect(this)) {
            finish()
            return
        }

        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(prefs.turnScreenOn)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            if (prefs.turnScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(
            SleepLockView(this, prefs.holdSeconds * 1000L, prefs.keepScreenOn, HoldState()) {
                LockService.unlock(this)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        visible = true
        tryPin()
        h.removeCallbacks(selfCheck)
        h.postDelayed(selfCheck, 1500L)
    }

    /**
     * 非 device-owner 时第一次会弹系统「要固定此屏幕吗」，勾上「不再询问」之后就是静默钉住。
     * 带 3 秒节流：系统确认框可能让 Activity 短暂 pause/resume，避免弹两次。
     */
    private fun tryPin() {
        if (!prefs.pinTask) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPinAttemptAt < 3000L) return
        lastPinAttemptAt = now
        runCatching { startLockTask() }
    }

    override fun onPause() {
        visible = false
        h.removeCallbacks(selfCheck)
        super.onPause()
    }

    override fun onDestroy() {
        visible = false
        h.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        runCatching { stopLockTask() }
        super.onDestroy()
    }

    /** 返回键直接吞掉 */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // no-op
    }

    /** 清理后台前临时解除屏幕固定，让最近任务能被打开 */
    fun unpinForRecentsClear() {
        runCatching { stopLockTask() }
    }

    /** 清理完成后重新固定锁屏 */
    fun repinAfterRecentsClear() {
        lastPinAttemptAt = 0L
        tryPin()
    }

    /** 来电期间主动退场，挂断后由 LockService 的看门狗重新拉起来 */
    fun stepAsideForCall() {
        visible = false
        runCatching { stopLockTask() }
        runCatching { finishAndRemoveTask() }
    }
}
