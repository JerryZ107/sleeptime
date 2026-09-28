package com.sleeptime.waker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 唤醒器的界面（故意用纯代码搭，不引任何依赖，包小、装起来无感）。
 * 就三件事：看它叫醒 SleepTime 的账、手动叫一次、打开 SleepTime。
 */
class MainActivity : Activity() {

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Waker.armJob(this)
        Waker.armWatchdog(this)
        // 打开界面 = 用户在用，顺手先叫一次
        Waker.wake(this, "manual")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val pkg = runCatching {
            packageManager.getPackageInfo(Waker.TARGET_PKG, 0).versionName
        }.getOrNull()
        info.text = buildString {
            append("我是 SleepTime 的外部唤醒器。\n")
            append("作用：开机后 / 每 15 分钟，给 SleepTime 发一条显式广播，\n")
            append("哪怕它被「强行停止」过也能把它叫醒（显式 intent 穿透 stopped）。\n\n")
            append(if (pkg != null) "SleepTime：已安装（v$pkg）\n" else "SleepTime：没装？\n")
            append("唤醒记录：" + Waker.stats(this@MainActivity) + "\n\n")
            append("建议：别把这东西也强行停止；系统管家/自启动里给它放行。")
        }
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "SleepTime 唤醒器"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, pad)
        }
        box.addView(title)

        info = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, pad)
        }
        box.addView(info)

        box.addView(Button(this).apply {
            text = "立刻叫醒 SleepTime"
            setOnClickListener {
                Waker.wake(this@MainActivity, "button")
                render()
            }
        })

        box.addView(Button(this).apply {
            text = "打开 SleepTime"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent().setComponent(
                            ComponentName(Waker.TARGET_PKG, "com.sleeptime.lock.MainActivity")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                render()
            }
        })

        box.addView(Button(this).apply {
            text = "刷新"
            setOnClickListener { render() }
        })

        val hint = TextView(this).apply {
            text = "\n小提示：把它也加进系统的「自启动」白名单，开机它才能第一时间去叫 SleepTime。"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.START
        }
        box.addView(hint)

        return box
    }
}