package cn.gxnu.campus.network

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download half, driven entirely by a fake transport. A build that ships without a usable APK
 * must never leave a file behind that the installer could be pointed at, so the file-system
 * outcome is asserted alongside the returned outcome.
 */
class ReleaseDownloadTest {

    @get:Rule val folder = TemporaryFolder()

    private val apkUrl = "https://github.com/owner/repo/releases/download/v0.4.1/shida-suixing-0.4.1.apk"
    private val chunk = 64 * 1024

    private fun apkBytes(chunks: Int): ByteArray {
        val body = ByteArray(chunks * chunk)
        body[0] = 0x50
        body[1] = 0x4B
        body[2] = 0x03
        body[3] = 0x04
        for (index in 4 until body.size) body[index] = (index % 251).toByte()
        return body
    }

    private fun updater(transport: ReleaseTransport, maxBytes: Long = 268_435_456) =
        ReleaseUpdater(transport, releaseApi = "https://api.github.com/repos/owner/repo/releases/latest", maxDownloadBytes = maxBytes)

    private fun download(transport: ReleaseTransport, target: File, maxBytes: Long = 268_435_456, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        runBlocking { updater(transport, maxBytes).download(apkUrl, target, onProgress) }

    @Test fun aDownloadedApkIsWrittenAndItsProgressReported() {
        val body = apkBytes(chunks = 3)
        val target = File(folder.root, "update.apk")
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = download(StreamTransport(body), target) { read, total -> progress += read to total }
        assertEquals(ApkDownload.Saved(target, body.size.toLong()), result)
        assertArrayEquals(body, target.readBytes())
        assertTrue("progress must advance", progress.size >= 3)
        assertEquals(body.size.toLong() to body.size.toLong(), progress.last())
        assertTrue("progress never goes backwards", progress.zipWithNext().all { it.first.first <= it.second.first })
    }

    @Test fun aBodyThatIsNotAZipIsRefusedAndLeavesNoFile() {
        val target = File(folder.root, "update.apk")
        val html = "<!DOCTYPE html><html>rate limited</html>".toByteArray()
        assertEquals(ApkDownload.Failed(DownloadFailure.NOT_AN_APK), download(StreamTransport(html), target))
        assertFalse(target.exists())
        assertFalse(File(folder.root, "update.apk.part").exists())
    }

    @Test fun anEmptyBodyIsRefused() {
        val target = File(folder.root, "update.apk")
        assertEquals(ApkDownload.Failed(DownloadFailure.NOT_AN_APK), download(StreamTransport(ByteArray(0)), target))
        assertFalse(target.exists())
    }

    @Test fun aBodyLargerThanTheDeclaredLimitIsAbandonedEarly() {
        val target = File(folder.root, "update.apk")
        val body = apkBytes(chunks = 4)
        assertEquals(ApkDownload.Failed(DownloadFailure.TOO_LARGE), download(StreamTransport(body), target, maxBytes = 2L * chunk))
        assertFalse(target.exists())
    }

    @Test fun aDeclaredLengthBeyondTheLimitIsRefusedBeforeAnyBytesAreWritten() {
        val target = File(folder.root, "update.apk")
        val body = apkBytes(chunks = 4)
        val transport = StreamTransport(body, declaredLength = 64L * 1024 * 1024)
        assertEquals(ApkDownload.Failed(DownloadFailure.TOO_LARGE), download(transport, target, maxBytes = 2L * chunk))
        assertFalse(target.exists())
    }

    @Test fun anHttpErrorIsAConnectionFailure() {
        val target = File(folder.root, "update.apk")
        val transport = object : ReleaseTransport {
            override suspend fun get(url: String, accept: String?) = throw AssertionError("no listing expected")
            override suspend fun open(url: String) = ReleaseStream(404, 0, ByteArrayInputStream(ByteArray(0)))
        }
        assertEquals(ApkDownload.Failed(DownloadFailure.NETWORK), download(transport, target))
        assertFalse(target.exists())
    }

    @Test fun anAbortedConnectionIsAConnectionFailure() {
        val target = File(folder.root, "update.apk")
        val transport = object : ReleaseTransport {
            override suspend fun get(url: String, accept: String?) = throw AssertionError("no listing expected")
            override suspend fun open(url: String): ReleaseStream = throw IOException("connection reset")
        }
        assertEquals(ApkDownload.Failed(DownloadFailure.NETWORK), download(transport, target))
        assertFalse(target.exists())
    }

    @Test fun aCancelledDownloadLeavesNoPartialFile() {
        val target = File(folder.root, "update.apk")
        val body = apkBytes(chunks = 4)
        var cancelled: CancellationException? = null
        try {
            download(StreamTransport(body) { index -> if (index == 1) throw CancellationException("user cancelled") }, target)
        } catch (failure: CancellationException) {
            cancelled = failure
        }
        assertNotNull("a cancelled download must not resolve to a result", cancelled)
        assertFalse(target.exists())
        assertFalse(File(folder.root, "update.apk.part").exists())
    }

    /** Streams [body] in [chunk]-sized reads, optionally failing on the given read index. */
    private inner class StreamTransport(
        private val body: ByteArray,
        private val declaredLength: Long = body.size.toLong(),
        private val failOnRead: ((Int) -> Unit)? = null
    ) : ReleaseTransport {
        override suspend fun get(url: String, accept: String?) = throw AssertionError("no listing expected")

        override suspend fun open(url: String): ReleaseStream {
            val source = object : InputStream() {
                private var position = 0
                private var reads = 0
                override fun read(): Int = if (position < body.size) body[position++].toInt() and 0xFF else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    failOnRead?.invoke(reads++)
                    if (position >= body.size) return -1
                    val count = minOf(length, body.size - position)
                    body.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
            }
            return ReleaseStream(200, declaredLength, source)
        }
    }
}
