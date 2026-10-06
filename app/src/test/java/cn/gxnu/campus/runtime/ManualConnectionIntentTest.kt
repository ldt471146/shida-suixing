package cn.gxnu.campus.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManualConnectionIntentTest {
    // Reading cached permission=false before refresh finishes loses the only click.
    @Test fun oneClickWaitsForWifiIdentificationAndContinuesExactlyOnce() = runTest {
        val networkRead = CompletableDeferred<ManualReadiness>()
        var preparing = false
        var dialogs = 0
        var submissions = 0
        val intent = ManualConnectionIntent(backgroundScope, { networkRead.await() },
            { preparing = true }, { if (it) dialogs++ }, { submissions++ })

        intent.request()

        assertTrue("the click must show progress before the network read", preparing)
        runCurrent()
        assertEquals(0, submissions)
        assertEquals("an unfinished refresh is not a denied permission", 0, dialogs)
        networkRead.complete(ManualReadiness.READY)
        runCurrent()

        assertEquals(1, submissions)
        assertFalse(intent.pending)
        intent.resumeAfterPermissionChange()
        runCurrent()
        assertEquals("a later resume cannot submit the consumed click again", 1, submissions)
    }

    @Test fun permissionDenialKeepsTheClickForALaterGrantWithoutRepeatingTheDialog() = runTest {
        var readiness = ManualReadiness.NEED_PERMISSION
        var dialogs = 0
        var submissions = 0
        val intent = ManualConnectionIntent(backgroundScope, { readiness }, {},
            { if (it) dialogs++ }, { submissions++ })

        intent.request()
        runCurrent()
        assertEquals(1, dialogs)
        assertTrue(intent.pending)
        intent.resumeAfterPermissionChange()
        runCurrent()
        assertEquals("a denied result must not reopen the system dialog", 1, dialogs)
        assertEquals(0, submissions)

        readiness = ManualReadiness.READY
        intent.resumeAfterPermissionChange()
        runCurrent()

        assertEquals("granting permission must continue the original manual request", 1, submissions)
        assertFalse(intent.pending)
    }

    @Test fun cancellationDuringIdentificationPreventsALateSubmission() = runTest {
        val networkRead = CompletableDeferred<ManualReadiness>()
        var submissions = 0
        val intent = ManualConnectionIntent(backgroundScope, { networkRead.await() }, {}, {}, { submissions++ })
        intent.request()
        runCurrent()

        intent.cancel()
        networkRead.complete(ManualReadiness.READY)
        runCurrent()
        intent.resumeAfterPermissionChange()
        runCurrent()

        assertFalse(intent.pending)
        assertEquals(0, submissions)
    }

    @Test fun repeatClickDuringIdentificationDoesNotStartASecondSubmission() = runTest {
        val networkRead = CompletableDeferred<ManualReadiness>()
        var submissions = 0
        val intent = ManualConnectionIntent(backgroundScope, { networkRead.await() }, {}, {}, { submissions++ })
        intent.request()
        intent.request()
        runCurrent()
        networkRead.complete(ManualReadiness.READY)
        runCurrent()

        assertEquals(1, submissions)
    }

    @Test fun anUnavailableNetworkReadEndsPreparationWithoutSubmittingCachedCredentials() = runTest {
        var submissions = 0
        var unavailableNotice = false
        val intent = ManualConnectionIntent(backgroundScope, { ManualReadiness.UNAVAILABLE }, {}, {},
            { submissions++ }, { unavailableNotice = true })

        intent.request()
        runCurrent()

        assertEquals("an unavailable refresh cannot use the previous Wi-Fi snapshot", 0, submissions)
        assertTrue(unavailableNotice)
        assertFalse(intent.pending)
        assertEquals(ManualIntentStage.FAILED, intent.stage)
    }
}
