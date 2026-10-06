package cn.gxnu.campus.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** One downloadable file attached to a release. */
data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

/** The version metadata published as the release's `version.json` asset. */
data class ReleaseVersion(val versionCode: Int, val versionName: String)

/** What a release listing says, or why it says nothing usable. */
sealed interface ReleaseListing {
    data class Published(val assets: List<ReleaseAsset>) : ReleaseListing
    data object NoRelease : ReleaseListing
    data object RateLimited : ReleaseListing
    data object Unreadable : ReleaseListing
    data object Unreachable : ReleaseListing
}

/** What a completed check means for the build that ran it. */
sealed interface ReleaseCheck {
    data class Available(val version: ReleaseVersion, val apkUrl: String, val apkBytes: Long) : ReleaseCheck
    data object UpToDate : ReleaseCheck
    data object NoRelease : ReleaseCheck
    data object RateLimited : ReleaseCheck
    data object MissingApk : ReleaseCheck
    data object Unreadable : ReleaseCheck
    data object Unreachable : ReleaseCheck
}

/** Why a transfer never produced an installable file. */
enum class DownloadFailure { NETWORK, NOT_AN_APK, TOO_LARGE, STORAGE }

/** The result of fetching an apk. Cancellation is not a value: it propagates as cancellation. */
sealed interface ApkDownload {
    data class Saved(val file: File, val bytes: Long) : ApkDownload
    data class Failed(val reason: DownloadFailure) : ApkDownload
}

data class ReleaseResponse(val status: Int, val body: String)

/** An open response body. The caller closes it; [abort] unblocks a read from another thread. */
class ReleaseStream(
    val status: Int,
    val contentLength: Long,
    val source: InputStream,
    val abort: () -> Unit = {}
) : Closeable {
    override fun close() {
        runCatching { source.close() }
    }
}

/**
 * Everything the updater needs from the network. Tests supply canned bytes through this seam, so
 * no unit test ever opens a socket.
 */
interface ReleaseTransport {
    suspend fun get(url: String, accept: String?): ReleaseResponse

    /** Opens [url] for a streaming download. The caller owns the returned stream. */
    suspend fun open(url: String): ReleaseStream
}

/**
 * The project publishes builds as GitHub releases, and every release carries the same two assets:
 * `version.json` and the signed apk. The same list is used for the listing and for the download,
 * so a check costs exactly one API request and stays well inside GitHub's unauthenticated hourly
 * allowance; the asset bodies come from their plain download urls and are not rate limited.
 */
const val RELEASE_API = "https://api.github.com/repos/ldt471146/shida-suixing/releases/latest"

private const val VERSION_ASSET_NAME = "version.json"
private const val GITHUB_JSON_ACCEPT = "application/vnd.github+json"

/**
 * Reads the HTTP status a GitHub listing answered with. The body is only consulted for a success
 * response: an error payload carries no assets, and the two statuses that matter here - 403 and 429
 * - are the ones GitHub uses for its unauthenticated 60-per-hour allowance.
 */
fun readReleaseListing(status: Int, body: String?): ReleaseListing = when {
    status == 404 -> ReleaseListing.NoRelease
    status == 403 || status == 429 -> ReleaseListing.RateLimited
    status != 200 -> ReleaseListing.Unreachable
    else -> assetsOf(parseObject(body))
}

private fun assetsOf(root: JsonObject?): ReleaseListing {
    val listed = root?.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ReleaseListing.Unreadable
    return ReleaseListing.Published(listed.filter { it.isJsonObject }.mapNotNull { asset(it.asJsonObject) })
}

private fun asset(value: JsonObject): ReleaseAsset? {
    val name = scalar(value, "name")?.takeIf { it.isNotBlank() } ?: return null
    val url = scalar(value, "browser_download_url")?.takeIf { it.isNotBlank() }
        ?: scalar(value, "url")?.takeIf { it.isNotBlank() }
        ?: return null
    return ReleaseAsset(name, url, scalar(value, "size")?.toLongOrNull() ?: -1L)
}

/** The `version.json` attachment of a listing, which is what carries the published build number. */
fun versionAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
    assets.firstOrNull { it.name == VERSION_ASSET_NAME }

/**
 * Reads the published version. Anything that is not a non-negative integer code plus a non-blank
 * name is refused: a release that cannot state its version must not be offered as an update. The
 * name is also restricted to the characters a version is made of, because it becomes part of the
 * file name the apk is cached under and must not be able to escape that directory.
 */
fun parseVersionMetadata(body: String?): ReleaseVersion? {
    val root = parseObject(body) ?: return null
    val code = root.get("versionCode")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
    val value = runCatching { code.asInt }.getOrNull() ?: return null
    val name = scalar(root, "versionName")?.trim()?.takeIf { VERSION_NAME.matches(it) } ?: return null
    if (value < 0) return null
    return ReleaseVersion(value, name)
}

private val VERSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")

/**
 * The signed apk of a release. The packaging names it for the version it contains; a release whose
 * assets were renamed is still usable, but only when exactly one apk is on offer - two candidates
 * mean the choice cannot be made safely.
 */
fun apkAsset(assets: List<ReleaseAsset>, versionName: String): ReleaseAsset? {
    assets.firstOrNull { it.name == "shida-suixing-$versionName.apk" }?.let { return it }
    return assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull()
}

/** A strictly higher published build number is the only thing that makes an update available. */
fun isNewerVersion(published: Int, current: Int): Boolean = published > current

/**
 * Turns a fetched release into the decision the UI acts on. The apk is only required once the
 * published build is actually newer, so an old incomplete release is never reported as a problem.
 */
fun releaseCheck(assets: List<ReleaseAsset>, versionBody: String?, currentVersionCode: Int): ReleaseCheck {
    val version = parseVersionMetadata(versionBody) ?: return ReleaseCheck.Unreadable
    if (!isNewerVersion(version.versionCode, currentVersionCode)) return ReleaseCheck.UpToDate
    val apk = apkAsset(assets, version.versionName) ?: return ReleaseCheck.MissingApk
    return ReleaseCheck.Available(version, apk.downloadUrl, apk.sizeBytes)
}

/**
 * Every apk is a zip, and a zip starts with the two bytes `PK`. This is a cheap sanity check on a
 * download that answered 200, not a signature check: it exists so a captive portal login page or an
 * error document is never handed to the installer as an update.
 */
fun looksLikeApk(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

private fun parseObject(body: String?): JsonObject? {
    if (body.isNullOrBlank()) return null
    return try {
        JsonParser.parseReader(StringReader(body)).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }
}

private fun scalar(value: JsonObject, key: String): String? =
    value.get(key)?.takeIf { it.isJsonPrimitive }?.asString

/**
 * Fetches and evaluates the latest release. Transport failures are values, not exceptions, so the
 * caller can always render something; only cancellation propagates.
 */
class ReleaseUpdater(
    private val transport: ReleaseTransport,
    private val releaseApi: String = RELEASE_API,
    private val maxDownloadBytes: Long = MAX_APK_BYTES
) {
    private val activeStream = AtomicReference<ReleaseStream?>(null)

    suspend fun check(currentVersionCode: Int): ReleaseCheck {
        val listing = request(releaseApi, GITHUB_JSON_ACCEPT) ?: return ReleaseCheck.Unreachable
        val published = when (val read = readReleaseListing(listing.status, listing.body)) {
            ReleaseListing.NoRelease -> return ReleaseCheck.NoRelease
            ReleaseListing.RateLimited -> return ReleaseCheck.RateLimited
            ReleaseListing.Unreadable -> return ReleaseCheck.Unreadable
            ReleaseListing.Unreachable -> return ReleaseCheck.Unreachable
            is ReleaseListing.Published -> read
        }
        val metadata = versionAsset(published.assets) ?: return ReleaseCheck.Unreadable
        val body = request(metadata.downloadUrl, null) ?: return ReleaseCheck.Unreachable
        if (body.status != 200) return ReleaseCheck.Unreachable
        return releaseCheck(published.assets, body.body, currentVersionCode)
    }

    /**
     * Streams the apk into `target.part` and renames it only after the whole body was written and
     * looked like an apk, so a half-written file can never be offered to the installer. Cancelling
     * the caller removes the partial file and rethrows.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): ApkDownload = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        var renamed = false
        try {
            when (val fetched = fetch(url, partial, onProgress)) {
                is Fetch.Rejected -> return@withContext ApkDownload.Failed(fetched.reason)
                is Fetch.Saved -> {
                    if (!partial.renameTo(target)) return@withContext ApkDownload.Failed(DownloadFailure.STORAGE)
                    renamed = true
                    ApkDownload.Saved(target, fetched.bytes)
                }
            }
        } finally {
            if (!renamed) partial.delete()
        }
    }

    /** Disconnects the transfer behind a running [download]; the download then fails as network. */
    fun abort() {
        activeStream.get()?.abort?.invoke()
    }

    private suspend fun fetch(url: String, partial: File, onProgress: (Long, Long) -> Unit): Fetch {
        val stream = try {
            transport.open(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Fetch.Rejected(DownloadFailure.NETWORK)
        }
        activeStream.set(stream)
        return try {
            Fetch.Saved(stream.use { copyTo(it, partial, onProgress) })
        } catch (rejected: DownloadRejected) {
            Fetch.Rejected(rejected.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Fetch.Rejected(DownloadFailure.NETWORK)
        } finally {
            activeStream.compareAndSet(stream, null)
        }
    }

    private suspend fun copyTo(stream: ReleaseStream, partial: File, onProgress: (Long, Long) -> Unit): Long {
        if (stream.status !in 200..299) throw DownloadRejected(DownloadFailure.NETWORK)
        if (stream.contentLength > maxDownloadBytes) throw DownloadRejected(DownloadFailure.TOO_LARGE)
        partial.parentFile?.mkdirs()
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        var headerChecked = false
        partial.outputStream().buffered().use { sink ->
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!headerChecked) {
                    headerChecked = true
                    val header = if (count > APK_MAGIC_BYTES) buffer.copyOf(APK_MAGIC_BYTES) else buffer.copyOf(count)
                    if (!looksLikeApk(header)) throw DownloadRejected(DownloadFailure.NOT_AN_APK)
                }
                if (written + count > maxDownloadBytes) throw DownloadRejected(DownloadFailure.TOO_LARGE)
                sink.write(buffer, 0, count)
                written += count
                onProgress(written, stream.contentLength)
            }
        }
        if (written == 0L) throw DownloadRejected(DownloadFailure.NOT_AN_APK)
        return written
    }

    /** Null means the request never produced a response; [ReleaseCheck.Unreachable] is the value. */
    private suspend fun request(url: String, accept: String?): ReleaseResponse? = try {
        transport.get(url, accept)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private class DownloadRejected(val reason: DownloadFailure) : Exception()

    private sealed interface Fetch {
        data class Saved(val bytes: Long) : Fetch
        data class Rejected(val reason: DownloadFailure) : Fetch
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val APK_MAGIC_BYTES = 2
        const val MAX_APK_BYTES = 128L * 1024 * 1024
    }
}

/**
 * Blocking HTTPS off the main thread, mirroring the transport the recognition client uses: the
 * platform TLS stack, and cancellation disconnects the request so a waiting read does not hang.
 */
internal class HttpsReleaseTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000
) : ReleaseTransport {

    override suspend fun get(url: String, accept: String?): ReleaseResponse =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpsURLConnection?>(null)
            fun disconnectAsync() {
                val connection = active.getAndSet(null) ?: return
                Dispatchers.IO.dispatch(continuation.context, Runnable { disconnectSafely(connection) })
            }
            continuation.invokeOnCancellation { disconnectAsync() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                var connection: HttpsURLConnection? = null
                try {
                    if (!continuation.isActive) return@Runnable
                    connection = open(url, accept)
                    val activeConnection = connection
                    active.set(activeConnection)
                    val status = activeConnection.responseCode
                    val body = if (status in 200..299) {
                        activeConnection.inputStream?.use { it.readBounded(MAX_TEXT_BYTES) } ?: ByteArray(0)
                    } else {
                        ByteArray(0)
                    }
                    if (continuation.isActive) continuation.resume(ReleaseResponse(status, String(body, Charsets.UTF_8)))
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally {
                    connection?.let { if (active.compareAndSet(it, null)) disconnectSafely(it) }
                }
            })
        }

    override suspend fun open(url: String): ReleaseStream = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpsURLConnection?>(null)
        continuation.invokeOnCancellation { active.getAndSet(null)?.let { disconnectSafely(it) } }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            var connection: HttpsURLConnection? = null
            try {
                if (!continuation.isActive) return@Runnable
                // An apk served from the api asset endpoint is only returned as bytes when this
                // media type is asked for; the plain download url ignores it.
                val opened = open(url, "application/octet-stream")
                connection = opened
                active.set(opened)
                val status = opened.responseCode
                if (!continuation.isActive) {
                    disconnectSafely(opened)
                    return@Runnable
                }
                val source = if (status in 200..299) opened.inputStream else ByteArrayInputStream(ByteArray(0))
                continuation.resume(ReleaseStream(status, opened.contentLengthLong, source) { disconnectSafely(opened) })
            } catch (failure: Exception) {
                connection?.let { disconnectSafely(it) }
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        })
    }

    private fun open(url: String, accept: String?): HttpsURLConnection {
        val parsed = URL(url)
        // The published assets are only ever fetched over https; downgrading is not an option.
        if (parsed.protocol != "https") throw IOException("release traffic must use https")
        return (parsed.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            // Asset downloads redirect from the project page to the release CDN.
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            useCaches = false
            doInput = true
            // GitHub answers 403 to a request without a User-Agent.
            setRequestProperty("User-Agent", USER_AGENT)
            accept?.let { setRequestProperty("Accept", it) }
        }
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("release response exceeded $limit bytes")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun disconnectSafely(connection: HttpsURLConnection) {
        try {
            connection.disconnect()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        const val USER_AGENT = "shida-suixing-updater"
    }
}
