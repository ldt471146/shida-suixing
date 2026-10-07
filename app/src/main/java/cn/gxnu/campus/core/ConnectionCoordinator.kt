package cn.gxnu.campus.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Called from one scope/thread; every transport result belongs to one configuration generation. */
class ConnectionCoordinator(private val scope: CoroutineScope, private val transport: ConnectionTransport) {
    private val mutableState = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private var configuration = Configuration(null, null, null, true)
    private var generation = 0L
    private var attemptSequence = 0L
    private var activeJob: Job? = null
    private var automaticJob = false
    private var lastAutomaticAttempt: TargetKey? = null

    fun update(
        network: NetworkSnapshot?, credentials: Credentials?, provider: Provider?,
        permissionGranted: Boolean = true, autoConnect: Boolean = false
    ) {
        val previous = configuration
        val next = Configuration(network, credentials, provider, permissionGranted)
        val changed = !next.hasSameIdentityAs(previous)
        val lostValidation = !changed && mutableState.value.status == ConnectionStatus.ONLINE &&
            ((previous.network?.isValidated == true && network?.isValidated == false) ||
                (previous.network?.isCaptivePortal == false && network?.isCaptivePortal == true))
        val stopAutomatic = !autoConnect && automaticJob && activeJob?.isActive == true
        configuration = next
        if (changed || stopAutomatic) {
            invalidate()
            if (changed) lastAutomaticAttempt = null
            mutableState.value = readyStateWithAttempt()
        }
        // Only a session that was already online earns another automatic attempt on capability loss.
        if (lostValidation) {
            lastAutomaticAttempt = null
            if (!autoConnect) mutableState.value = readyStateWithAttempt()
        }
        val target = readyTarget()
        if (autoConnect && target != null && target.key != lastAutomaticAttempt && activeJob?.isActive != true) {
            start(target, automatic = true)
        }
    }

    fun connect() {
        val target = readyTarget()
        if (target == null) {
            mutableState.value = readyStateWithAttempt()
            return
        }
        if (activeJob?.isActive == true) return
        start(target, automatic = false)
    }

    fun cancel(userInitiated: Boolean = false) {
        if (userInitiated) readyTarget()?.let { lastAutomaticAttempt = it.key }
        invalidate()
        mutableState.value = if (userInitiated) mutableState.value.copy(
            status = ConnectionStatus.CANCELLED, message = "连接已取消，可以重新尝试。", failure = null
        ) else readyStateWithAttempt()
    }

    private fun invalidate() {
        generation++
        activeJob?.cancel()
        activeJob = null
        automaticJob = false
    }

    private fun start(target: Target, automatic: Boolean) {
        val ticket = ++generation
        val attemptId = ++attemptSequence
        val startedAtMillis = System.nanoTime() / 1_000_000
        automaticJob = automatic
        // A manual attempt also consumes the automatic allowance for this target.
        lastAutomaticAttempt = target.key
        // Assign before starting, including when the caller uses an immediate dispatcher.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var authenticationAccepted = false
            try {
                if (ticket != generation) return@launch
                mutableState.value = ConnectionState(
                    ConnectionStatus.CHECKING, "正在检查校园 Wi-Fi 是否可以上网…",
                    attemptId = attemptId, connectionStartedAtMillis = startedAtMillis
                )
                withTimeout(45_000) {
                    val alreadyOnline = initialInternetCheck(target.network)
                    if (ticket != generation) return@withTimeout
                    if (alreadyOnline) {
                        publish(ticket, ConnectionStatus.ONLINE, "校园 Wi-Fi 已连接，可以上网。")
                        return@withTimeout
                    }

                    publish(ticket, ConnectionStatus.AUTHENTICATING, "正在打开学校登录页并自动登录…")
                    val accepted = withTimeout(30_000) {
                        transport.authenticate(target.network, target.credentials, target.provider)
                    }
                    if (ticket != generation) return@withTimeout
                    if (!accepted) {
                        publish(ticket, ConnectionStatus.AUTH_ERROR, failureMessage(PortalFailure.ACCOUNT), PortalFailure.ACCOUNT)
                        return@withTimeout
                    }
                    authenticationAccepted = true

                    publish(ticket, ConnectionStatus.VERIFYING, "认证已受理，正在检查校园 Wi-Fi 外网…")
                    val online = withTimeoutOrNull(10_000) { transport.verifyInternet(target.network) } == true
                    if (online) publish(ticket, ConnectionStatus.ONLINE, "校园 Wi-Fi 已连接，可以上网。")
                    else publish(ticket, ConnectionStatus.UNREACHABLE, failureMessage(PortalFailure.VERIFICATION), PortalFailure.VERIFICATION)
                }
            } catch (_: TimeoutCancellationException) {
                val failure = if (authenticationAccepted) PortalFailure.VERIFICATION else PortalFailure.TIMEOUT
                publish(ticket, ConnectionStatus.UNREACHABLE, failureMessage(failure), failure)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PortalException) {
                val failure = if (authenticationAccepted) PortalFailure.VERIFICATION else error.reason
                val status = if (failure == PortalFailure.ACCOUNT) ConnectionStatus.AUTH_ERROR else ConnectionStatus.UNREACHABLE
                publish(ticket, status, error.message?.takeIf { it.isNotBlank() } ?: failureMessage(failure), failure)
            } catch (_: Exception) {
                val failure = if (authenticationAccepted) PortalFailure.VERIFICATION else PortalFailure.UNREACHABLE
                val message = if (authenticationAccepted) failureMessage(failure)
                    else "校园 Wi-Fi 连接检查失败，请确认网络后重试。"
                publish(ticket, ConnectionStatus.UNREACHABLE, message, failure)
            } finally {
                if (ticket == generation) {
                    activeJob = null
                    automaticJob = false
                }
            }
        }
        activeJob = job
        job.start()
    }

    private suspend fun initialInternetCheck(network: NetworkSnapshot): Boolean = try {
        // A captive Wi-Fi can black-hole an internet probe before school authentication.
        withTimeoutOrNull(2_500) { transport.verifyInternet(network) } == true
    } catch (error: PortalException) {
        if (error.reason == PortalFailure.TIMEOUT || error.reason == PortalFailure.UNREACHABLE) false else throw error
    }

    private fun publish(ticket: Long, status: ConnectionStatus, message: String, failure: PortalFailure? = null) {
        if (ticket == generation) mutableState.value = mutableState.value.copy(status = status, message = message, failure = failure)
    }

    private fun readyStateWithAttempt(): ConnectionState = readyState().copy(
        attemptId = mutableState.value.attemptId,
        connectionStartedAtMillis = mutableState.value.connectionStartedAtMillis
    )

    private fun readyState(): ConnectionState = when {
        !configuration.permissionGranted -> ConnectionState(ConnectionStatus.NEED_PERMISSION, "需要网络识别权限才能确认校园 Wi-Fi。")
        else -> when (CampusNetworkPolicy.check(configuration.network)) {
            CampusNetworkPolicy.Verdict.NotWifi ->
                ConnectionState(ConnectionStatus.NO_WIFI, CampusNetworkPolicy.refusal(configuration.network)!!)
            CampusNetworkPolicy.Verdict.OtherNetwork ->
                ConnectionState(ConnectionStatus.OUTSIDE_CAMPUS, CampusNetworkPolicy.refusal(configuration.network)!!)
            CampusNetworkPolicy.Verdict.Allowed -> when {
                configuration.credentials == null || configuration.credentials!!.account.isBlank() || configuration.credentials!!.password.isEmpty() ->
                    ConnectionState(ConnectionStatus.NEED_ACCOUNT, "请先设置校园网账号和密码。")
                configuration.provider == null -> ConnectionState(ConnectionStatus.NEED_PROVIDER, "请选择校园网供应商。")
                else -> ConnectionState(ConnectionStatus.READY, "校园 Wi-Fi 已连接，可以开始认证。")
            }
        }
    }

    private fun readyTarget(): Target? {
        if (readyState().status != ConnectionStatus.READY) return null
        return Target(configuration.network!!, configuration.credentials!!, configuration.provider!!)
    }

    private fun failureMessage(failure: PortalFailure): String = when (failure) {
        PortalFailure.ACCOUNT -> "学校未通过认证，请检查账号、密码和供应商。"
        PortalFailure.TIMEOUT -> "学校认证响应超时，请稍后重试。"
        PortalFailure.UNSUPPORTED -> "学校要求其他认证操作，请打开学校认证页面。"
        PortalFailure.INVALID_RESPONSE -> "学校认证响应无法识别，请打开学校认证页面检查。"
        PortalFailure.UNREACHABLE -> "学校认证入口暂时无法访问，请稍后重试。"
        PortalFailure.VERIFICATION -> "认证已受理，校园 Wi-Fi 暂时仍无法访问外网，请稍后重试。"
    }

    private data class Configuration(
        val network: NetworkSnapshot?, val credentials: Credentials?,
        val provider: Provider?, val permissionGranted: Boolean
    ) {
        fun hasSameIdentityAs(other: Configuration): Boolean =
            (network?.hasSameIdentityAs(other.network) ?: (other.network == null)) &&
                credentials == other.credentials && provider == other.provider && permissionGranted == other.permissionGranted
    }

    private data class Target(val network: NetworkSnapshot, val credentials: Credentials, val provider: Provider) {
        val key: TargetKey get() = TargetKey(network.id, network.ssid, credentials, provider)
    }
    private data class TargetKey(val networkId: String, val ssid: String, val credentials: Credentials, val provider: Provider)
}
