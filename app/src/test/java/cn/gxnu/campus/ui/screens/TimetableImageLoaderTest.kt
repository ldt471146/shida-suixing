package cn.gxnu.campus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure part of the image pipeline: how far a photo is downsampled before upload. */
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
}
