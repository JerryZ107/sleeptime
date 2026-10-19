package com.sleeptime.lock

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
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

    private val title: TextView
    private val ghost: TextView
    private lateinit var hint: TextView

    // ---- 下拉面板（只放第三方 App 真能做的开关）----
    private lateinit var panel: LinearLayout
    private lateinit var torchTile: QuickTile
    private lateinit var silentTile: QuickTile
    private lateinit var dndTile: QuickTile
    private lateinit var clearTile: QuickTile
    private var panelOpen = false
    private var downY = 0f
    private var swipeUsed = false
    private lateinit var dndHintRow: LinearLayout
    private val autoClose = Runnable { closePanel() }

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


        buildPanel(tf)

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
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                swipeUsed = false
                // 面板开着时点空白不算长按（免得一边看开关一边把解锁计时跑起来）
                if (!panelOpen) startHold()
            }
            MotionEvent.ACTION_POINTER_DOWN -> abortHold()
            MotionEvent.ACTION_POINTER_UP -> abortHold()
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) { abortHold(); return true }
                val dy = e.y - downY
                if (!swipeUsed && dy > 64 * d) { swipeUsed = true; abortHold(); openPanel(); android.util.Log.i("SleepTime", "面板：手势触发 dy=" + dy) }
                else if (!swipeUsed && dy < -64 * d && panelOpen) { swipeUsed = true; abortHold(); closePanel() }
            }
            MotionEvent.ACTION_UP -> {
                if (!swipeUsed && panelOpen && !inPanel(e.rawX.toInt(), e.rawY.toInt())) closePanel()
                abortHold()
            }
            MotionEvent.ACTION_CANCEL -> abortHold()
        }
        return true
    }

    // ------------------------------------------------------------ 快捷开关面板

    private fun buildPanel(tf: android.graphics.Typeface) {
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = 26f * d
                setColor(0xE60B1016.toInt())
                setStroke((1.2f * d).toInt().coerceAtLeast(1), 0x5526D9E8)
            }
            val ph = (18 * d).toInt()
            setPadding(ph, (14 * d).toInt(), ph, (16 * d).toInt())
        }
        panel.addView(TextView(context).apply {
            text = "快捷开关"
            setTextColor(HINT_COLOR); textSize = 11.5f; letterSpacing = 0.3f; typeface = tf
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        torchTile = makeTile(tf, R.drawable.ic_lock_flashlight, "手电筒") {
            QuickToggles.setTorch(context, !QuickToggles.isTorch())
            postDelayed({ refreshTiles() }, 150)
        }
        silentTile = makeTile(tf, R.drawable.ic_lock_silent, "系统静音") {
            val on = !QuickToggles.isSilent(context)
            val ok = QuickToggles.setSilent(context, on)
            android.widget.Toast.makeText(
                context,
                if (!ok) "静音切换失败"
                else if (on) "已静音铃声/通知/系统音（不碰勿扰）"
                else "已恢复声音",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            refreshTiles()
        }
        dndTile = makeTile(tf, R.drawable.ic_lock_dnd, "勿扰模式") {
            if (!QuickToggles.hasDndAccess(context)) {
                showDndGrantHint()
            } else {
                val on = !QuickToggles.isDnd(context)
                QuickToggles.setDnd(context, on)
                android.widget.Toast.makeText(
                    context,
                    if (on) "已开启勿扰模式" else "已关闭勿扰模式",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refreshTiles()
            }
        }
        clearTile = makeTile(tf, R.drawable.ic_lock_clear, "清理后台") {
            android.widget.Toast.makeText(context, "正在清理后台...", android.widget.Toast.LENGTH_SHORT).show()
            QuickToggles.clearBackground(context) { count ->
                val msg = if (count > 0) {
                    "已请求清理 " + count + " 个应用的后台进程"
                } else {
                    "没有可清理的第三方后台应用"
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        for (item in listOf(torchTile, silentTile, dndTile, clearTile)) {
            row.addView(item.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (14 * d).toInt() })

        dndHintRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        dndHintRow.addView(TextView(context).apply {
            text = "勿扰模式未授权 · 请先解锁，回控制面板授权勿扰权限"
            setTextColor(0x99FFD54A.toInt())
            textSize = 10.5f
            typeface = tf
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(dndHintRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * d).toInt() })
        panel.addView(TextView(context).apply {
            text = "点图标切换 · 清理后台只清第三方后台进程 · 上滑收起"
            setTextColor(0x66A8D8E0.toInt()); textSize = 10f; gravity = Gravity.CENTER; typeface = tf
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * d).toInt() })
        addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply {
            topMargin = (30 * d).toInt()
            leftMargin = (22 * d).toInt()
            rightMargin = (22 * d).toInt()
        })
    }

    private fun makeTile(tf: android.graphics.Typeface, icon: Int, label: String, onClick: () -> Unit): QuickTile {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val size = (54 * d).toInt()
        val iv = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (14 * d).toInt(); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
                setStroke((1.2f * d).toInt().coerceAtLeast(1), 0x55FFFFFF)
            }
            setColorFilter(HINT_COLOR, PorterDuff.Mode.SRC_IN)
            alpha = 0.75f
            isClickable = true; isFocusable = false
            setOnClickListener { onClick() }
        }
        box.addView(iv, LinearLayout.LayoutParams(size, size))
        val tv = TextView(context).apply {
            text = label; setTextColor(HINT_COLOR); textSize = 11f; typeface = tf
            gravity = Gravity.CENTER; includeFontPadding = false
        }
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * d).toInt() })
        return QuickTile(box, iv, tv)
    }

    private fun refreshTiles() {
        if (!::panel.isInitialized) return
        val hasDnd = QuickToggles.hasDndAccess(context)
        if (::dndHintRow.isInitialized) {
            dndHintRow.visibility = if (hasDnd) View.GONE else View.VISIBLE
        }
        setTileState(torchTile, QuickToggles.isTorch(), "手电筒")
        setTileState(silentTile, QuickToggles.isSilent(context), "系统静音")
        setTileState(dndTile, QuickToggles.isDnd(context), if (hasDnd) "勿扰模式" else "勿扰模式 · 需授权")
    }

    private fun showDndGrantHint() {
        android.widget.Toast.makeText(
            context,
            "勿扰模式未授权。请先解锁，回 SleepTime 控制面板授权勿扰权限",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun setTileState(t: QuickTile, on: Boolean, label: String) {
        val c = if (on) 0xFFFFD54A.toInt() else HINT_COLOR
        t.icon.setColorFilter(c, PorterDuff.Mode.SRC_IN)
        t.icon.alpha = if (on) 1f else 0.75f
        t.label.text = label
        t.label.setTextColor(c)
    }

    private fun openPanel() {
        if (panelOpen) return
        panelOpen = true
        QuickToggles.syncSilentState(context)
        refreshTiles()
        panel.visibility = View.VISIBLE
        panel.alpha = 1f
        panel.translationY = 0f
        android.util.Log.i("SleepTime", "面板：展开")
        h.removeCallbacks(autoClose)
        h.postDelayed(autoClose, 15_000L)
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        h.removeCallbacks(autoClose)
        panel.visibility = View.GONE
        android.util.Log.i("SleepTime", "面板：收起")
    }

    private fun inPanel(x: Int, y: Int): Boolean {
        val r = Rect()
        return panel.getGlobalVisibleRect(r) && r.contains(x, y)
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
        Torch.onChanged = { post { refreshTiles() } }
    }

    override fun onDetachedFromWindow() {
        Torch.onChanged = null
        Torch.stopWatch(context)
        h.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

}

/** 下拉面板里的一个小开关：圆图标 + 下面一行字 */
class QuickTile(val root: LinearLayout, val icon: ImageView, val label: TextView)

/** 长按计时状态。放在 SleepLockView 外面，重建 View 时不会丢。 */
class HoldState {
    @Volatile var startAt: Long = 0L
    @Volatile var dotCount: Int = 0
}