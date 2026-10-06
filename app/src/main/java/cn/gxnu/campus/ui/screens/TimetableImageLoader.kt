package cn.gxnu.campus.ui.screens

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.VisionImageLimits
import java.io.ByteArrayOutputStream

class ImagePreparationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A ready-to-upload image plus a small copy the screen can show as a preview. */
class PreparedTimetableImage(val image: TimetableImage, val preview: Bitmap)

/**
 * Decodes with a power-of-two [BitmapFactory.Options.inSampleSize], scales into the upload box and
 * re-encodes as JPEG. Every blocking step belongs on a background dispatcher; [prepare] touches
 * neither the main thread nor the network.
 */
internal object TimetableImageLoader {

    /** Comfortably below the documented 32 MiB single-image ceiling. */
    private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    private const val PREVIEW_SIDE_PX = 720
    private val JPEG_QUALITIES = intArrayOf(88, 80, 70, 55)

    fun prepare(resolver: ContentResolver, uri: Uri): PreparedTimetableImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw ImagePreparationException("无法读取这张图片，请换一张试试。")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImagePreparationException("这张图片无法解码，请换一张试试。")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw ImagePreparationException("无法读取这张图片，请换一张试试。")
        val target = VisionImageLimits.fitInside(decoded.width, decoded.height, VisionImageLimits.TARGET_SIDE_PX)
        val scaled = if (target.width == decoded.width && target.height == decoded.height) {
            decoded
        } else {
            try {
                Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
            } finally {
                decoded.recycle()
            }
        }
        val encoded = try {
            encodeUnderLimit(scaled)
        } catch (failure: Exception) {
            scaled.recycle()
            throw failure
        }
        val preview = if (maxOf(scaled.width, scaled.height) > PREVIEW_SIDE_PX) {
            val boundsForPreview = VisionImageLimits.fitInside(scaled.width, scaled.height, PREVIEW_SIDE_PX)
            try {
                Bitmap.createScaledBitmap(scaled, boundsForPreview.width, boundsForPreview.height, true)
            } finally {
                scaled.recycle()
            }
        } else {
            scaled
        }
        return PreparedTimetableImage(TimetableImage(encoded, "image/jpeg"), preview)
    }

    /** Halves the decoded size until the long side first fits the upload box. */
    fun sampleSize(width: Int, height: Int, target: Int = VisionImageLimits.TARGET_SIDE_PX): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > target) sample *= 2
        return sample
    }

    /** A real JPEG is always produced, so the API's content sniffing never disagrees with us. */
    private fun encodeUnderLimit(bitmap: Bitmap): ByteArray {
        var lastSize = 0
        for (quality in JPEG_QUALITIES) {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) ByteArray(0) else output.toByteArray()
            }
            if (bytes.isEmpty()) throw ImagePreparationException("图片压缩失败，请换一张试试。")
            if (bytes.size <= MAX_UPLOAD_BYTES) return bytes
            lastSize = bytes.size
        }
        throw ImagePreparationException("图片压缩后仍然过大（约 ${lastSize / 1024 / 1024} MB），请换一张照片。")
    }

    /**
     * A full-resolution capture target for the camera. Below API 29 an app-owned MediaStore entry
     * needs WRITE_EXTERNAL_STORAGE, so the screen offers library picking only there.
     */
    fun createCaptureTarget(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "timetable_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                // Private to this app until the camera has written the pixels.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })
        } catch (_: Exception) {
            null
        }
    }

    /** The captured frame is only input for recognition, so it never stays in the user's gallery. */
    fun discardCaptureTarget(context: Context, uri: Uri) {
        try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
    }
}
