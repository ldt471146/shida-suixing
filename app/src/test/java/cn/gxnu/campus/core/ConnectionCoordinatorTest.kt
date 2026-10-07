package cn.gxnu.campus.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {
    private val campus = NetworkSnapshot("wifi-1", "GXNU-YC", true)
    private val credentials = Credentials("sample-student", "sample-password")

    // Catches losing the visible checking phase, attempt ID, or timer when the phase changes.
    @Test fun attemptMetadataPersistsFromCheckingThroughTerminal() = runTest {
        val initialProbe = CompletableDeferred<Boolean>()
        val login = CompletableDeferred<Boolean>()
        val finalProbe = CompletableDeferred<Boolean>()
        var probes = 0
        val transport = TestTransport(
            authenticate = { _, _ -> login.await() },
            verify = { if (++probes == 1) initialProbe.await() else finalProbe.await() }
        )
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        val checking = coordinator.state.value
        initialProbe.complete(false)
        runCurrent()
        val authenticating = coordinator.state.value
        login.complete(true)
        runCurrent()
        val verifying = coordinator.state.value
        finalProbe.complete(true)
        runCurrent()
        val online = coordinator.state.value

        assertEquals(ConnectionStatus.CHECKING, checking.status)
        assertTrue(checking.attemptId > 0)
        assertNotNull(checking.connectionStartedAtMillis)
        assertEquals(ConnectionStatus.AUTHENTICATING, authenticating.status)
        assertEquals(ConnectionStatus.VERIFYING, verifying.status)
        assertEquals(ConnectionStatus.ONLINE, online.status)
        for (phase in listOf(authenticating, verifying, online)) {
            assertEquals(checking.attemptId, phase.attemptId)
            assertEquals(checking.connectionStartedAtMillis, phase.connectionStartedAtMillis)
        }
        assertNull(online.failure)
        coordinator.connect()
        runCurrent()
        assertTrue(coordinator.state.value.attemptId > online.attemptId)
    }

    // Catches cancelling or duplicating a login solely because Android changed VALIDATED/CAPTIVE_PORTAL.
    @Test fun capabilityEventsKeepInFlightAuthentication() = runTest {
        val login = CompletableDeferred<Boolean>()
        var probes = 0
        val transport = TestTransport(authenticate = { _, _ -> login.await() }, verify = { ++probes > 1 })
        val coordinator = ConnectionCoordinator(this, transport)
        val captive = campus.copy(isValidated = false, isCaptivePortal = true)
        coordinator.update(captive, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        val first = coordinator.state.value
        coordinator.update(captive.copy(isValidated = true, isCaptivePortal = false), credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        val afterCapabilities = coordinator.state.value
        login.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.AUTHENTICATING, afterCapabilities.status)
        assertEquals(first.attemptId, afterCapabilities.attemptId)
        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
    }

    // Catches an account lockout loop when a captive network repeatedly changes its capabilities.
    @Test fun accountFailureDoesNotRetryOnCapabilityFlaps() = runTest {
        val transport = TestTransport(authenticate = { _, _ -> false })
        val coordinator = ConnectionCoordinator(this, transport)
        val captive = campus.copy(isValidated = false, isCaptivePortal = true)
        coordinator.update(captive, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        repeat(4) { index ->
            coordinator.update(captive.copy(isValidated = index % 2 == 0, isCaptivePortal = index % 2 != 0),
                credentials, Provider.MOBILE, autoConnect = true)
            runCurrent()
        }

        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student"), transport.events)
    }

    // Catches treating the first capability observation as a validated session being lost.
    @Test fun unknownValidationChangeDoesNotRestartOnlineConnection() = runTest {
        val transport = TestTransport(verify = { true })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        val online = coordinator.state.value
        coordinator.update(campus.copy(isValidated = false, isCaptivePortal = false), credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(online.attemptId, coordinator.state.value.attemptId)
        assertEquals(listOf("verify:wifi-1"), transport.events)
    }

    // Catches ignoring expiration of a previously validated session after capability-only updates.
    @Test fun validationLossAfterOnlineRechecksAndAuthenticatesTheSameWifiOnce() = runTest {
        var probes = 0
        val transport = TestTransport(verify = { ++probes != 2 })
        val coordinator = ConnectionCoordinator(this, transport)
        val validated = campus.copy(isValidated = true, isCaptivePortal = false)
        coordinator.update(validated, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        val original = coordinator.state.value
        val expired = validated.copy(isValidated = false, isCaptivePortal = true)
        coordinator.update(expired, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        repeat(3) { coordinator.update(expired.copy(), credentials, Provider.MOBILE, autoConnect = true); runCurrent() }

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertTrue(coordinator.state.value.attemptId > original.attemptId)
        assertEquals(listOf("verify:wifi-1", "verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
    }

    // Catches hiding user cancellation or allowing a later automatic event to restart it.
    @Test fun userCancellationPersistsAcrossEquivalentAutomaticEvents() = runTest {
        val transport = TestTransport(authenticate = { _, _ -> awaitCancellation() })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        val started = coordinator.state.value
        coordinator.cancel(userInitiated = true)
        coordinator.update(campus.copy(), credentials.copy(), Provider.MOBILE, autoConnect = true)
        runCurrent()

        assertEquals(ConnectionStatus.CANCELLED, coordinator.state.value.status)
        assertEquals(started.attemptId, coordinator.state.value.attemptId)
        assertEquals(started.connectionStartedAtMillis, coordinator.state.value.connectionStartedAtMillis)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student"), transport.events)
    }

    // Catches classifying accepted authentication with a stalled final probe as an account rejection.
    @Test fun finalVerificationTimeoutHasItsOwnFailureCategory() = runTest {
        var probes = 0
        val transport = TestTransport(verify = { if (++probes == 1) false else awaitCancellation() })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()

        assertEquals(ConnectionStatus.UNREACHABLE, coordinator.state.value.status)
        assertEquals(PortalFailure.VERIFICATION, coordinator.state.value.failure)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
    }

    // Catches a transport-classified initial timeout prematurely ending the school authentication flow.
    @Test fun initialProbeTransportTimeoutContinuesAuthentication() = runTest {
        var probes = 0
        val transport = TestTransport(verify = {
            if (++probes == 1) throw PortalException(PortalFailure.TIMEOUT, "外网检查连接超时。") else true
        })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
    }

    // Catches losing a safe transport diagnostic or labelling an accepted login as an account failure.
    @Test fun finalProbeErrorPreservesSafeMessageAndVerificationCategory() = runTest {
        var probes = 0
        val safeMessage = "校园 Wi-Fi 外网安全连接失败，请检查系统时间。"
        val transport = TestTransport(verify = {
            if (++probes == 1) false else throw PortalException(PortalFailure.UNREACHABLE, safeMessage)
        })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.UNREACHABLE, coordinator.state.value.status)
        assertEquals(PortalFailure.VERIFICATION, coordinator.state.value.failure)
        assertEquals(safeMessage, coordinator.state.value.message)
    }

    // Catches publishing arbitrary exception text from outside the trusted PortalException boundary.
    @Test fun unexpectedTransportFailureNeverEchoesRawExceptionText() = runTest {
        val fictionalSecret = "fictional-sensitive-password"
        val transport = TestTransport(authenticate = { _, _ -> throw IllegalStateException(fictionalSecret) })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.UNREACHABLE, coordinator.state.value.status)
        assertEquals(PortalFailure.UNREACHABLE, coordinator.state.value.failure)
        assertFalse(coordinator.state.value.message.contains(fictionalSecret))
    }

    @Test fun validationLossWithAutoConnectDisabledWaitsForAnExplicitConnect() = runTest {
        val transport = TestTransport(verify = { true })
        val coordinator = ConnectionCoordinator(this, transport)
        val validated = campus.copy(isValidated = true, isCaptivePortal = false)
        coordinator.update(validated, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        coordinator.update(validated.copy(isValidated = false, isCaptivePortal = true), credentials, Provider.MOBILE)
        runCurrent()

        assertEquals(ConnectionStatus.READY, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1"), transport.events)
    }

    // 一个**读得出名字**、又不是校园网的网络必须被挡住 —— 这时候名字就是证据。
    // 反过来，读不出名字的网络不再一律当成外网（见下一条）：Android 没给定位权限时会把
    // 任何 Wi-Fi 的名字都抹成占位符，学校换 SSID 也一样，把这些人挡在外面就是这次报的故障。
    @Test fun aNamedWifiThatIsNotTheCampusOneNeverStartsCampusTransport() = runTest {
        val transport = TestTransport()
        val coordinator = ConnectionCoordinator(this, transport)
        listOf("Home-WiFi", "Guest", "GXNU-YC-5G", "gxnu-yc").forEachIndexed { index, ssid ->
            coordinator.update(NetworkSnapshot("other-$index", ssid, true), credentials, Provider.CAMPUS, autoConnect = true)
            coordinator.connect()
            runCurrent()
            assertEquals("「$ssid」不是校园网", ConnectionStatus.OUTSIDE_CAMPUS, coordinator.state.value.status)
            assertTrue(transport.events.isEmpty())
        }
    }

    /**
     * 用户报的故障：手机明明连在校园网上，应用却说「请切换校园 Wi-Fi」。名字读不出来的 Wi-Fi
     * 不能再被当成外网 —— 名字和校园网对不上只是「不知道」，不是「不是」。
     */
    @Test fun aWifiWhoseNameCannotBeReadIsOfferedForAuthentication() = runTest {
        val transport = TestTransport()
        val coordinator = ConnectionCoordinator(this, transport)
        listOf("", "未识别 Wi-Fi").forEachIndexed { index, ssid ->
            coordinator.update(NetworkSnapshot("unnamed-$index", ssid, true), credentials, Provider.CAMPUS)
            runCurrent()
            assertEquals("「$ssid」应被视为可认证，而不是外网", ConnectionStatus.READY, coordinator.state.value.status)

            coordinator.connect()
            runCurrent()
            assertEquals("认证必须真的发出去", true, transport.events.isNotEmpty())
            transport.events.clear()
        }
    }

    // Catches a branch that submits campus credentials on cellular, other Wi-Fi, or without permission.
    @Test fun missingInputsAndOtherNetworksBlockAuthentication() = runTest {
        val transport = TestTransport()
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(null, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        assertEquals(ConnectionStatus.NO_WIFI, coordinator.state.value.status)

        coordinator.update(NetworkSnapshot("mobile", "", false), credentials, Provider.MOBILE, autoConnect = true)
        assertEquals(ConnectionStatus.NO_WIFI, coordinator.state.value.status)
        coordinator.update(NetworkSnapshot("other", "Guest", true), credentials, Provider.MOBILE, autoConnect = true)
        assertEquals(ConnectionStatus.OUTSIDE_CAMPUS, coordinator.state.value.status)
        coordinator.update(campus, credentials, Provider.MOBILE, permissionGranted = false, autoConnect = true)
        assertEquals(ConnectionStatus.NEED_PERMISSION, coordinator.state.value.status)
        coordinator.update(campus, null, Provider.MOBILE, autoConnect = true)
        assertEquals(ConnectionStatus.NEED_ACCOUNT, coordinator.state.value.status)
        coordinator.update(campus, credentials, null, autoConnect = true)
        assertEquals(ConnectionStatus.NEED_PROVIDER, coordinator.state.value.status)
        runCurrent()
        assertTrue(transport.events.isEmpty())
    }

    // Catches unconditionally submitting a password while the chosen Wi-Fi is already online.
    @Test fun alreadyOnlineWifiDoesNotSubmitCredentials() = runTest {
        val transport = TestTransport(verify = { true })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1"), transport.events)
    }

    // Catches skipping verification after a protocol success or probing another network.
    @Test fun successfulAuthenticationVerifiesTheSameWifiBeforeOnline() = runTest {
        var checks = 0
        val transport = TestTransport(verify = { ++checks > 1 })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
        assertEquals(listOf(Provider.MOBILE), transport.authenticatedProviders)
    }

    // Catches treating a rejected login as online or probing after account rejection.
    @Test fun rejectedCredentialsStopBeforePostAuthenticationProbe() = runTest {
        val transport = TestTransport(authenticate = { _, _ -> false })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student"), transport.events)
    }

    // Catches equating Portal acceptance with internet access.
    @Test fun acceptedLoginWithNoExternalInternetStaysUnreachable() = runTest {
        val transport = TestTransport()
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.UNREACHABLE, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
    }

    // Catches clearing the in-flight guard on an equivalent network callback.
    @Test fun clicksAndRepeatedNetworkEventsShareOneAuthentication() = runTest {
        val gate = CompletableDeferred<Boolean>()
        var checks = 0
        val transport = TestTransport(authenticate = { _, _ -> gate.await() }, verify = { ++checks > 1 })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        coordinator.connect()
        runCurrent()
        assertEquals(ConnectionStatus.AUTHENTICATING, coordinator.state.value.status)
        coordinator.update(campus.copy(), credentials.copy(), Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student"), transport.events)

        gate.complete(true)
        runCurrent()
        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(1, transport.authenticatedCredentials.size)
    }

    // Catches automatic retry loops caused by state propagation after a failed login.
    @Test fun automaticFailureDoesNotRetryUntilAnExplicitConnect() = runTest {
        val transport = TestTransport(authenticate = { _, _ -> false })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)

        repeat(3) {
            coordinator.update(campus.copy(), credentials.copy(), Provider.MOBILE, autoConnect = true)
            runCurrent()
        }
        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        assertEquals(1, transport.authenticatedCredentials.size)
        coordinator.connect()
        runCurrent()
        assertEquals(2, transport.authenticatedCredentials.size)
    }

    // Catches publishing a late login on a Wi-Fi connection the user has left.
    @Test fun leavingCampusWifiInvalidatesEvenNonCooperativeAuthentication() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val transport = TestTransport(authenticate = { _, _ -> withContext(NonCancellable) { gate.await() } })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        coordinator.update(NetworkSnapshot("guest", "Guest", true), credentials, Provider.MOBILE, autoConnect = true)
        assertEquals(ConnectionStatus.OUTSIDE_CAMPUS, coordinator.state.value.status)
        gate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.OUTSIDE_CAMPUS, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student"), transport.events)
    }

    // Catches publishing online from the initial probe after network identification permission is revoked.
    @Test fun revokedPermissionInvalidatesAnInFlightInternetProbe() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val transport = TestTransport(verify = { withContext(NonCancellable) { gate.await() } })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        val checking = coordinator.state.value.status
        coordinator.update(campus, credentials, Provider.MOBILE, permissionGranted = false)
        gate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.CHECKING, checking)
        assertEquals(ConnectionStatus.NEED_PERMISSION, coordinator.state.value.status)
        assertTrue(transport.authenticatedCredentials.isEmpty())
    }

    // Catches publishing online from post-authentication verification for outdated credentials.
    @Test fun editingAccountDuringFinalVerificationInvalidatesItsOnlineResult() = runTest {
        val gate = CompletableDeferred<Boolean>()
        var probes = 0
        val transport = TestTransport(verify = {
            if (++probes == 1) false else withContext(NonCancellable) { gate.await() }
        })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        assertEquals(ConnectionStatus.VERIFYING, coordinator.state.value.status)
        coordinator.update(campus, Credentials("another-sample", "another-password"), Provider.MOBILE)
        gate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.READY, coordinator.state.value.status)
        assertEquals(1, transport.authenticatedCredentials.size)
    }

    // Catches using SSID alone to identify a connection rather than the bound Network ID.
    @Test fun newCampusNetworkStartsItsOwnAttemptAndIgnoresTheOldResult() = runTest {
        val oldGate = CompletableDeferred<Boolean>()
        val transport = TestTransport(authenticate = { network, _ ->
            if (network.id == "wifi-1") withContext(NonCancellable) { oldGate.await() } else false
        })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        coordinator.update(campus.copy(id = "wifi-2"), credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        oldGate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-2", "auth:wifi-2:sample-student"), transport.events)
    }

    // Catches omitting password changes from the configuration version guard.
    @Test fun editingPasswordInvalidatesTheOldResultAndUsesNewCredentials() = runTest {
        val oldGate = CompletableDeferred<Boolean>()
        val changed = credentials.copy(password = "new-sample-password")
        val transport = TestTransport(authenticate = { _, account ->
            if (account == credentials) withContext(NonCancellable) { oldGate.await() } else false
        })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        coordinator.update(campus, changed, Provider.MOBILE)
        assertEquals(ConnectionStatus.READY, coordinator.state.value.status)
        coordinator.connect()
        runCurrent()
        oldGate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        assertEquals(listOf(credentials, changed), transport.authenticatedCredentials)
        assertEquals(2, transport.events.count { it.startsWith("verify:") })
    }

    // Catches retaining a deleted account in an active or subsequent automatic attempt.
    @Test fun deletingAccountCancelsAuthenticationAndBlocksFurtherSubmissions() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val transport = TestTransport(authenticate = { _, _ -> withContext(NonCancellable) { gate.await() } })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        coordinator.update(campus, null, Provider.MOBILE, autoConnect = true)
        gate.complete(true)
        runCurrent()
        coordinator.update(campus, null, Provider.MOBILE, autoConnect = true)
        coordinator.connect()
        runCurrent()

        assertEquals(ConnectionStatus.NEED_ACCOUNT, coordinator.state.value.status)
        assertEquals(1, transport.authenticatedCredentials.size)
        assertEquals(1, transport.events.count { it.startsWith("verify:") })
    }

    // Catches completing an automatic task after its user-visible switch is disabled.
    @Test fun disablingAutoConnectInvalidatesItsInFlightResult() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val transport = TestTransport(authenticate = { _, _ -> withContext(NonCancellable) { gate.await() } })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = false)
        assertEquals(ConnectionStatus.READY, coordinator.state.value.status)
        gate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.READY, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student"), transport.events)
    }

    // Catches restart-on-event after explicit cancellation.
    @Test fun cancellationStaysCancelledWhenAnEquivalentAutoEventArrives() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val transport = TestTransport(authenticate = { _, _ -> withContext(NonCancellable) { gate.await() } })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        coordinator.cancel()
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        gate.complete(true)
        runCurrent()

        assertEquals(ConnectionStatus.READY, coordinator.state.value.status)
        assertEquals(1, transport.authenticatedCredentials.size)
        assertEquals(1, transport.events.count { it.startsWith("verify:") })
    }

    // Catches waiting indefinitely or automatically retrying a timed-out submission.
    @Test fun hangingAuthenticationHasAFiniteDeadlineAndNoAutomaticLoop() = runTest {
        val transport = TestTransport(authenticate = { _, _ -> awaitCancellation() })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        assertEquals(ConnectionStatus.AUTHENTICATING, coordinator.state.value.status)
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(ConnectionStatus.UNREACHABLE, coordinator.state.value.status)
        assertEquals(PortalFailure.TIMEOUT, coordinator.state.value.failure)
        coordinator.update(campus, credentials, Provider.MOBILE, autoConnect = true)
        runCurrent()
        assertEquals(1, transport.authenticatedCredentials.size)
    }

    // Catches spending the whole connection deadline before the four school requests can complete.
    @Test fun slowSchoolDiscoveryCanFinishWithinItsAuthenticationBudget() = runTest {
        var probes = 0
        val transport = TestTransport(
            authenticate = { _, _ -> delay(25_000); true },
            verify = { ++probes > 1 }
        )
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        advanceTimeBy(25_001)
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(1, transport.authenticatedCredentials.size)
        assertEquals(2, probes)
    }

    // Catches letting an unresponsive internet endpoint consume the much longer school budget.
    @Test fun initialInternetProbeTimeoutContinuesSchoolAuthentication() = runTest {
        var probes = 0
        val transport = TestTransport(verify = { if (++probes == 1) awaitCancellation() else true })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        advanceTimeBy(2_501)
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, coordinator.state.value.status)
        assertEquals(listOf("verify:wifi-1", "auth:wifi-1:sample-student", "verify:wifi-1"), transport.events)
    }

    // Catches replacing a classified, already sanitized school response with a generic account failure.
    @Test fun failuresPreserveTheSafePortalMessage() = runTest {
        val safeMessage = "账号已停用，请联系学校网络中心。"
        val transport = TestTransport(authenticate = { _, _ -> throw PortalException(PortalFailure.ACCOUNT, safeMessage) })
        val coordinator = ConnectionCoordinator(this, transport)
        coordinator.update(campus, credentials, Provider.MOBILE)
        coordinator.connect()
        runCurrent()
        assertEquals(ConnectionStatus.AUTH_ERROR, coordinator.state.value.status)
        assertEquals(safeMessage, coordinator.state.value.message)
    }

    private class TestTransport(
        private val authenticate: suspend (NetworkSnapshot, Credentials) -> Boolean = { _, _ -> true },
        private val verify: suspend (NetworkSnapshot) -> Boolean = { false }
    ) : ConnectionTransport {
        val events = mutableListOf<String>()
        val authenticatedCredentials = mutableListOf<Credentials>()
        val authenticatedProviders = mutableListOf<Provider>()

        override suspend fun authenticate(network: NetworkSnapshot, credentials: Credentials, provider: Provider): Boolean {
            events += "auth:${network.id}:${credentials.account}"
            authenticatedCredentials += credentials
            authenticatedProviders += provider
            return authenticate(network, credentials)
        }

        override suspend fun verifyInternet(network: NetworkSnapshot): Boolean {
            events += "verify:${network.id}"
            return verify(network)
        }
    }
}
