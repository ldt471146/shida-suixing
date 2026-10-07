package cn.gxnu.campus

import cn.gxnu.campus.network.PortalCompletion
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The embedded school page is a live WebView holding whatever the user has typed into the school's
 * own form. A rotation used to throw it away: the Activity was recreated, the page's teardown wiped
 * WebStorage, the attempt was never settled, and the user had to start the login again.
 *
 * The fix has two halves, and both are pinned here. The host must not be recreated for the changes
 * a user can cause while the page is open, and an attempt must settle exactly once, with the page's
 * own conclusion as evidence rather than as a status. A JVM unit test cannot rotate an Activity, so
 * the first half is asserted against the manifest and the host source, the way the other
 * source-level guards in this module are.
 */
class EmbeddedPortalRotationTest {

    @Test
    fun rotationNeverRestartsTheActivityThatOwnsThePage() {
        // orientation on its own is not enough: since API 13 the framework still restarts an
        // Activity that does not also claim screenSize, and that restart is exactly what used to
        // null the page out from under a half-finished login.
        val claimed = claimedConfigChanges()
        assertTrue("orientation", "orientation" in claimed)
        assertTrue("screenSize", "screenSize" in claimed)
    }

    @Test
    fun theOtherShapeChangesThatWouldDropThePageAreHandledToo() {
        val claimed = claimedConfigChanges()
        // The same event seen from other fields: a fold, a split-screen resize, an input device
        // being attached while the user types, and the app's SYSTEM theme mode following night mode.
        for (value in listOf("smallestScreenSize", "screenLayout", "keyboardHidden", "navigation", "uiMode")) {
            assertTrue(value, value in claimed)
        }
    }

    @Test
    fun theChangesComposeCanOnlyActOnWhenItStartsStillRestartTheHost() {
        // LocalDensity is read while the composition starts and this app lays its screens out from
        // its fontScale, so a text scale the user changed needs a fresh Activity. There is no
        // translated resource set to re-read either, so a locale change must not be frozen in place.
        val claimed = claimedConfigChanges()
        for (value in listOf("density", "fontScale", "locale")) {
            assertFalse(value, value in claimed)
        }
    }

    @Test
    fun thePageIsNeverResurrectedFromASavedStateOrARetainedHandle() {
        val source = mainActivitySource()
        // After process death a handle that came back would point at a WebView that died with the
        // old process, so the page is kept out of both mechanisms and the host settles it instead.
        assertFalse(
            "the page must not be retained across recreation",
            source.contains("onRetainCustomNonConfigurationInstance")
        )
        assertTrue(
            "a host that goes away must still settle an open attempt",
            source.contains("override fun onDestroy()") && source.contains("closeEmbeddedPortal()")
        )
    }

    @Test
    fun oneAttemptSettlesExactlyOnceWithTheEvidenceItGathered() {
        val attempt = attempt()
        attempt.opened(FakePage(PortalCompletion.Online))
        assertEquals(true, attempt.settle())
        assertNull("a settled attempt cannot settle again", attempt.settle())
    }

    @Test
    fun aHostThatGoesAwayAfterThePageClosedSettlesNothingTwice() {
        // The page's own back button and the system back both lead to the close, and the host is
        // destroyed afterwards: only the first one may reach the runtime.
        val attempt = attempt()
        attempt.opened(FakePage(PortalCompletion.Manual("未自动确认联网")))
        assertEquals(false, attempt.settle())
        assertNull(attempt.settle())
    }

    @Test
    fun aHostWithNoOpenPageNeverSettlesAnything() {
        // A rotation with no school page open, or the preview build, must leave the runtime alone.
        assertNull(attempt().settle())
    }

    @Test
    fun eachNewAttemptGetsItsOwnSettlement() {
        val attempt = attempt()
        attempt.opened(FakePage(PortalCompletion.Manual("未自动确认联网")))
        assertEquals(false, attempt.settle())
        attempt.opened(FakePage(PortalCompletion.Online))
        assertEquals(true, attempt.settle())
    }

    @Test
    fun aPageThatNeverProvedOnlineIsNotEvidenceForTheRuntime() {
        // The panel the page renders is not a status: only the same Wi-Fi's own probe means online,
        // and the runtime re-runs that probe before it publishes anything.
        assertTrue(portalEvidence(PortalCompletion.Online))
        assertFalse("a page still checking has proved nothing", portalEvidence(PortalCompletion.Checking))
        assertFalse(
            "a page that gave up on the watch has proved nothing",
            portalEvidence(PortalCompletion.Manual("仍未检测到外网，可在此页面手动登录，完成后返回首页。"))
        )
    }

    private class FakePage(val completion: PortalCompletion)

    private fun attempt() = EmbeddedPortalAttempt<FakePage> { portalEvidence(it.completion) }

    /** The config changes MainActivity declares it handles, as the framework reads them. */
    private fun claimedConfigChanges(): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())
        val activities = document.getElementsByTagName("activity")
        val host = (0 until activities.length)
            .map { activities.item(it) as Element }
            .firstOrNull { it.getAttribute("android:name") == ".MainActivity" }
        assertNotNull("MainActivity is not declared in the manifest", host)
        return host!!.getAttribute("android:configChanges")
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Sources are resolved from the test working directory (`app/` under Gradle) or from the
     * repository root, so the guards work from both entry points.
     */
    private fun sourceFile(relative: String): File {
        val candidates = generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .flatMap { root -> sequenceOf(File(root, relative), File(root, "app/$relative")) }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$relative not found above ${File("").absolutePath}")
    }

    private fun manifestFile(): File = sourceFile("src/main/AndroidManifest.xml")

    private fun mainActivitySource(): String =
        sourceFile("src/main/java/cn/gxnu/campus/MainActivity.kt").readText()
}
