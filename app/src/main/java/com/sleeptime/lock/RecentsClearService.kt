package com.sleeptime.lock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * A deliberately small accessibility service.
 *
 * It is only used when the lock-screen panel's 清理后台 tile is pressed:
 * open the system Recents screen, find the system 清除 button, click it,
 * then close Recents. It does not read or click anything else.
 */
class RecentsClearService : AccessibilityService() {

    companion object {
        private const val TAG = "SleepTime"

        @Volatile
        var instance: RecentsClearService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val secure = ctx.contentResolver
            val enabled = Settings.Secure.getInt(
                secure, Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            if (!enabled) return false
            val services = Settings.Secure.getString(
                secure, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(ctx, RecentsClearService::class.java)
            return services.split(':').any {
                it.equals(component.flattenToString(), true) ||
                    it.equals(component.flattenToShortString(), true)
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "RecentsClearService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun clearRecents(done: (Boolean) -> Unit) {
        if (busy) {
            done(false)
            return
        }
        busy = true
        val opened = performGlobalAction(GLOBAL_ACTION_RECENTS)
        Log.i(TAG, "clearRecents recentsOpen=$opened")
        if (!opened) {
            busy = false
            done(false)
            return
        }
        clickClear(0, done)
    }

    private fun clickClear(attempt: Int, done: (Boolean) -> Unit) {
        if (!busy) {
            done(false)
            return
        }
        if (attempt >= 15) {
            Log.w(TAG, "clearRecents: clear button not found")
            fail(done)
            return
        }
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        windows.forEach { w -> w.root?.let { roots.add(it) } }
        val target = roots.firstNotNullOfOrNull { findClearButton(it) }
        if (attempt < 3) {
            Log.i(
                TAG,
                "clearRecents attempt=$attempt windows=" +
                    roots.joinToString { it.packageName?.toString() ?: "?" } +
                    " texts=" + roots.joinToString { rootTexts(it) }
            )
        }
        if (target != null) {
            val clicked = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            Log.i(TAG, "clearRecents click=$clicked text=${target.text} desc=${target.contentDescription}")
            if (clicked) {
                busy = false
                h.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    done(true)
                }, 450L)
                return
            }
        }
        h.postDelayed({ clickClear(attempt + 1, done) }, 300L)
    }

    private fun fail(done: (Boolean) -> Unit) {
        busy = false
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        done(false)
    }

    private fun findClearButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hit = isClearLabel(text) || isClearLabel(desc)
            if (hit) {
                if (n.isClickable) return n
                var p = n.parent
                while (p != null) {
                    if (p.isClickable) return p
                    p = p.parent
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun rootTexts(root: AccessibilityNodeInfo): String {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 40) {
            val n = queue.removeFirst()
            seen++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
            if (d.isNotEmpty()) out.add(d)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out.take(20).joinToString("|")
    }

    private fun isClearLabel(value: String): Boolean =
        value == "清除" || value == "全部清除" || value == "清除全部" ||
            value == "清理" || value == "全部清理" || value == "清理全部"
}
