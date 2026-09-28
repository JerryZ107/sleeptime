package com.sleeptime.lock

import android.content.Context
import android.graphics.Typeface
import java.io.File

object FontLoader {

    const val FONT_FILE = "sleeptime_font.ttf"
    private const val ASSET_FONT = "fonts/orbitron.ttf"

    /** 优先级：用户导入 > assets 内置 > 系统超粗体 */
    fun load(ctx: Context): Typeface {
        Prefs(ctx).fontPath?.let { path ->
            if (path.isNotEmpty()) {
                val f = File(path)
                if (f.exists()) {
                    runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { return it }
                }
            }
        }
        runCatching { Typeface.createFromAsset(ctx.assets, ASSET_FONT) }
            .getOrNull()?.let { return it }

        return runCatching { Typeface.create("sans-serif-black", Typeface.BOLD) }
            .getOrNull() ?: Typeface.MONOSPACE
    }

    fun describe(ctx: Context): String {
        Prefs(ctx).fontPath?.let { path ->
            if (path.isNotEmpty() && File(path).exists()) return "已导入：${File(path).name}"
        }
        val hasAsset = runCatching { ctx.assets.open(ASSET_FONT).close() }.isSuccess
        return if (hasAsset) {
            "内置字体：assets/$ASSET_FONT"
        } else {
            "内置字体缺失，当前用系统超粗体兜底（把 orbitron.ttf 放进 assets/fonts/ 即可）"
        }
    }
}