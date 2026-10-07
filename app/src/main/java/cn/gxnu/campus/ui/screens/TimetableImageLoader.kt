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
 * scales into the upload box and encodes the upload copy — losslessly when the source was a PNG,
 * otherwise along the JPEG quality ladder. Every blocking step belongs on a background dispatcher;
 * [prepare] touches neither the main thread nor the network.
 */
internal object TimetableImageLoader {

    private const val MIME_PNG = "image/png"
    private const val MIME_JPEG = "image/jpeg"

    /**
     * The upload budget, derived from the endpoint's own per-image ceiling instead of picked by hand:
     * the largest payload whose base64 form still fits [VisionImageLimits.MAX_BASE64_BYTES], which is
     * 24 MiB of bytes inflating to exactly 32 MiB of base64.
     *
     * It is deliberately generous. At [VisionImageLimits.TARGET_SIDE_PX] a 4:3 photo is 4096 × 3072 px
     * and dense text can reach roughly a byte per pixel at q88, so a smaller budget would push ordinary
     * timetables down to the low-quality rungs — and resolution is precisely what recognition accuracy
     * was measured to depend on.
     */
    const val MAX_UPLOAD_BYTES: Int = VisionImageLimits.MAX_BASE64_BYTES / 4 * 3

    /** A source photo is held in memory only long enough to be downsampled; this bounds that hold. */
    private const val MAX_SOURCE_BYTES = 48 * 1024 * 1024

    private const val PREVIEW_SIDE_PX = 720

    /** The JPEG ladder. The first rung that fits wins, so quality only drops when it has to. */
    private val JPEG_QUALITIES = intArrayOf(88, 80, 70, 55)

    /** One message for every "the phone could not hold these pixels" path. */
    private const val TOO_LARGE_MESSAGE = "这张图片太大，手机无法处理，请换一张分辨率低一些的照片。"

    /**
     * What the picked bytes really are. The provider's MIME type is a hint from a picker that may be
     * wrong or silent, and the endpoint sniffs the payload itself, so the bytes decide here too.
     */
    enum class SourceFormat { JPEG, PNG, WEBP, UNKNOWN }

    fun prepare(resolver: ContentResolver, uri: Uri): PreparedTimetableImage = withMemoryGuard {
        // The picked photo is read exactly once. A picker hands out a URI whose stream is expensive
        // to re-open — and may not re-open at all — so every later pass (bounds, EXIF, pixels) works
        // from these bytes rather than asking the provider for the image a second time.
        val source = resolver.openInputStream(uri)?.use { it.readBounded(MAX_SOURCE_BYTES) }
            ?: throw ImagePreparationException("无法打开这张图片，请换一张试试。")
        if (source.isEmpty()) throw ImagePreparationException("这张图片是空的，请换一张试试。")

        val format = sourceFormat(source)
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
        val resized = target.width != upright.width || target.height != upright.height
        val scaled = if (!resized) {
            upright
        } else {
            try {
                Bitmap.createScaledBitmap(upright, target.width, target.height, true)
            } finally {
                upright.recycle()
            }
        }
        // The original bytes may only travel untouched while they also respect the endpoint's pixel
        // ceiling. A very tall screenshot (a 1080x20000 scroll capture, say) samples down to a small
        // bitmap, so nothing looks "resized" — yet the bytes that would be uploaded are still 20000 px
        // on the long side, which the endpoint refuses with no fallback left to catch it.
        val upload = uploadCopy(
            source,
            format,
            scaled,
            untouched = mayPassThrough(rotation, resized, maxOf(bounds.outWidth, bounds.outHeight))
        )
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
        PreparedTimetableImage(upload, preview)
    }

    /**
     * Whether the source file's own bytes are a legal upload. `resized` only describes the decoded
     * bitmap, so a source whose long side is past the endpoint's ceiling must not pass through even
     * when the decoded copy happens to be small.
     */
    fun mayPassThrough(rotationDegrees: Int, resized: Boolean, sourceSide: Int): Boolean =
        rotationDegrees == 0 && !resized && sourceSide <= VisionImageLimits.MAX_SIDE_PX

    /**
     * The payload that goes to the endpoint. A PNG source travels losslessly — as its own bytes when
     * nothing had to be changed, or as a lossless re-encode of the resized pixels when something did —
     * because JPEG ringing around sharp glyphs is exactly the damage a screenshot must not pick up.
     * A PNG whose lossless form does not fit the budget falls back to the JPEG ladder, and so does
     * every other source.
     */
    private fun uploadCopy(source: ByteArray, format: SourceFormat, scaled: Bitmap, untouched: Boolean): TimetableImage {
        val lossless = when {
            format != SourceFormat.PNG -> null
            untouched -> source
            else -> compress(scaled, Bitmap.CompressFormat.PNG, 100)
        }
        if (lossless != null && uploadMimeType(format, lossless.size) == MIME_PNG) {
            return TimetableImage(lossless, MIME_PNG)
        }
        val jpeg = encodeJpegUnderLimit(MAX_UPLOAD_BYTES) { quality ->
            compress(scaled, Bitmap.CompressFormat.JPEG, quality)
        }
        return TimetableImage(jpeg, MIME_JPEG)
    }

    /**
     * Which encoding the upload copy may be sent as. Only a real PNG source qualifies, and only while
     * its lossless payload fits [MAX_UPLOAD_BYTES]; past the budget the JPEG ladder's smaller payload
     * wins, since an upload the endpoint would refuse helps nobody.
     */
    fun uploadMimeType(format: SourceFormat, losslessBytes: Int): String =
        if (format == SourceFormat.PNG && losslessBytes in 1..MAX_UPLOAD_BYTES) MIME_PNG else MIME_JPEG

    /**
     * What the picked bytes are, read from their magic numbers. Only a PNG earns the lossless path, so
     * the signature checks are deliberately complete: a truncated signature is [SourceFormat.UNKNOWN]
     * and takes the JPEG path along with everything else that is not recognised.
     */
    fun sourceFormat(bytes: ByteArray): SourceFormat = when {
        bytes.matchesAt(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> SourceFormat.PNG
        bytes.matchesAt(0, 0xFF, 0xD8, 0xFF) -> SourceFormat.JPEG
        // "RIFF" opens a WAV as readily as a WebP; the tag at offset 8 is what makes it an image.
        bytes.matchesAt(0, 0x52, 0x49, 0x46, 0x46) && bytes.matchesAt(8, 0x57, 0x45, 0x42, 0x50) -> SourceFormat.WEBP
        else -> SourceFormat.UNKNOWN
    }

    /**
     * Walks the JPEG quality ladder and keeps the first rung whose payload fits [limit], so quality is
     * never cut while the upload still fits — the lower rungs exist for photos that genuinely do not
     * fit. [encode] produces one rung's payload and is only asked for the rungs that are needed.
     */
    fun encodeJpegUnderLimit(limit: Int, encode: (Int) -> ByteArray): ByteArray {
        var smallest = 0
        for (quality in JPEG_QUALITIES) {
            val bytes = encode(quality)
            if (bytes.isEmpty()) throw ImagePreparationException("图片压缩失败，请换一张试试。")
            if (bytes.size <= limit) return bytes
            smallest = bytes.size
        }
        throw ImagePreparationException("图片压缩后仍然过大（约 ${smallest / 1024 / 1024} MB），请换一张照片。")
    }

    /**
     * Runs [block] and turns an allocation failure into the readable "too large" failure the screen
     * shows for it. [OutOfMemoryError] is an [Error], not an [Exception], so a phone that cannot hold
     * the decoded bitmap would otherwise take the process down instead of asking for a smaller photo.
     */
    fun <T> withMemoryGuard(block: () -> T): T = try {
        block()
    } catch (failure: OutOfMemoryError) {
        // A constant message: building one here would need the allocation that just failed.
        throw ImagePreparationException(TOO_LARGE_MESSAGE, failure)
    }

    private fun ByteArray.matchesAt(offset: Int, vararg expected: Int): Boolean {
        if (offset < 0 || size < offset + expected.size) return false
        for (index in expected.indices) {
            if ((this[offset + index].toInt() and 0xFF) != expected[index]) return false
        }
        return true
    }

    /** Bounded so a corrupt or absurd source cannot be slurped into memory in one go. */
    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw ImagePreparationException(TOO_LARGE_MESSAGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Encodes the bitmap; PNG ignores the quality argument and always writes lossless pixels. */
    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(format, quality, output)) ByteArray(0) else output.toByteArray()
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
