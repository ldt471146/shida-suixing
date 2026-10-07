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

/**
 * 校园网的 Wi-Fi 名。它**只是**一个「先看这个」的提示，不再是准入条件。
 *
 * 学校可能换 SSID、同一栋楼可能有两张校园网、Android 在没拿到定位权限时还会把名字
 * 抹成 `未识别 Wi-Fi`。这些情况下名字都对不上，而手机其实就好好连在校园网上 —— 所以
 * 真正决定「这是不是校园网」的是[校园认证入口本身](CampusPortal)，不是这个字符串。
 */
const val CAMPUS_SSID_HINT = "GXNU-YC"

/** 读不到名字时 [NetworkSnapshot.ssid] 用的占位符；它和「确实是别的 Wi-Fi」是两回事。 */
const val UNKNOWN_SSID = "未识别 Wi-Fi"

data class NetworkSnapshot(
    val id: String, val ssid: String, val isWifi: Boolean,
    val isValidated: Boolean? = null, val isCaptivePortal: Boolean? = null
) {
    /** 名字读出来了，而且是校园网那一张 —— 可以直接放行，不必先去问认证页。 */
    val isNamedCampus: Boolean get() = isWifi && ssid == CAMPUS_SSID_HINT

    /** 这张 Wi-Fi 的名字根本没读出来，所以「它是不是校园网」无从判断。 */
    val hasUnknownName: Boolean get() = ssid.isBlank() || ssid == UNKNOWN_SSID
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
