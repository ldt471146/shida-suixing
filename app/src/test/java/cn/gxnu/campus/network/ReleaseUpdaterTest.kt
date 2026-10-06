package cn.gxnu.campus.network

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check half of the updater is pure: the release payload, the published version metadata and
 * the running build number go in, a decision comes out. Every branch is pinned here so the network
 * client itself only has to move bytes.
 */
class ReleaseUpdaterTest {

    private val releaseApi = "https://api.github.com/repos/owner/repo/releases/latest"
    private val versionUrl = "https://github.com/owner/repo/releases/download/v0.4.1/version.json"
    private val apkUrl = "https://github.com/owner/repo/releases/download/v0.4.1/shida-suixing-0.4.1.apk"

    private val versionAssetJson =
        """{"name":"version.json","browser_download_url":"$versionUrl","url":"https://api.github.com/assets/1","size":43}"""
    private val apkAssetJson =
        """{"name":"shida-suixing-0.4.1.apk","browser_download_url":"$apkUrl","url":"https://api.github.com/assets/2","size":9142272}"""

    private fun releaseBody(vararg asset: String): String =
        """{"tag_name":"v0.4.1","body":"修复若干问题","assets":[${asset.joinToString(",")}]}"""

    private fun published(vararg asset: String): List<ReleaseAsset> =
        (readReleaseListing(200, releaseBody(*asset)) as ReleaseListing.Published).assets

    private val newerVersion = """{"versionCode":8,"versionName":"0.4.1"}"""

    @Test fun aHigherPublishedVersionCodeIsAnUpdate() {
        assertTrue(isNewerVersion(published = 8, current = 7))
    }

    @Test fun theSameOrAnOlderPublishedVersionCodeIsNotAnUpdate() {
        assertFalse(isNewerVersion(published = 7, current = 7))
        assertFalse(isNewerVersion(published = 6, current = 7))
    }

    @Test fun aReleaseListingIsReadFromItsAssets() {
        val assets = published(versionAssetJson, apkAssetJson)
        assertEquals(listOf("version.json", "shida-suixing-0.4.1.apk"), assets.map { it.name })
        assertEquals(listOf(versionUrl, apkUrl), assets.map { it.downloadUrl })
        assertEquals(listOf(43L, 9_142_272L), assets.map { it.sizeBytes })
    }

    @Test fun anAssetWithoutABrowserDownloadUrlKeepsItsApiUrl() {
        val assets = published("""{"name":"version.json","url":"https://api.github.com/assets/1","size":43}""")
        assertEquals("https://api.github.com/assets/1", assets.single().downloadUrl)
    }

    @Test fun anAssetWithoutAUsableUrlIsDropped() {
        val assets = published("""{"name":"version.json","size":43}""", apkAssetJson)
        assertEquals(listOf("shida-suixing-0.4.1.apk"), assets.map { it.name })
    }

    @Test fun a404MeansTheProjectHasPublishedNothing() {
        assertEquals(ReleaseListing.NoRelease, readReleaseListing(404, """{"message":"Not Found"}"""))
    }

    @Test fun githubRateLimitingIsReportedAsItsOwnOutcome() {
        // The unauthenticated allowance is 60 requests per hour per address; both 403 and 429 mean
        // the same thing to us, and both must stop the client from asking again.
        assertEquals(ReleaseListing.RateLimited, readReleaseListing(403, """{"message":"API rate limit exceeded"}"""))
        assertEquals(ReleaseListing.RateLimited, readReleaseListing(429, """{"message":"Slow down"}"""))
    }

    @Test fun serverErrorsAndUnknownStatusesCannotReachTheProject() {
        assertEquals(ReleaseListing.Unreachable, readReleaseListing(500, ""))
        assertEquals(ReleaseListing.Unreachable, readReleaseListing(0, null))
        assertEquals(ReleaseListing.Unreachable, readReleaseListing(301, ""))
    }

    @Test fun aListingThatIsNotAJsonObjectIsUnreadable() {
        assertEquals(ReleaseListing.Unreadable, readReleaseListing(200, null))
        assertEquals(ReleaseListing.Unreadable, readReleaseListing(200, ""))
        assertEquals(ReleaseListing.Unreadable, readReleaseListing(200, "{ truncated"))
        assertEquals(ReleaseListing.Unreadable, readReleaseListing(200, "[1,2,3]"))
        assertEquals(ReleaseListing.Unreadable, readReleaseListing(200, """{"assets":"none"}"""))
    }

    @Test fun theVersionAssetIsTheOneNamedForIt() {
        assertEquals(versionUrl, versionAsset(published(versionAssetJson, apkAssetJson))?.downloadUrl)
        assertEquals(null, versionAsset(published(apkAssetJson)))
    }

    @Test fun publishedVersionMetadataIsParsedWhenItIsWellFormed() {
        assertEquals(ReleaseVersion(8, "0.4.1"), parseVersionMetadata("""{"versionCode":8,"versionName":"0.4.1"}"""))
    }

    @Test fun malformedVersionMetadataYieldsNothing() {
        assertEquals(null, parseVersionMetadata(null))
        assertEquals(null, parseVersionMetadata(""))
        assertEquals(null, parseVersionMetadata("not json"))
        assertEquals(null, parseVersionMetadata("""{"versionName":"0.4.1"}"""))
        assertEquals(null, parseVersionMetadata("""{"versionCode":"8","versionName":"0.4.1"}"""))
        assertEquals(null, parseVersionMetadata("""{"versionCode":8,"versionName":"   "}"""))
        assertEquals(null, parseVersionMetadata("""{"versionCode":-1,"versionName":"0.4.1"}"""))
    }

    @Test fun aVersionNameCannotEscapeTheDirectoryTheApkIsCachedIn() {
        // The published name becomes part of the cache file name, so a release must not be able to
        // talk the app into writing somewhere else.
        assertEquals(null, parseVersionMetadata("""{"versionCode":8,"versionName":"../../evil"}"""))
        assertEquals(null, parseVersionMetadata("""{"versionCode":8,"versionName":"a/b"}"""))
        assertEquals(null, parseVersionMetadata("""{"versionCode":8,"versionName":"a b"}"""))
        assertEquals(null, parseVersionMetadata("""{"versionCode":8,"versionName":"${"9".repeat(65)}"}"""))
        assertEquals(ReleaseVersion(8, "0.4.1-rc.2+build"), parseVersionMetadata("""{"versionCode":8,"versionName":"0.4.1-rc.2+build"}"""))
    }

    @Test fun theApkAssetIsChosenByName() {
        val assets = published(
            versionAssetJson,
            """{"name":"notes.txt","browser_download_url":"https://example.invalid/notes.txt","size":10}""",
            apkAssetJson
        )
        assertEquals("shida-suixing-0.4.1.apk", apkAsset(assets, "0.4.1")?.name)
        // The release name wins over a differently named apk in the same release.
        assertEquals("shida-suixing-0.4.1.apk", apkAsset(assets + ReleaseAsset("legacy.apk", "u", 1), "0.4.1")?.name)
    }

    @Test fun aLoneApkAssetIsAcceptedWhenTheReleaseWasRenamed() {
        val assets = listOf(ReleaseAsset("app-release-0.5.0.apk", apkUrl, 1))
        assertEquals("app-release-0.5.0.apk", apkAsset(assets, "0.5.0")?.name)
    }

    @Test fun severalUnnamedApkCandidatesAreRefusedRatherThanGuessed() {
        val assets = listOf(ReleaseAsset("a.apk", "u1", 1), ReleaseAsset("b.apk", "u2", 1))
        assertEquals(null, apkAsset(assets, "0.5.0"))
    }

    @Test fun aPublishedReleaseBecomesAnUpdateOnlyWhenItCarriesAnApk() {
        assertEquals(
            ReleaseCheck.Available(ReleaseVersion(8, "0.4.1"), apkUrl, 9_142_272),
            releaseCheck(published(versionAssetJson, apkAssetJson), newerVersion, currentVersionCode = 7)
        )
        assertEquals(ReleaseCheck.MissingApk, releaseCheck(emptyList(), newerVersion, currentVersionCode = 7))
    }

    @Test fun aReleaseAtOrBelowTheRunningBuildIsUpToDateEvenWithoutAnApk() {
        assertEquals(
            ReleaseCheck.UpToDate,
            releaseCheck(emptyList(), """{"versionCode":7,"versionName":"0.4.0"}""", currentVersionCode = 7)
        )
        assertEquals(
            ReleaseCheck.UpToDate,
            releaseCheck(emptyList(), """{"versionCode":6,"versionName":"0.3.0"}""", currentVersionCode = 7)
        )
    }

    @Test fun aReleaseWithoutReadableVersionMetadataIsUnreadable() {
        assertEquals(ReleaseCheck.Unreadable, releaseCheck(emptyList(), null, currentVersionCode = 7))
        assertEquals(ReleaseCheck.Unreadable, releaseCheck(emptyList(), """{"versionCode":"x"}""", currentVersionCode = 7))
    }

    @Test fun aCheckFetchesTheListingAndThenTheVersionAsset() = runTest {
        val transport = FakeTransport(
            ReleaseResponse(200, releaseBody(versionAssetJson, apkAssetJson)),
            ReleaseResponse(200, newerVersion)
        )
        assertEquals(
            ReleaseCheck.Available(ReleaseVersion(8, "0.4.1"), apkUrl, 9_142_272),
            ReleaseUpdater(transport, releaseApi).check(currentVersionCode = 7)
        )
        assertEquals(listOf(releaseApi, versionUrl), transport.requests)
        assertEquals(listOf("application/vnd.github+json", null), transport.accepts)
    }

    @Test fun aRateLimitedCheckNeverAsksForTheVersionAsset() = runTest {
        val transport = FakeTransport(ReleaseResponse(403, """{"message":"API rate limit exceeded"}"""))
        assertEquals(ReleaseCheck.RateLimited, ReleaseUpdater(transport, releaseApi).check(currentVersionCode = 7))
        assertEquals(listOf(releaseApi), transport.requests)
    }

    @Test fun aReleaseWithoutAVersionAssetIsUnreadable() = runTest {
        val transport = FakeTransport(ReleaseResponse(200, releaseBody(apkAssetJson)))
        assertEquals(ReleaseCheck.Unreadable, ReleaseUpdater(transport, releaseApi).check(currentVersionCode = 7))
        assertEquals(listOf(releaseApi), transport.requests)
    }

    @Test fun aBrokenConnectionIsNotAFailureOfTheProject() = runTest {
        val transport = FakeTransport(failure = IOException("no route to host"))
        assertEquals(ReleaseCheck.Unreachable, ReleaseUpdater(transport, releaseApi).check(currentVersionCode = 7))
    }

    @Test fun aVersionAssetThatDoesNotDownloadIsUnreachable() = runTest {
        val transport = FakeTransport(ReleaseResponse(200, releaseBody(versionAssetJson, apkAssetJson)), ReleaseResponse(502, ""))
        assertEquals(ReleaseCheck.Unreachable, ReleaseUpdater(transport, releaseApi).check(currentVersionCode = 7))
    }

    @Test fun theZipSignatureIsWhatMakesADownloadLookLikeAnApk() {
        assertTrue(looksLikeApk(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(looksLikeApk(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertFalse(looksLikeApk(byteArrayOf(0x3C, 0x21, 0x44, 0x4F)))
        assertFalse(looksLikeApk(byteArrayOf(0x50)))
        assertFalse(looksLikeApk(ByteArray(0)))
    }

    private class FakeTransport(
        vararg canned: ReleaseResponse,
        private val failure: Exception? = null
    ) : ReleaseTransport {
        private val responses = ArrayDeque(canned.toList())
        val requests = mutableListOf<String>()
        val accepts = mutableListOf<String?>()

        override suspend fun get(url: String, accept: String?): ReleaseResponse {
            requests += url
            accepts += accept
            failure?.let { throw it }
            return responses.removeFirst()
        }

        override suspend fun open(url: String): ReleaseStream = throw AssertionError("no download expected")
    }
}
