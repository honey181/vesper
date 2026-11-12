package com.vesper.app.data.local

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs

data class ScreenSpec(
    val widthPx: Int,
    val heightPx: Int,
    val aspectRatioFloat: Float
)

object DeviceDisplayHelper {

    /**
     * Supported aspect ratios for Gemini Nano Banana 2.1 (`imageConfig.aspectRatio`).
     */
    val SUPPORTED_RATIOS: List<Pair<String, Float>> = listOf(
        "9:16" to (9f / 16f),
        "2:3" to (2f / 3f),
        "3:4" to (3f / 4f),
        "4:5" to (4f / 5f),
        "1:4" to (1f / 4f),
        "1:8" to (1f / 8f),
        "1:1" to 1f,
        "16:9" to (16f / 9f),
        "3:2" to (3f / 2f),
        "4:3" to (4f / 3f),
        "5:4" to (5f / 4f),
        "21:9" to (21f / 9f)
    )

    fun readRealScreenSpec(context: Context): ScreenSpec {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var width = 1080
        var height = 2400

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width().coerceAtLeast(1)
            height = bounds.height().coerceAtLeast(1)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                width = metrics.widthPixels
                height = metrics.heightPixels
            }
        }

        // Always normalize to portrait orientation for phone wallpaper sizing unless landscape tablet
        val w = minOf(width, height)
        val h = maxOf(width, height)
        return ScreenSpec(w, h, w.toFloat() / h.toFloat())
    }

    /**
     * Selects the closest Gemini-supported aspect ratio for the target screen dimensions.
     * Note: 20:9 / 19.5:9 smartphones (e.g., 1280x2856) map cleanly to "9:16"
     * and are then center-cropped on-device to exact pixel proportions.
     */
    fun bestSupportedAspectRatio(widthPx: Int, heightPx: Int, override: String): String {
        if (override != "AUTO" && SUPPORTED_RATIOS.any { it.first == override }) {
            return override
        }
        val targetRatio = widthPx.toFloat() / heightPx.coerceAtLeast(1).toFloat()
        // For tall modern phones (~0.42–0.56), prefer 9:16 over 1:4 so composition isn't distorted into a ribbon.
        val candidates = if (targetRatio in 0.38f..0.60f) {
            listOf("9:16" to (9f / 16f), "2:3" to (2f / 3f))
        } else {
            SUPPORTED_RATIOS
        }
        return candidates.minByOrNull { abs(it.second - targetRatio) }?.first ?: "9:16"
    }
}
