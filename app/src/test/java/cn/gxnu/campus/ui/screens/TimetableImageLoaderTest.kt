package cn.gxnu.campus.ui.screens

import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.VisionImageLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure part of the image pipeline: how far a photo is downsampled before upload, what the picked
 * bytes really are once the provider's own MIME claim is ignored, and which encoding the upload copy
 * is allowed to be sent as.
 */
class TimetableImageLoaderTest {

    @Test fun aPhotoInsideTheUploadBoxIsNotDownsampled() {
        assertEquals(1, TimetableImageLoader.sampleSize(2048, 2048, 2048))
        assertEquals(1, TimetableImageLoader.sampleSize(2000, 1500, 2048))
        assertEquals(1, TimetableImageLoader.sampleSize(1024, 2048, 2048))
    }

    @Test fun aLargePhotoIsHalvedUntilItFits() {
        assertEquals(2, TimetableImageLoader.sampleSize(4000, 3000, 2048))
        assertEquals(2, TimetableImageLoader.sampleSize(600, 3264, 2048))
        assertEquals(8, TimetableImageLoader.sampleSize(12000, 9000, 2048))
        assertEquals(128, TimetableImageLoader.sampleSize(200000, 100000, 2048))
    }

    @Test fun degenerateBoundsNeverDivideByZero() {
        assertEquals(1, TimetableImageLoader.sampleSize(0, 0, 2048))
        assertEquals(1, TimetableImageLoader.sampleSize(-10, 4000, 2048))
        assertEquals(1, TimetableImageLoader.sampleSize(4000, 4000, 0))
    }

    @Test fun aQuarterTurnSwapsTheDimensions() {
        assertEquals(VisionImageLimits.ImageSize(3000, 4000), TimetableImageLoader.displaySize(4000, 3000, 90))
        assertEquals(VisionImageLimits.ImageSize(4000, 3000), TimetableImageLoader.displaySize(3000, 4000, 270))
    }

    @Test fun halfTurnsAndNoRotationKeepTheDimensions() {
        assertEquals(VisionImageLimits.ImageSize(3000, 4000), TimetableImageLoader.displaySize(3000, 4000, 0))
        assertEquals(VisionImageLimits.ImageSize(3000, 4000), TimetableImageLoader.displaySize(3000, 4000, 180))
        assertEquals(VisionImageLimits.ImageSize(3000, 4000), TimetableImageLoader.displaySize(3000, 4000, -180))
    }

    @Test fun degenerateSizesStillReportATleastOnePixel() {
        assertEquals(VisionImageLimits.ImageSize(1, 1), TimetableImageLoader.displaySize(0, 0, 90))
        assertEquals(VisionImageLimits.ImageSize(1, 5), TimetableImageLoader.displaySize(5, 0, 90))
    }

    @Test fun noSampleSizeEverLeavesTheLongSideAboveTheUploadBox() {
        val target = VisionImageLimits.TARGET_SIDE_PX
        for (width in listOf(1, 1023, 4095, 4096, 4097, 5000, 8192, 8193, 12000, 40000)) {
            for (height in listOf(1, 1024, 3072, 4096, 4097, 9000, 30000)) {
                val sample = TimetableImageLoader.sampleSize(width, height)
                assertTrue(
                    "sampleSize($width, $height) = $sample leaves the long side above $target",
                    maxOf(width, height) / sample <= target
                )
            }
        }
    }

    @Test fun noSampleSizeCrushesThePhotoFarBelowTheUploadBox() {
        val target = VisionImageLimits.TARGET_SIDE_PX
        for (side in listOf(4097, 4098, 8192, 8193, 12000, 16384, 100000)) {
            val sample = TimetableImageLoader.sampleSize(side, side)
            assertTrue(
                "sampleSize($side, $side) = $sample halves away most of the pixels the model needs",
                side / sample >= target / 2
            )
        }
    }

    @Test fun theSourceFormatIsDecidedByTheMagicBytes() {
        assertEquals(
            TimetableImageLoader.SourceFormat.PNG,
            TimetableImageLoader.sourceFormat(bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D))
        )
        assertEquals(
            TimetableImageLoader.SourceFormat.JPEG,
            TimetableImageLoader.sourceFormat(bytesOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46))
        )
        assertEquals(
            TimetableImageLoader.SourceFormat.WEBP,
            TimetableImageLoader.sourceFormat(bytesOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50))
        )
    }

    @Test fun bytesThatAreNotAnImageAtAllAreUnknown() {
        assertEquals(TimetableImageLoader.SourceFormat.UNKNOWN, TimetableImageLoader.sourceFormat(ByteArray(0)))
        assertEquals(TimetableImageLoader.SourceFormat.UNKNOWN, TimetableImageLoader.sourceFormat(bytesOf(0x00, 0x01, 0x02, 0x03)))
        // A GIF decodes fine, but it is not a PNG, so it earns no lossless pass-through.
        assertEquals(
            TimetableImageLoader.SourceFormat.UNKNOWN,
            TimetableImageLoader.sourceFormat(bytesOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
        )
        // "RIFF" also opens a WAV; only the tag at offset 8 makes it a WebP image.
        assertEquals(
            TimetableImageLoader.SourceFormat.UNKNOWN,
            TimetableImageLoader.sourceFormat(bytesOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45))
        )
    }

    @Test fun aTruncatedSignatureIsNotMistakenForACompleteOne() {
        assertEquals(TimetableImageLoader.SourceFormat.UNKNOWN, TimetableImageLoader.sourceFormat(bytesOf(0x89, 0x50, 0x4E, 0x47)))
        assertEquals(TimetableImageLoader.SourceFormat.UNKNOWN, TimetableImageLoader.sourceFormat(bytesOf(0xFF, 0xD8)))
        assertEquals(
            TimetableImageLoader.SourceFormat.UNKNOWN,
            TimetableImageLoader.sourceFormat(bytesOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00))
        )
    }

    @Test fun aPngWithinTheBudgetIsUploadedAsPng() {
        assertEquals("image/png", TimetableImageLoader.uploadMimeType(TimetableImageLoader.SourceFormat.PNG, 1024))
        assertEquals(
            "image/png",
            TimetableImageLoader.uploadMimeType(TimetableImageLoader.SourceFormat.PNG, TimetableImageLoader.MAX_UPLOAD_BYTES)
        )
    }

    @Test fun aPngTooLargeForTheBudgetFallsBackToJpeg() {
        assertEquals(
            "image/jpeg",
            TimetableImageLoader.uploadMimeType(TimetableImageLoader.SourceFormat.PNG, TimetableImageLoader.MAX_UPLOAD_BYTES + 1)
        )
    }

    @Test fun aLosslessEncodingThatProducedNothingIsNotSentAsPng() {
        assertEquals("image/jpeg", TimetableImageLoader.uploadMimeType(TimetableImageLoader.SourceFormat.PNG, 0))
    }

    @Test fun onlyAPngSourceCanBeSentAsPng() {
        val notPng = listOf(
            TimetableImageLoader.SourceFormat.JPEG,
            TimetableImageLoader.SourceFormat.WEBP,
            TimetableImageLoader.SourceFormat.UNKNOWN
        )
        for (format in notPng) {
            assertEquals("image/jpeg", TimetableImageLoader.uploadMimeType(format, 1024))
        }
    }

    @Test fun theUploadBudgetIsTheLargestPayloadTheEndpointStillAccepts() {
        // 24 MiB of bytes is exactly the documented 32 MiB base64 ceiling; one byte more is not.
        assertEquals(25_165_824, TimetableImageLoader.MAX_UPLOAD_BYTES)
        assertTrue(
            VisionImageLimits.base64Length(TimetableImageLoader.MAX_UPLOAD_BYTES) <= VisionImageLimits.MAX_BASE64_BYTES
        )
        assertTrue(
            VisionImageLimits.base64Length(TimetableImageLoader.MAX_UPLOAD_BYTES + 1) > VisionImageLimits.MAX_BASE64_BYTES
        )
        assertNull(
            VisionImageLimits.rejectionReason(
                TimetableImage(ByteArray(TimetableImageLoader.MAX_UPLOAD_BYTES), "image/png")
            )
        )
    }

    @Test fun theBudgetLeavesRoomForAQ88PhotoAtTheUploadResolution() {
        // A 4:3 photo at the long-side limit is 4096 × 3072 px, and dense timetable text can reach
        // roughly a byte per pixel at q88. The old 8 MiB budget dropped those straight to q55; this
        // is the regression that would quietly cost recognition accuracy.
        val denseQ88Bytes = VisionImageLimits.TARGET_SIDE_PX * (VisionImageLimits.TARGET_SIDE_PX * 3 / 4)
        assertTrue(denseQ88Bytes > 8 * 1024 * 1024)
        assertTrue(TimetableImageLoader.MAX_UPLOAD_BYTES >= denseQ88Bytes)
    }

    @Test fun theJpegLadderStopsAtTheFirstQualityThatFits() {
        val tried = mutableListOf<Int>()
        val bytes = TimetableImageLoader.encodeJpegUnderLimit(limit = 4_000) { quality ->
            tried += quality
            ByteArray(if (quality == 88) 4_000 else 40_000)
        }
        assertEquals(listOf(88), tried)
        assertEquals(4_000, bytes.size)
    }

    @Test fun theJpegLadderOnlyStepsDownWhenThePayloadDoesNotFit() {
        val tried = mutableListOf<Int>()
        val bytes = TimetableImageLoader.encodeJpegUnderLimit(limit = 4_000) { quality ->
            tried += quality
            ByteArray(if (quality >= 70) 40_000 else 1_000)
        }
        assertEquals(listOf(88, 80, 70, 55), tried)
        assertEquals(1_000, bytes.size)
    }

    @Test fun aPhotoThatFitsNoQualityFailsInsteadOfUploadingSomethingEnormous() {
        try {
            TimetableImageLoader.encodeJpegUnderLimit(limit = 4_000) { ByteArray(40_000) }
            throw AssertionError("expected the ladder to give up once q55 was still over budget")
        } catch (failure: ImagePreparationException) {
            assertTrue(failure.message.orEmpty().contains("过大"))
        }
    }

    @Test fun aCompressorThatProducesNothingIsAFailureRatherThanAnEmptyUpload() {
        try {
            TimetableImageLoader.encodeJpegUnderLimit(limit = 4_000) { ByteArray(0) }
            throw AssertionError("expected an empty compression result to be refused")
        } catch (failure: ImagePreparationException) {
            assertTrue(failure.message.orEmpty().isNotEmpty())
        }
    }

    @Test fun anAllocationFailureBecomesAReadableImageFailure() {
        val failure = try {
            TimetableImageLoader.withMemoryGuard<ByteArray> {
                throw OutOfMemoryError("Failed to allocate a 25165836 byte allocation")
            }
            throw AssertionError("expected the allocation failure to be translated")
        } catch (failure: ImagePreparationException) {
            failure
        }
        assertEquals("这张图片太大，手机无法处理，请换一张分辨率低一些的照片。", failure.message)
        assertTrue(failure.cause is OutOfMemoryError)
    }

    @Test fun anUntouchedSourceStillHasToRespectTheEndpointPixelCeiling() {
        // A 1080x20000 scroll capture samples down to a tiny bitmap, so nothing looks resized while
        // the bytes about to be uploaded are still 20000 px on the long side.
        assertTrue(TimetableImageLoader.mayPassThrough(rotationDegrees = 0, resized = false, sourceSide = 4_096))
        assertTrue(TimetableImageLoader.mayPassThrough(rotationDegrees = 0, resized = false, sourceSide = 8_192))
        assertTrue(!TimetableImageLoader.mayPassThrough(rotationDegrees = 0, resized = false, sourceSide = 8_193))
        assertTrue(!TimetableImageLoader.mayPassThrough(rotationDegrees = 0, resized = false, sourceSide = 20_000))
        // A rotated or resized source has to be re-encoded anyway, so it is never a pass-through.
        assertTrue(!TimetableImageLoader.mayPassThrough(rotationDegrees = 90, resized = false, sourceSide = 1_200))
        assertTrue(!TimetableImageLoader.mayPassThrough(rotationDegrees = 0, resized = true, sourceSide = 1_200))
        assertEquals(8_192, VisionImageLimits.MAX_SIDE_PX)
    }

    @Test fun theUploadTargetStaysUnderTheEndpointCeiling() {
        // The downscale target must be lower than the ceiling, or every large photo would be uploaded
        // past the limit the endpoint documents.
        assertTrue(VisionImageLimits.TARGET_SIDE_PX < VisionImageLimits.MAX_SIDE_PX)
    }

    @Test fun aGuardedStepThatSucceedsReturnsItsValue() {
        assertEquals("seven", TimetableImageLoader.withMemoryGuard { "sev" + "en" })
    }

    @Test fun theGuardLeavesTheFailuresTheScreenAlreadyReadsUntouched() {
        try {
            TimetableImageLoader.withMemoryGuard<Unit> { throw ImagePreparationException("这张图片是空的，请换一张试试。") }
            throw AssertionError("expected the failure to travel through the guard unchanged")
        } catch (failure: ImagePreparationException) {
            assertEquals("这张图片是空的，请换一张试试。", failure.message)
        }
    }

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
