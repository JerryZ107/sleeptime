package com.sleeptime.lock

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val onPhone: () -> Unit,
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
    private lateinit var torchBtn: ImageView
    private lateinit var phoneBtn: ImageView

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

        // 手电筒开关：黑幕右下角一个小药丸。为什么不做「下拉通知栏」——
        // 那等于给锁开后门（通知一点就能进设置把 App 强行停止）。
        // 这里只放一个开关，锁的完整性不受影响。
        val btnPx = (62 * d).toInt()

        // 两个图标按钮：左下 = 打电话，右下 = 手电筒。
        torchBtn = circleButton(R.drawable.ic_lock_flashlight, "手电筒").apply {
            setOnClickListener {
                android.util.Log.i("SleepTime", "点击了手电筒按钮")
                Torch.toggle(context)
                postDelayed({ updateTorchButton() }, 150)
            }
        }
        addView(torchBtn, FrameLayout.LayoutParams(btnPx, btnPx, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = (26 * d).toInt()
            bottomMargin = (34 * d).toInt()
        })

        phoneBtn = circleButton(R.drawable.ic_lock_phone, "打电话").apply {
            setOnClickListener {
                android.util.Log.i("SleepTime", "点击了电话按钮")
                onPhone()
            }
        }
        addView(phoneBtn, FrameLayout.LayoutParams(btnPx, btnPx, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (26 * d).toInt()
            bottomMargin = (34 * d).toInt()
        })

        updateTorchButton()

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
        android.util.Log.i("SleepTime", "锁屏 touch action=" + e.actionMasked + " x=" + e.x + " y=" + e.y)
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
        Torch.startWatch(context)
        Torch.onChanged = { post { updateTorchButton() } }
        updateTorchButton()
    }

    override fun onDetachedFromWindow() {
        Torch.onChanged = null
        Torch.stopWatch(context)
        h.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** 圆形图标按钮：半透明底 + 描边，图标整体可着色 */
    private fun circleButton(icon: Int, desc: String): ImageView = ImageView(context).apply {
        setImageResource(icon)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (16 * d).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x22FFFFFF)
            setStroke((1.2f * d).toInt().coerceAtLeast(1), 0x55FFFFFF)
        }
        isClickable = true
        isFocusable = false
        setColorFilter(HINT_COLOR, PorterDuff.Mode.SRC_IN)
        alpha = 0.72f
    }

    /** 手电筒图标跟着真实状态变色（黄色 = 开着），和系统下拉面板的磁贴互通 */
    private fun updateTorchButton() {
        if (!::torchBtn.isInitialized) return
        val on = Torch.isOn
        torchBtn.setColorFilter(if (on) 0xFFFFD54A.toInt() else HINT_COLOR, PorterDuff.Mode.SRC_IN)
        torchBtn.alpha = if (on) 1f else 0.72f
    }
}

/** 长按计时状态。放在 SleepLockView 外面，重建 View 时不会丢。 */
class HoldState {
    @Volatile var startAt: Long = 0L
    @Volatile var dotCount: Int = 0
}