package com.vesper.app.data.wallpaper

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vesper.app.domain.model.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ProcessedWallpaperPaths(
    val rawMasterPath: String,
    val lockImagePath: String,
    val homeImagePath: String?
)

class WallpaperProcessor(private val context: Context) {

    private val wallpapersDir = File(context.filesDir, "wallpapers").apply { mkdirs() }

    suspend fun processAndSave(
        id: String,
        rawBytes: ByteArray,
        mimeType: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        exactPixelFit: Boolean,
        createCalmHomeTwin: Boolean,
        homeBlurRadius: Float,
        homeDimAlpha: Float,
        secondScreenRawBytes: ByteArray? = null
    ): ProcessedWallpaperPaths = withContext(Dispatchers.IO) {
        val ext = if (mimeType.contains("png", ignoreCase = true)) "png" else "jpg"
        val rawFile = File(wallpapersDir, "raw_$id.$ext")
        rawFile.writeBytes(rawBytes)

        val decoded = decodeBitmapSafely(rawBytes)
            ?: throw IllegalStateException("Generated image bytes could not be decoded into a valid bitmap")

        val fittedLock = fitToPhoneDimensions(decoded, targetWidthPx, targetHeightPx, exactPixelFit)
        if (fittedLock !== decoded) decoded.recycle()

        val lockFile = File(wallpapersDir, "lock_$id.png")
        FileOutputStream(lockFile).use { out ->
            fittedLock.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        var homeFilePath: String? = null
        if (secondScreenRawBytes != null) {
            val decodedHome = decodeBitmapSafely(secondScreenRawBytes)
                ?: throw IllegalStateException("Second screen image bytes could not be decoded")
            val fittedHome = fitToPhoneDimensions(decodedHome, targetWidthPx, targetHeightPx, exactPixelFit)
            if (fittedHome !== decodedHome) decodedHome.recycle()

            val homeFile = File(wallpapersDir, "home_$id.png")
            FileOutputStream(homeFile).use { out ->
                fittedHome.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            fittedHome.recycle()
            homeFilePath = homeFile.absolutePath
        } else if (createCalmHomeTwin) {
            val calmBitmap = createCalmTwinBitmap(fittedLock, homeBlurRadius, homeDimAlpha)
            val homeFile = File(wallpapersDir, "home_$id.png")
            FileOutputStream(homeFile).use { out ->
                calmBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            calmBitmap.recycle()
            homeFilePath = homeFile.absolutePath
        }

        fittedLock.recycle()
        ProcessedWallpaperPaths(
            rawMasterPath = rawFile.absolutePath,
            lockImagePath = lockFile.absolutePath,
            homeImagePath = homeFilePath
        )
    }

    suspend fun applyWallpapers(
        lockImagePath: String,
        homeImagePath: String?,
        target: WallpaperTarget
    ) = withContext(Dispatchers.IO) {
        val wm = WallpaperManager.getInstance(context)
        val lockBitmap = BitmapFactory.decodeFile(lockImagePath)
            ?: throw IllegalStateException("Lock image file missing or unreadable")

        try {
            when (target) {
                WallpaperTarget.LOCK_AND_CALM_HOME,
                WallpaperTarget.SEPARATE_GENERATIONS -> {
                    wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK)
                    val homeBmp = homeImagePath?.let { BitmapFactory.decodeFile(it) }
                    if (homeBmp != null) {
                        try {
                            wm.setBitmap(homeBmp, null, true, WallpaperManager.FLAG_SYSTEM)
                        } finally {
                            homeBmp.recycle()
                        }
                    } else {
                        wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    }
                }
                WallpaperTarget.IDENTICAL_BOTH -> {
                    wm.setBitmap(
                        lockBitmap,
                        null,
                        true,
                        WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM
                    )
                }
                WallpaperTarget.LOCK_ONLY -> {
                    wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK)
                }
                WallpaperTarget.HOME_ONLY -> {
                    val homeBmp = homeImagePath?.let { BitmapFactory.decodeFile(it) }
                    if (homeBmp != null) {
                        try {
                            wm.setBitmap(homeBmp, null, true, WallpaperManager.FLAG_SYSTEM)
                        } finally {
                            homeBmp.recycle()
                        }
                    } else {
                        wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    }
                }
            }
        } finally {
            lockBitmap.recycle()
        }
    }

    suspend fun exportToGallery(sourcePath: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return@withContext false

        val isPng = sourceFile.extension.equals("png", ignoreCase = true)
        val mime = if (isPng) "image/png" else "image/jpeg"
        val cleanName = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "vesper_wallpaper" }
        val fileName = "${cleanName}_${System.currentTimeMillis()}.${sourceFile.extension}"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vesper")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false

        return@withContext runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }

    private fun decodeBitmapSafely(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Center-crops to exact screen aspect ratio (zero distortion) and optionally scales to exact
     * native pixel dimensions (e.g., 1280x2856) for razor-sharp supersampled display.
     */
    private fun fitToPhoneDimensions(
        source: Bitmap,
        targetWidthPx: Int,
        targetHeightPx: Int,
        exactPixelFit: Boolean
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val targetAspect = targetWidthPx.toFloat() / targetHeightPx.coerceAtLeast(1).toFloat()
        val srcAspect = srcW.toFloat() / srcH.coerceAtLeast(1).toFloat()

        val cropped = if (abs(srcAspect - targetAspect) > 0.01f) {
            if (srcAspect > targetAspect) {
                // Source is wider than target phone screen -> trim left/right evenly
                val cropW = (srcH * targetAspect).roundToInt().coerceIn(1, srcW)
                val xOffset = ((srcW - cropW) / 2f).roundToInt().coerceAtLeast(0)
                Bitmap.createBitmap(source, xOffset, 0, cropW, srcH)
            } else {
                // Source is taller than target screen -> bias slightly toward top to protect upper sky/clock void
                val cropH = (srcW / targetAspect).roundToInt().coerceIn(1, srcH)
                val yOffset = ((srcH - cropH) * 0.25f).roundToInt().coerceAtLeast(0)
                Bitmap.createBitmap(source, 0, yOffset, srcW, cropH)
            }
        } else {
            source
        }

        if (!exactPixelFit || (cropped.width == targetWidthPx && cropped.height == targetHeightPx)) {
            return cropped
        }

        val scaled = Bitmap.createScaledBitmap(cropped, targetWidthPx, targetHeightPx, true)
        if (cropped !== source) cropped.recycle()
        return scaled
    }

    /**
     * Creates an icon-friendly Home Screen variation on-device using fast downsampled StackBlur,
     * gentle desaturation, and a subtle dark atmospheric scrim.
     */
    private fun createCalmTwinBitmap(
        source: Bitmap,
        blurRadius: Float,
        dimAlpha: Float
    ): Bitmap {
        val width = source.width
        val height = source.height
        val radius = blurRadius.roundToInt().coerceIn(0, 32)

        val blurredBase = if (radius > 0) {
            val scaleFactor = 3
            val smallW = (width / scaleFactor).coerceAtLeast(32)
            val smallH = (height / scaleFactor).coerceAtLeast(32)
            val downscaled = Bitmap.createScaledBitmap(source, smallW, smallH, true)
            val stacked = stackBlur(downscaled, (radius / 2).coerceAtLeast(2))
            if (stacked !== downscaled) downscaled.recycle()
            val upscaled = Bitmap.createScaledBitmap(stacked, width, height, true)
            stacked.recycle()
            upscaled
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val saturationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val matrix = ColorMatrix().apply { setSaturation(0.76f) }
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(blurredBase, 0f, 0f, saturationPaint)
        blurredBase.recycle()

        if (dimAlpha > 0f) {
            val scrimAlpha = (dimAlpha.coerceIn(0f, 0.75f) * 255).roundToInt()
            canvas.drawColor(Color.argb(scrimAlpha, 8, 10, 14))
        }
        return result
    }

    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yi = 0
        yw = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0; gsum = 0; rsum = 0
            boutsum = 0; goutsum = 0; routsum = 0
            binsum = 0; ginsum = 0; rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++; x++
            }
            yw += w; y++
        }

        x = 0
        while (x < w) {
            bsum = 0; gsum = 0; rsum = 0
            boutsum = 0; goutsum = 0; routsum = 0
            binsum = 0; ginsum = 0; rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w; y++
            }
            x++
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
