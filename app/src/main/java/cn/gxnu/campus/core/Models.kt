package cn.gxnu.campus.core

enum class Provider(val title: String, val suffix: String) {
    CAMPUS("校园网", ""), TELECOM("中国电信", "@ctc"),
    UNICOM("中国联通", "@cuc"), MOBILE("中国移动", "@cmc"),
    BROADCAST("广电网络", "@gd")
}

data class Credentials(val account: String, val password: String) {
    override fun toString() = "Credentials(account=<redacted>, password=<redacted>)"
}

enum class ConnectionStatus {
    NO_WIFI, OUTSIDE_CAMPUS, NEED_ACCOUNT, NEED_PROVIDER, NEED_PERMISSION,
    READY, PREPARING, CHECKING, AUTHENTICATING, VERIFYING, ONLINE, AUTH_ERROR, UNREACHABLE, CANCELLED
}

data class NetworkSnapshot(
    val id: String, val ssid: String, val isWifi: Boolean,
    val isValidated: Boolean? = null, val isCaptivePortal: Boolean? = null
) {
    val isCampus: Boolean get() = isWifi && ssid == "GXNU-YC"
}

internal fun NetworkSnapshot.hasSameIdentityAs(other: NetworkSnapshot?): Boolean =
    other != null && id == other.id && ssid == other.ssid && isWifi == other.isWifi

data class PortalContext(
    val ipv4: String, val ipv6: String = "", val mac: String = "",
    val acIp: String = "", val acName: String = "", val jsVersion: String = "4.2.2"
)

interface ConnectionTransport {
    suspend fun authenticate(network: NetworkSnapshot, credentials: Credentials, provider: Provider): Boolean
    suspend fun verifyInternet(network: NetworkSnapshot): Boolean
}

enum class PortalFailure { ACCOUNT, TIMEOUT, UNREACHABLE, UNSUPPORTED, INVALID_RESPONSE, VERIFICATION }
class PortalException(val reason: PortalFailure, message: String) : Exception(message)

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.NO_WIFI,
    val message: String = "先连接校园 Wi-Fi，再来这里认证。",
    val failure: PortalFailure? = null,
    val attemptId: Long = 0L,
    val connectionStartedAtMillis: Long? = null
)
