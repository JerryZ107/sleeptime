package com.sleeptime.lock

import android.content.Context
import android.content.SharedPreferences

/** 全部配置 */
class Prefs(context: Context) {

    /**
     * 配置存在「设备加密存储」(DE)，不是默认的凭据加密存储 (CE)。
     *
     * 为什么：重启之后、第一次解锁之前，CE 存储挂不上 —— App 连自己的配置都读不到，
     * 自然没法把闹钟武装起来。这台机器实测：BFU 状态下 /data/user/0/<pkg> 直接不存在。
     * DE 存储开机瞬间可读，配合 directBootAware 组件，首次解锁前就能排好闹钟。
     */
    private val sp: SharedPreferences = run {
        val app = context.applicationContext
        val de = app.createDeviceProtectedStorageContext()
            .getSharedPreferences("sleeptime", Context.MODE_PRIVATE)
        if (de.all.isEmpty()) {
            // 一次性搬家：老版本的配置在 CE 存储里
            runCatching {
                val old = app.getSharedPreferences("sleeptime", Context.MODE_PRIVATE).all
                if (old.isNotEmpty()) {
                    val e = de.edit()
                    for ((k, v) in old) {
                        when (v) {
                            is Boolean -> e.putBoolean(k, v)
                            is Int -> e.putInt(k, v)
                            is Long -> e.putLong(k, v)
                            is Float -> e.putFloat(k, v)
                            is String -> e.putString(k, v)
                        }
                    }
                    e.commit()
                }
            }
        }
        de
    }

    /** 总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(value) = sp.edit().putBoolean(K_ENABLED, value).apply()

    /** 每日锁定时刻 */
    var hour: Int
        get() = sp.getInt(K_HOUR, 23)
        set(value) = sp.edit().putInt(K_HOUR, value).apply()

    var minute: Int
        get() = sp.getInt(K_MINUTE, 30)
        set(value) = sp.edit().putInt(K_MINUTE, value).apply()

    /** 每日自动解除锁定时刻（可以比锁定时刻小，表示跨午夜） */
    var unlockHour: Int
        get() = sp.getInt(K_UHOUR, 7)
        set(value) = sp.edit().putInt(K_UHOUR, value).apply()

    var unlockMinute: Int
        get() = sp.getInt(K_UMINUTE, 0)
        set(value) = sp.edit().putInt(K_UMINUTE, value).apply()

    /** 解锁需要长按的秒数。最低 3 分钟，设更小会被自动抬到 180 */
    var holdSeconds: Int
        get() = sp.getInt(K_HOLD, 180).coerceAtLeast(MIN_HOLD)
        set(value) = sp.edit().putInt(K_HOLD, value.coerceAtLeast(MIN_HOLD)).apply()

    /**
     * 本锁定周期内最后一次「手动解除」的时间戳。
     * 与 Scheduler.currentPeriodStart() 比较，决定这个周期还该不该锁。
     */
    var lastUnlockAt: Long
        get() = sp.getLong(K_LAST_UNLOCK, 0L)
        set(value) {
            sp.edit().putLong(K_LAST_UNLOCK, value).commit()
        }

    /** 进入屏幕固定 */
    var pinTask: Boolean
        get() = sp.getBoolean(K_PIN, true)
        set(value) = sp.edit().putBoolean(K_PIN, value).apply()

    /** 来电时让出屏幕并放开麦克风 */
    var allowCalls: Boolean
        get() = sp.getBoolean(K_CALLS, true)
        set(value) = sp.edit().putBoolean(K_CALLS, value).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean(K_KEEP, false)
        set(value) = sp.edit().putBoolean(K_KEEP, value).apply()

    var turnScreenOn: Boolean
        get() = sp.getBoolean(K_TURN, false)
        set(value) = sp.edit().putBoolean(K_TURN, value).apply()

    /** 锁定时静音麦克风（让小布听不见） */
    var micMute: Boolean
        get() = sp.getBoolean(K_MIC, true)
        set(value) = sp.edit().putBoolean(K_MIC, value).apply()

    /** 后台被系统冻结的次数 / 最长一次冻结多久 / 上次心跳时间（用于自检） */
    var freezeCount: Int
        get() = sp.getInt(K_FREEZE_N, 0)
        set(value) = sp.edit().putInt(K_FREEZE_N, value).apply()

    var maxFreezeMs: Long
        get() = sp.getLong(K_FREEZE_MAX, 0L)
        set(value) = sp.edit().putLong(K_FREEZE_MAX, value).apply()

    var lastTickAt: Long
        get() = sp.getLong(K_LAST_TICK, 0L)
        set(value) = sp.edit().putLong(K_LAST_TICK, value).apply()

    /**
     * 下一次「该锁」的时刻：排闹钟时写进去。
     * 自检就靠它跟 lastLockAt 对照 —— 到点了却没锁上，一眼就能看出来。
     */
    var pendingTrigger: Long
        get() = sp.getLong(K_PENDING, 0L)
        set(value) { sp.edit().putLong(K_PENDING, value).commit() }

    /** 最近一次真的进入锁定态的时刻 */
    var lastLockAt: Long
        get() = sp.getLong(K_LAST_LOCK, 0L)
        set(value) { sp.edit().putLong(K_LAST_LOCK, value).commit() }

    /** 最近一次被系统冻结的时刻（自检「后台运行」那行要用） */
    var lastFreezeAt: Long
        get() = sp.getLong(K_FREEZE_LAST, 0L)
        set(value) { sp.edit().putLong(K_FREEZE_LAST, value).commit() }

    /**
     * 开机自检的戳：BootReceiver 收到 BOOT_COMPLETED 时按下。
     * bootStamp = 用 wall clock 反推的「本次开机时刻」，bootSeenAt = 收到广播的时刻。
     * 两者只要和「当前这次开机」对得上，就说明自启动是真开着的 —— 这条骗不了人。
     */
    var bootStamp: Long
        get() = sp.getLong(K_BOOT_STAMP, 0L)
        set(value) { sp.edit().putLong(K_BOOT_STAMP, value).commit() }

    var bootSeenAt: Long
        get() = sp.getLong(K_BOOT_SEEN, 0L)
        set(value) { sp.edit().putLong(K_BOOT_SEEN, value).commit() }

    fun resetFreezeStats() {
        sp.edit().putInt(K_FREEZE_N, 0).putLong(K_FREEZE_MAX, 0L).putLong(K_FREEZE_LAST, 0L).apply()
    }

    /** 是否记录诊断日志 */
    var traceEnabled: Boolean
        get() = sp.getBoolean(K_TRACE, true)
        set(value) = sp.edit().putBoolean(K_TRACE, value).apply()

    /**
     * 「立即测试锁定」按下去之后的持续锁。
     * 必须落盘：ColorOS 会杀进程，状态只在内存里的话，进程一死锁就没了。
     */
    var manualHold: Boolean
        get() = sp.getBoolean(K_MANUAL_HOLD, false)
        set(value) { sp.edit().putBoolean(K_MANUAL_HOLD, value).commit() }

    /**
     * 电话让路的有效期（**墙钟**毫秒，跨重启也不会算错）。
     * > 0 = 正在让路：盯着前台，只要还在拨号盘/通话界面就不锁，离开就立刻锁回来。
     */
    var dialerUntil: Long
        get() = sp.getLong(K_DIALER_UNTIL, 0L)
        set(value) { sp.edit().putLong(K_DIALER_UNTIL, value).commit() }

    /** 进入锁定态的时刻（0 = 没锁）。落盘：进程被杀之后还要记得"我本来锁着" */
    var lockedAt: Long
        get() = sp.getLong(K_LOCKED_AT, 0L)
        set(value) { sp.edit().putLong(K_LOCKED_AT, value).commit() }

    /** 锁屏电话按钮要拨的号码；空 = 只打开拨号盘，由用户自己按拨号 */
    var callNumber: String?
        get() = sp.getString(K_CALL_NUMBER, null)
        set(value) = sp.edit().putString(K_CALL_NUMBER, value).apply()

    /** 用户导入的字体绝对路径 */
    var fontPath: String?
        get() = sp.getString(K_FONT, null)
        set(value) = sp.edit().putString(K_FONT, value).apply()

    fun clear() {
        sp.edit().clear().apply()
    }

    companion object {
        /** 解锁长按时长的下限：3 分钟 */
        const val MIN_HOLD = 180

        private const val K_ENABLED = "enabled"
        private const val K_HOUR = "hour"
        private const val K_MINUTE = "minute"
        private const val K_UHOUR = "unlock_hour"
        private const val K_UMINUTE = "unlock_minute"
        private const val K_HOLD = "hold_seconds"
        private const val K_LAST_UNLOCK = "last_unlock_at"
        private const val K_PIN = "pin_task"
        private const val K_CALLS = "allow_calls"
        private const val K_KEEP = "keep_screen_on"
        private const val K_TURN = "turn_screen_on"
        private const val K_MIC = "mic_mute"
        private const val K_FONT = "font_path"
        private const val K_CALL_NUMBER = "call_number"
        private const val K_MANUAL_HOLD = "manual_hold"
        private const val K_DIALER_UNTIL = "dialer_until"
        private const val K_LOCKED_AT = "locked_at"
        private const val K_TRACE = "trace_enabled"
        private const val K_FREEZE_N = "freeze_count"
        private const val K_FREEZE_MAX = "freeze_max_ms"
        private const val K_LAST_TICK = "last_tick_at"
        private const val K_FREEZE_LAST = "last_freeze_at"
        private const val K_PENDING = "pending_trigger"
        private const val K_LAST_LOCK = "last_lock_at"
        private const val K_BOOT_STAMP = "boot_stamp"
        private const val K_BOOT_SEEN = "boot_seen_at"
    }
}