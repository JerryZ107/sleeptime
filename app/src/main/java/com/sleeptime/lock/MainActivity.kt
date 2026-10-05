package com.sleeptime.lock

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sleeptime.lock.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** 供 adb / 自动化调用： am start -n com.sleeptime.lock/.MainActivity --ez lock_now true */
        const val EXTRA_LOCK_NOW = "lock_now"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val h = Handler(Looper.getMainLooper())
    private var syncing = false

    private val notiPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val phonePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("没有电话权限，来电时可能无法自动放开麦克风")
            refresh()
        }

    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFont(uri) else refresh()
        }

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            b.tvLockState.text = lockStateText()
        b.tvLockState.setTextColor(color(if (Scheduler.shouldLock(prefs)) R.color.ok else R.color.text_dim))

        updateTraceInfo()   // 守护服务是异步起来的，onResume 时日志文件可能还没生成
            h.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.btnPickTime.setOnClickListener { pickTime() }
        b.btnPickHold.setOnClickListener { pickHold() }
        b.btnPickUnlock.setOnClickListener { pickUnlockTime() }
        b.btnPickFont.setOnClickListener { fontPicker.launch(arrayOf("*/*")) }
        b.btnResetFont.setOnClickListener { resetFont() }

        b.btnOverlay.setOnClickListener { openOverlaySettings() }
        b.btnAlarm.setOnClickListener { openExactAlarmSettings() }
        b.btnNoti.setOnClickListener { openNotificationSettings() }
        b.btnPhone.setOnClickListener { requestPhonePermission() }
        b.btnDnd.setOnClickListener { openDndSettings() }
        // 自启动 / 耗电管理：系统没有公开查询接口，只保留直达设置入口
        b.btnBoot.setOnClickListener { toast(SystemLinks.jumpStartup(this)) }
        b.btnBg.setOnClickListener { toast(SystemLinks.jumpBackground(this)) }

        b.btnTest.setOnClickListener { startTestLock() }
        b.btnStopTonight.setOnClickListener { stopTonight() }
        b.btnClearTrace.setOnClickListener { clearTrace() }
        b.btnUninstall.setOnClickListener { confirmUninstall() }

        // 打开控制面板 = 用户要操作，先把可能残留的麦克风静音清掉
        runCatching { getSystemService(AudioManager::class.java).isMicrophoneMute = false }

        bindSwitches()
        refresh()

        if (intent?.getBooleanExtra(EXTRA_LOCK_NOW, false) == true) {
            b.root.post { startTestLock() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LOCK_NOW, false)) startTestLock()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        android.util.Log.i("SleepTime", "onResume enabled=" + prefs.enabled + " running=" + LockService.running)
        if (prefs.enabled) {
            // 第一道保险：常驻守护服务，到点由它自己判断上锁（不依赖闹钟准时投递）
            LockService.start(this)
            // 第二道保险：系统闹钟，万一服务没活着还能把它叫起来
            runCatching { Scheduler.arm(this) }
            // 第三道保险：15 分钟一次的持久化任务（重启后系统自己重排）
            runCatching { Scheduler.armGuardJob(this) }
        }
        h.removeCallbacks(ticker)
        h.post(ticker)
    }

    override fun onPause() {
        h.removeCallbacks(ticker)
        super.onPause()
    }

    // --------------------------------------------------------------- 开关

    private fun bindSwitches() {
        b.swEnabled.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.enabled = v
            if (v) {
                prefs.lastUnlockAt = 0L
                Scheduler.arm(this)
                Scheduler.armGuardJob(this)
                LockService.start(this)
            } else {
                Scheduler.cancel(this)
                Scheduler.cancelGuardJob(this)
                LockService.stop(this)
            }
            refresh()
        }
        b.swNotify.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.showNotificationDetail = v
            LockService.start(this)
            refresh()
        }
        b.swPinTask.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.pinTask = v
            refresh()
        }
        b.swAllowCalls.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.allowCalls = v
            refresh()
        }
        b.swKeepScreen.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.keepScreenOn = v
            refresh()
        }
        b.swTurnScreen.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.turnScreenOn = v
            refresh()
        }
        b.swTrace.setOnCheckedChangeListener { _, v ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.traceEnabled = v
            refresh()
        }
    }

    private fun setSw(sw: SwitchCompat, value: Boolean) {
        syncing = true
        sw.isChecked = value
        syncing = false

        // 关掉的开关整体变灰：轨道 / 滑块 / 文字全部压暗，一眼看出哪些没开
        val thumb = if (value) R.color.neon_cyan else R.color.sw_off_thumb
        val track = if (value) R.color.neon_cyan else R.color.sw_off_track
        val label = if (value) R.color.text else R.color.text_dim
        sw.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(this, thumb))
        sw.trackTintList = ColorStateList.valueOf(ContextCompat.getColor(this, track))
        sw.setTextColor(ContextCompat.getColor(this, label))
    }

    // --------------------------------------------------------------- 刷新

    private fun refresh() {
        b.tvTime.text = String.format(Locale.US, "%02d:%02d", prefs.hour, prefs.minute)
        b.tvUnlockTime.text = String.format(Locale.US, "%02d:%02d", prefs.unlockHour, prefs.unlockMinute)

        setSw(b.swEnabled, prefs.enabled)
        setSw(b.swNotify, prefs.showNotificationDetail)
        setSw(b.swPinTask, prefs.pinTask)
        setSw(b.swAllowCalls, prefs.allowCalls)
        setSw(b.swKeepScreen, prefs.keepScreenOn)
        setSw(b.swTurnScreen, prefs.turnScreenOn)
        setSw(b.swTrace, prefs.traceEnabled)

        b.tvHold.text = holdLabel(prefs.holdSeconds)

        val on = prefs.enabled
        b.tvStatusTitle.text = if (on) "已启用" else "已停用"
        b.tvStatusTitle.setTextColor(color(if (on) R.color.ok else R.color.warn))

        b.tvStatusNext.text = if (on) {
            "每天 " + String.format(Locale.US, "%02d:%02d", prefs.hour, prefs.minute) +
                " 锁定 · " + String.format(Locale.US, "%02d:%02d", prefs.unlockHour, prefs.unlockMinute) +
                " 自动解除 · 长按 " + holdLabel(prefs.holdSeconds) + " 解锁"
        } else {
            "自动锁定已关闭，打开“锁定行为”中的“启用每日自动锁定”才会生效"
        }
        b.tvLockState.text = lockStateText()
        b.tvLockState.setTextColor(color(if (Scheduler.shouldLock(prefs)) R.color.ok else R.color.text_dim))

        updateTraceInfo()

        val fz = prefs.freezeCount
        if (on && fz > 0) {
            b.tvNextAlarm.text = "⚠ 后台被系统冻结过 " + fz + " 次（最长 " + (prefs.maxFreezeMs / 1000) +
                " 秒）—— 到点可能不会自动锁。去下面「耗电管理（应用详情-耗电管理）」，点右侧「去应用详情」，选「允许完全后台行为」"
            b.tvNextAlarm.setTextColor(color(R.color.warn))
        } else {
            b.tvNextAlarm.text = if (on) {
                "到点会整屏压黑，Home / 最近任务 / 通知栏都会失效（需开启屏幕固定）"
            } else {
                "未排定"
            }
            b.tvNextAlarm.setTextColor(color(R.color.text_dim))
        }

        // 字体
        val tf: Typeface = FontLoader.load(this)
        b.tvFontPreview.typeface = tf
        b.tvFontName.text = FontLoader.describe(this)

        // 权限
        val overlayOk = Settings.canDrawOverlays(this)
        setPermission(b.tvOverlayState, b.btnOverlay, overlayOk,
            okText = "已授权",
            badText = "未授权 · 没有它到点只会弹个通知，不会锁屏")

        val alarmOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else true
        setPermission(b.tvAlarmState, b.btnAlarm, alarmOk,
            okText = "已授权",
            badText = "未授权 · 会晚几分钟到十几分钟才锁")

        val notiOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        setPermission(b.tvNotiState, b.btnNoti, notiOk,
            okText = "已允许",
            badText = "未允许 · 前台服务通知会被隐藏，保活变差")

        val phoneOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        } else true
        setPermission(b.tvPhoneState, b.btnPhone, phoneOk,
            okText = "已授权",
            badText = "未授权 · 来电时无法自动放开麦克风")

        val dndOk = QuickToggles.hasDndAccess(this)
        setPermission(b.tvDndState, b.btnDnd, dndOk,
            okText = "已授权",
            badText = "未授权 · 免打扰不可用；点右侧去系统设置允许")

        updateCountdown()
    }

    private fun setPermission(
        state: android.widget.TextView,
        button: android.widget.TextView,
        granted: Boolean,
        okText: String,
        badText: String
    ) {
        state.text = if (granted) okText else badText
        state.setTextColor(color(if (granted) R.color.ok else R.color.warn))
        button.visibility = if (granted) View.INVISIBLE else View.VISIBLE
    }

    private fun requestPhonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            phonePerm.launch(Manifest.permission.READ_PHONE_STATE)
        } else {
            toast("电话权限已授予")
        }
    }

    private fun openDndSettings() {
        val ok = QuickToggles.openDndAccessSettings(this)
        toast(
            if (ok) "已打开勿扰权限页，请允许 SleepTime"
            else "打不开系统页，请手动去 设置 -> 通知与状态栏 -> 勿扰权限"
        )
    }

    /** 检测诊断日志：有才显示「清空」按钮 */
    private fun updateTraceInfo() {
        val logFile = File(filesDir, "trace.log")
        val size = if (logFile.exists()) logFile.length() else 0L
        if (size > 0L) {
            b.tvTraceInfo.text = "当前 " + size + " 字节。超 64KB 会自动清空。"
            b.tvTraceInfo.setTextColor(color(R.color.text_dim))
            b.btnClearTrace.visibility = View.VISIBLE
        } else {
            b.tvTraceInfo.text = if (prefs.traceEnabled) "暂无日志（只在事件发生时写）" else "已关闭，不再写入任何文件"
            b.tvTraceInfo.setTextColor(color(R.color.text_dim))
            b.btnClearTrace.visibility = View.GONE
        }
    }

    private fun updateCountdown() {
        if (!prefs.enabled) {
            b.tvCountdown.text = ""
            return
        }
        val left = (Scheduler.nextTriggerMillis(prefs.hour, prefs.minute) - System.currentTimeMillis())
            .coerceAtLeast(0L)
        val hh = left / 3_600_000L
        val mm = left % 3_600_000L / 60_000L
        val ss = left % 60_000L / 1000L
        b.tvCountdown.text =
            "距下次锁定  " + if (hh > 0) "$hh 小时 $mm 分" else "$mm 分 $ss 秒"
    }

    private fun holdLabel(sec: Int): String = when {
        sec < 60 -> "$sec 秒"
        sec % 60 == 0 -> "${sec / 60} 分钟"
        else -> "${sec / 60} 分 ${sec % 60} 秒"
    }

    /** 把「现在到底该不该锁、为什么」直接写出来，省得猜 */
    private fun lockStateText(): String {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        fun hm(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
        val l = prefs.hour * 60 + prefs.minute
        val u = prefs.unlockHour * 60 + prefs.unlockMinute
        return when {
            !prefs.enabled -> "自动锁定已关闭"
            !Scheduler.inLockPeriod(prefs) ->
                "现在不该锁 —— 当前 " + hm(nowMin) + " 不在时段 " + hm(l) + " ~ " + hm(u) + " 内"
            prefs.lastUnlockAt >= Scheduler.currentPeriodStart(prefs) ->
                "现在不该锁 —— 本时段你已经手动解锁过了"
            else -> "现在应该锁定 —— 在时段 " + hm(l) + " ~ " + hm(u) + " 内，且本时段未解锁"
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // --------------------------------------------------------------- 选择器

    private fun pickTime() {
        val tp = TimePicker(this).apply {
            setIs24HourView(true)
            hour = prefs.hour
            minute = prefs.minute
        }
        val pad = (14 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            addView(tp, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("每日锁定时间")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                tryChangeLockTime(tp.hour, tp.minute)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * good2: 每天只允许改一次锁定时间，且最多向后延 15 分钟。
     * 提前修改不限；不管提前还是延后，都消耗当天这一次机会。
     */
    private fun tryChangeLockTime(newHour: Int, newMinute: Int) {
        val oldMin = prefs.hour * 60 + prefs.minute
        val newMin = newHour * 60 + newMinute
        if (oldMin == newMin) {
            toast("锁定时间没有变化")
            return
        }

        val today = LocalDate.now().toEpochDay()
        if (prefs.lockTimeChangeDay == today) {
            toast("今天已经修改过一次了，每天只能改一次")
            return
        }

        // 取环形时间差里较短的方向：正数=延后，负数=提前
        val delay = (newMin - oldMin + 1440) % 1440
        val advance = 1440 - delay
        val signed = if (delay <= advance) delay else -advance
        if (signed > 15) {
            toast("每天最多只能把锁定时间延后 15 分钟")
            return
        }

        prefs.hour = newHour
        prefs.minute = newMinute
        prefs.lockTimeChangeDay = today
        prefs.lastUnlockAt = 0L
        if (prefs.enabled) Scheduler.arm(this)
        refresh()
        val mins = kotlin.math.abs(signed)
        toast("锁定时间已" + (if (signed >= 0) "延后 " else "提前 ") + mins + " 分钟")
    }

    private fun pickUnlockTime() {
        val tp = TimePicker(this).apply {
            setIs24HourView(true)
            hour = prefs.unlockHour
            minute = prefs.unlockMinute
        }
        val pad = (14 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            addView(tp, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("每日解除锁定时间")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                prefs.unlockHour = tp.hour
                prefs.unlockMinute = tp.minute
                refresh()
                toast("解除时间已改为 " + String.format(Locale.US, "%02d:%02d", tp.hour, tp.minute))
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun pickHold() {
        // 3 分钟以下已全部去掉
        val labels = arrayOf("3 分钟（推荐）", "5 分钟", "10 分钟")
        val values = intArrayOf(180, 300, 600)
        val idx = values.indexOf(prefs.holdSeconds).let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle("长按解锁时长")
            .setSingleChoiceItems(labels, idx) { dlg, which ->
                prefs.holdSeconds = values[which]
                dlg.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFont(uri: Uri) {
        val dst = File(filesDir, FontLoader.FONT_FILE)
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("读不到这个文件")

            if (dst.length() < 2048L || dst.length() > 40L * 1024 * 1024) {
                throw IllegalStateException("文件大小异常 (${dst.length()} 字节)")
            }
            Typeface.createFromFile(dst)
            true
        }.getOrElse {
            dst.delete()
            toast("导入失败：${it.message}")
            false
        }

        if (ok) {
            prefs.fontPath = dst.absolutePath
            toast("字体已生效")
        }
        refresh()
    }

    private fun resetFont() {
        File(filesDir, FontLoader.FONT_FILE).delete()
        prefs.fontPath = null
        refresh()
        toast("已恢复内置字体")
    }

    // --------------------------------------------------------------- 权限跳转

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notiPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val i = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        openAppNotificationSettings()
    }

    private fun openAppNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    // --------------------------------------------------------------- 操作

    private fun startTestLock() {
        if (!Settings.canDrawOverlays(this)) {
            toast("先给悬浮窗权限")
            openOverlaySettings()
            return
        }
        // 强制立刻上锁，不依赖「是否已到设定时间」
        LockService.lockNow(this)
        LockActivity.launch(this)
        toast("冻结中 · 按住黑屏 ${holdLabel(prefs.holdSeconds)} 才能出来")
    }

    /** 检测到日志存在才调用得到（按钮在没日志时不显示）；顺手清掉冻结计数 */
    private fun clearTrace() {
        val logFile = File(filesDir, "trace.log")
        val n = if (logFile.exists()) logFile.length() else 0L
        logFile.delete()
        prefs.resetFreezeStats()
        toast(if (n > 0L) "已清空 " + n + " 字节日志，冻结计数也归零" else "没有日志可清")
        refresh()
    }

    private fun stopTonight() {
        AlertDialog.Builder(this)
            .setTitle("停用今晚")
            .setMessage("立刻解除当前锁定，并且今晚 23:30 不再自动锁。明天的定时照常。")
            .setPositiveButton("确认") { _, _ ->
                LockService.unlock(this)
                toast("今晚不再锁定")
                refresh()
            }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this)
            .setTitle("完全卸载")
            .setMessage("停止锁定、取消全部定时、清空所有配置，然后弹出系统卸载框。")
            .setPositiveButton("继续") { _, _ ->
                prefs.lastUnlockAt = System.currentTimeMillis()
                LockService.unlock(this)
                Scheduler.cancel(this)
                prefs.enabled = false
                File(filesDir, FontLoader.FONT_FILE).delete()
                prefs.clear()
                runCatching {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}