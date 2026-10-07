package cn.gxnu.campus.ui.screens

import cn.gxnu.campus.core.WordDocuments
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the Word import makes before the controller ever sees the bytes: which files the
 * picker offers, how far a stream may be read, and which file is refused for its size alone. All of
 * it is Android-free on purpose — the rest of the import entry point is Context and Uri plumbing.
 */
class TimetableScreenTest {

    @Test fun thePickerOffersBothWordTypesAndAPlainFallback() {
        assertEquals(
            listOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                // A provider that filters on the exact type list greys the file out; the plain
                // wildcard last keeps the real document reachable, and the bytes are sniffed anyway.
                "*/*"
            ),
            WordImportFiles.MIME_TYPES.toList()
        )
    }

    @Test fun aFileInsideTheLimitIsReadWhole() {
        val bytes = ByteArray(4096) { it.toByte() }
        assertEquals(bytes.toList(), WordImportFiles.readAtMost(ByteArrayInputStream(bytes))!!.toList())
    }

    @Test fun aStreamThatEndsExactlyOnTheLimitIsStillRead() {
        assertEquals(4096, WordImportFiles.readAtMost(ByteArrayInputStream(ByteArray(4096)), limit = 4096)!!.size)
    }

    @Test fun aStreamPastTheLimitIsRefusedInsteadOfReadIntoTheHeap() {
        assertNull(WordImportFiles.readAtMost(ByteArrayInputStream(ByteArray(4097)), limit = 4096))
    }

    @Test fun aFilePastTheWordLimitIsNamedInTheNotice() {
        val notice = WordImportFiles.oversizeNotice("课表.doc", WordDocuments.MAX_BYTES + 1L)
        assertTrue("the notice has to name the file: $notice", notice!!.contains("课表.doc"))
        assertTrue("the notice has to say how big a 课表 may be: $notice", notice.contains("24 MB"))
    }

    @Test fun aFileAtTheLimitIsNotRefused() {
        assertNull(WordImportFiles.oversizeNotice("课表.doc", WordDocuments.MAX_BYTES.toLong()))
    }
}
