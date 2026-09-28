package com.sleeptime.lock

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * 锁屏上的手电筒开关。
 *
 * 为什么要有它：开了手电筒之后到点锁屏，黑幕把状态栏和下拉面板一起盖住，
 * 手电筒就关不掉了 —— 只能摸黑按住屏幕按满 3 分钟。所以干脆把开关做进黑幕里。
 *
 * 走的是 CameraManager.setTorchMode —— 和系统下拉面板里那个「手电筒」磁贴同一个 API，
 * 所以两边状态互通：磁贴开着，这里的按钮显示「开」；这里关掉，磁贴也跟着灭。
 */
object Torch {

    private const val TAG = "SleepTime"

    @Volatile
    var isOn: Boolean = false
        private set

    /** 状态变化回调（主线程） */
    @Volatile
    var onChanged: ((Boolean) -> Unit)? = null

    private var registered = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            isOn = enabled
            onChanged?.invoke(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            isOn = false
            onChanged?.invoke(false)
        }
    }

    /** 回调线程：直接在当前线程跑，View 那边自己会 post 回主线程 */
    private val directExecutor = java.util.concurrent.Executor { r -> r.run() }

    /** 锁定期间登记监听，让按钮状态跟系统磁贴保持一致（API 28+） */
    fun startWatch(ctx: Context) {
        if (registered) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        runCatching { cm.registerTorchCallback(directExecutor, callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "registerTorchCallback 失败", it) }
    }

    fun stopWatch(ctx: Context) {
        if (!registered) return
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        runCatching { cm.unregisterTorchCallback(callback) }
        registered = false
    }

    private fun flashCameraId(ctx: Context): String? {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return null
        return runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    /** 开/关手电筒。返回是否调用成功（失败原因进日志） */
    fun set(ctx: Context, on: Boolean): Boolean {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return false
        val id = flashCameraId(ctx)
        if (id == null) {
            Log.w(TAG, "没有可用的闪光灯")
            return false
        }
        val r = runCatching { cm.setTorchMode(id, on) }
        Log.i(TAG, "手电筒设为 $on -> " + (if (r.isSuccess) "OK" else "失败: " + r.exceptionOrNull()))
        return r.isSuccess
    }

    fun toggle(ctx: Context): Boolean = set(ctx, !isOn)
}