package com.sleeptime.lock

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 黑幕本体。**纯静态**：
 *   - 只有「红（品红）/ 蓝（青）」两层文字，做最小错位形成色差，两层挨得最近但不重合
 *   - 静态霓虹辉光
 *   - 没有动画、没有抖动、没有扫描线
 *
 * 长按逻辑不变：按住满 holdMillis 回调 onFinished；抬手/多指/被打断立刻归零。
 */
class SleepLockView(
    context: Context,
    private val holdMillis: Long,
    private val keepScreenOnPref: Boolean,
    private val holdState: HoldState,
    private val onFinished: () -> Unit
) : FrameLayout(context) {

    private companion object {
        val CYAN = 0xFF00F0FF.toInt()
        val MAGENTA = 0xFFFF2BD6.toInt()
        val HINT_COLOR = 0xFF7FD8E8.toInt()
        val HINT_GLOW = 0xFF2E8FA6.toInt()

        /** 两层文字的总错位量（dp）。最小但不重合 —— 就是「红蓝挨得最近」那一档 */
        const val ABERRATION_DP = 1.0f

        const val SHOW_PROGRESS = false
        const val DOT_STEP_MS = 30_000L
    }

    private lateinit var title: TextView
    private lateinit var ghost: TextView
    private lateinit var hint: TextView

    private val d = resources.displayMetrics.density
    private val h = Handler(Looper.getMainLooper())

    private val finishHold = Runnable {
        if (holdState.startAt != 0L) {
            abortHold()
            onFinished()
        }
    }

    private val addDot = object : Runnable {
        override fun run() {
            if (holdState.startAt == 0L) return
            holdState.dotCount++
            hint.text = "睡眠时间" + ".".repeat(holdState.dotCount.coerceAtMost(9))
            h.postDelayed(this, DOT_STEP_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = keepScreenOnPref

        val tf = FontLoader.load(context)
        val half = ABERRATION_DP / 2f * d

        ghost = TextView(context).apply {
            text = "SleepTime"
            setTextColor(MAGENTA)
            textSize = 52f
            letterSpacing = 0.16f
            typeface = tf
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setShadowLayer(4f, 0f, 0f, MAGENTA)
            alpha = 0.9f
            translationX = -half
        }

        title = TextView(context).apply {
            text = "SleepTime"
            setTextColor(CYAN)
            textSize = 52f
            letterSpacing = 0.16f
            typeface = tf
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setShadowLayer(20f, 0f, 0f, CYAN)
            translationX = half
        }

        val titleBox = FrameLayout(context).apply {
            addView(ghost, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(title, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        hint = TextView(context).apply {
            text = "睡眠时间..."
            setTextColor(HINT_COLOR)
            textSize = 16f
            letterSpacing = 0.32f
            typeface = tf
            gravity = Gravity.CENTER
            includeFontPadding = false
            visibility = View.INVISIBLE
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setShadowLayer(8f, 0f, 0f, HINT_GLOW)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(titleBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (26 * d).toInt() })
        }
        addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))


        setOnTouchListener { _, e -> handleTouch(e) }
        setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_BACK }

        // 悬浮窗被重建（抢 z 序）时，接着算长按
        if (holdState.startAt != 0L) {
            hint.visibility = View.VISIBLE
            keepScreenOn = true
            val remain = (holdMillis - (SystemClock.uptimeMillis() - holdState.startAt)).coerceAtLeast(0L)
            h.postDelayed(finishHold, remain)
        }
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startHold()
            MotionEvent.ACTION_POINTER_DOWN -> abortHold()
            MotionEvent.ACTION_POINTER_UP -> abortHold()
            MotionEvent.ACTION_MOVE -> if (e.pointerCount > 1) abortHold()
            MotionEvent.ACTION_UP -> abortHold()
            MotionEvent.ACTION_CANCEL -> abortHold()
        }
        return true
    }

    private fun startHold() {
        holdState.startAt = SystemClock.uptimeMillis()
        holdState.dotCount = 0
        hint.text = "睡眠时间..."
        hint.visibility = View.VISIBLE
        keepScreenOn = true
        h.removeCallbacks(finishHold)
        h.postDelayed(finishHold, holdMillis)
        if (SHOW_PROGRESS) {
            h.removeCallbacks(addDot)
            h.postDelayed(addDot, DOT_STEP_MS)
        }
    }

    fun abortHold() {
        holdState.startAt = 0L
        h.removeCallbacks(finishHold)
        h.removeCallbacks(addDot)
        if (hint.visibility != View.INVISIBLE) hint.visibility = View.INVISIBLE
        keepScreenOn = keepScreenOnPref
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

}

/** 长按计时状态。放在 SleepLockView 外面，重建 View 时不会丢。 */
class HoldState {
    @Volatile var startAt: Long = 0L
    @Volatile var dotCount: Int = 0
}