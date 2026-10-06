package cn.gxnu.campus.ui.screens

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.VisionImageLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ImagePreparationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A ready-to-upload image plus a small copy the screen can show as a preview. */
class PreparedTimetableImage(val image: TimetableImage, val preview: Bitmap)

/**
 * Decodes with a power-of-two [BitmapFactory.Options.inSampleSize], stands the pixels upright,
 * scales into the upload box and re-encodes as JPEG. Every blocking step belongs on a background
 * dispatcher; [prepare] touches neither the main thread nor the network.
 */
internal object TimetableImageLoader {

    /** Comfortably below the documented 32 MiB single-image ceiling. */
    private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

    /** A source photo is held in memory only long enough to be downsampled; this bounds that hold. */
    private const val MAX_SOURCE_BYTES = 48 * 1024 * 1024

    private const val PREVIEW_SIDE_PX = 720
    private val JPEG_QUALITIES = intArrayOf(88, 80, 70, 55)

    fun prepare(resolver: ContentResolver, uri: Uri): PreparedTimetableImage {
        // The picked photo is read exactly once. A picker hands out a URI whose stream is expensive
        // to re-open — and may not re-open at all — so every later pass (bounds, EXIF, pixels) works
        // from these bytes rather than asking the provider for the image a second time.
        val source = resolver.openInputStream(uri)?.use { it.readBounded(MAX_SOURCE_BYTES) }
            ?: throw ImagePreparationException("无法打开这张图片，请换一张试试。")
        if (source.isEmpty()) throw ImagePreparationException("这张图片是空的，请换一张试试。")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImagePreparationException("这张图片的格式无法识别，请换一张试试。")
        }
        val rotation = exifRotation(source)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: throw ImagePreparationException("这张图片读取失败，请重新选择。")
        // A camera photo is usually stored landscape with an "rotate me" tag; a timetable sent
        // sideways is far harder to read, so the rotation is baked in before anything else.
        val upright = if (rotation == 0) decoded else rotate(decoded, rotation)
        val target = VisionImageLimits.fitInside(upright.width, upright.height, VisionImageLimits.TARGET_SIDE_PX)
        val scaled = if (target.width == upright.width && target.height == upright.height) {
            upright
        } else {
            try {
                Bitmap.createScaledBitmap(upright, target.width, target.height, true)
            } finally {
                upright.recycle()
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

    /** Bounded so a corrupt or absurd source cannot be slurped into memory in one go. */
    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                throw ImagePreparationException("这张图片太大，手机无法处理，请换一张分辨率低一些的照片。")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Halves the decoded size until the long side first fits the upload box. */
    fun sampleSize(width: Int, height: Int, target: Int = VisionImageLimits.TARGET_SIDE_PX): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > target) sample *= 2
        return sample
    }

    /** The size a decoded bitmap occupies once [rotationDegrees] is applied to it. */
    fun displaySize(width: Int, height: Int, rotationDegrees: Int): VisionImageLimits.ImageSize {
        val safeWidth = maxOf(1, width)
        val safeHeight = maxOf(1, height)
        return if (rotationDegrees % 180 == 0) {
            VisionImageLimits.ImageSize(safeWidth, safeHeight)
        } else {
            VisionImageLimits.ImageSize(safeHeight, safeWidth)
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /**
     * How far the camera had to rotate the sensor to record this photo. Only the pure rotations are
     * honoured — a mirrored tag is left alone rather than guessed at. A file with no readable EXIF
     * (a screenshot, a PNG, a downloaded image) simply reports 0.
     *
     * This is the platform reader, not androidx's: the app carries no extra dependency for one tag,
     * and the platform class still reads the orientation on every supported API level.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("ExifInterface")
    private fun exifRotation(source: ByteArray): Int = try {
        val exif = android.media.ExifInterface(ByteArrayInputStream(source))
        when (exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90,
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270,
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
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
