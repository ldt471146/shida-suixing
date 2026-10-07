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
 * 校园网 SSID 里带的学校记号。名字里出现它，就当作校园网。
 *
 * 这里原来是一句 `ssid == "GXNU-YC"` 的精确比较，0.8.3 修的故障就是它：用户那台 AP 报出来的
 * 名字是 `GXNU.YC`（**点号**，截图里放大到 4 倍能看清它对在基线上，和标题里那个居中的
 * `·` 不是同一个字符）。精确比较判成「别的网络」，于是手机明明连在校园网上、应用却让用户
 * 「请先连接校园 Wi-Fi」—— 用户永远做不到，因为那张网的名字本来就不长这样。
 *
 * 点的、横的、下划线的、带 `-5G` 后缀的、大写小写的都是同一张网，所以这里先归一化再找记号，
 * 让「像不像校园网」不再依赖某一个字符恰好是横线。
 */
private val CAMPUS_SSID_MARKERS = listOf("gxnu", "广西师范大学")

/**
 * 名字里带学校记号吗。
 *
 * 归一化：转小写，并去掉所有非字母数字 —— `GXNU-YC`、`GXNU.YC`、`GXNU_YC`、`gxnu-yc-5g`
 * 全部变成 `gxnuyc…`，于是它们全都命中。它只回答「像不像」，不回答「是不是」：后面那个问题
 * 只有认证入口有权回答，见 [CampusNetworkPolicy]。
 */
fun looksLikeCampusSsid(ssid: String): Boolean {
    val normalized = ssid.lowercase().filter { it.isLetterOrDigit() }
    return normalized.isNotEmpty() && CAMPUS_SSID_MARKERS.any { normalized.contains(it) }
}

/** 读不到名字时 [NetworkSnapshot.ssid] 用的占位符；它和「确实是别的 Wi-Fi」是两回事。 */
const val UNKNOWN_SSID = "未识别 Wi-Fi"

data class NetworkSnapshot(
    val id: String, val ssid: String, val isWifi: Boolean,
    val isValidated: Boolean? = null, val isCaptivePortal: Boolean? = null
) {
    /** 名字读出来了，而且像校园网那一张 —— 可以直接放行，不必先去问认证页。 */
    val isNamedCampus: Boolean get() = isWifi && looksLikeCampusSsid(ssid)

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
