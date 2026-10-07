package cn.gxnu.campus.core

/**
 * 校园网的身份判定，集中在一处。
 *
 * 这条规则原来散在七个文件里（`ConnectionCoordinator`、`WifiEnvironment`、`PortalClient`、
 * `OfficialPortalTransport`、`PinnedPortalResources`、`CampusApplication`、`Models`），每处各写
 * 一遍 `ssid == "GXNU-YC"`。0.8.1 之前它们全都把「名字读不出来」和「这是别的网络」当成同一件
 * 事，于是手机明明连在校园网上、应用却把用户挡在认证之外。修的时候要改六处 —— 那就是耦合的代价。
 *
 * 现在规则只在这里，而且只有三条：
 *
 * 1. **不是 Wi-Fi** → 没有可认证的链路。
 * 2. **名字读得出来、而且不像校园网** → 拒绝。这时候名字是证据。
 * 3. **其余（含名字读不出来）** → 放行去问认证页。
 *
 * 第 2 条的判据是 [looksLikeCampusSsid]（带学校记号，而非等于某一个拼法）。0.8.3 之前它是
 * 精确相等的比较，用户那台 AP 报 `GXNU.YC` 而常量写 `GXNU-YC`，一个字符之差就把用户挡死。
 *
 * 第 3 条是关键，也是反直觉的那条：Android 在缺少定位权限或系统定位开关时会**把任何 Wi-Fi 的
 * 名字抹成占位符**，学校改 SSID 时名字也对不上。这两种情况下「名字不是校园网」只说明我们
 * **不知道**，不说明**不是**。真正有权回答「这是不是校园网」的是校园认证入口自己 —— 它是那台
 * AC/portal，对谁应答谁就是。所以判定在这里放行，由认证结果定胜负。
 */
object CampusNetworkPolicy {

    /** 在一张网络上能不能发起校园认证。 */
    sealed interface Verdict {
        /** 可以。名字要么就是校园网，要么根本没读出来。 */
        data object Allowed : Verdict

        /** 根本没有 Wi-Fi 可认证（手机网络、以太网、或还没连上）。 */
        data object NotWifi : Verdict

        /** 名字读出来了，而且是别的网络 —— 认证不该发到这张网上。 */
        data object OtherNetwork : Verdict
    }

    fun check(network: NetworkSnapshot?): Verdict = when {
        network == null || !network.isWifi -> Verdict.NotWifi
        network.isNamedCampus -> Verdict.Allowed
        // 名字读不出来 ≠ 不是校园网：见类文档第 3 条。
        network.hasUnknownName -> Verdict.Allowed
        else -> Verdict.OtherNetwork
    }

    fun allows(network: NetworkSnapshot?): Boolean = check(network) == Verdict.Allowed

    /**
     * 放行时返回 null，否则返回一句可以直接显示给用户的中文。
     * [NotWifi] 与 [OtherNetwork] 分开说，是因为用户要做的事不一样：一个是去连 Wi-Fi，一个是换一张网。
     * 被拒绝的那张网叫什么名字要写进句子里 —— 名字正是拒绝的理由，藏起来的话，判错时用户和我们都
     * 看不出是哪一步错了（0.8.3 的故障排查就是靠界面上那个名字才对上 `GXNU.YC` 的）。
     */
    fun refusal(network: NetworkSnapshot?): String? = when (check(network)) {
        Verdict.Allowed -> null
        Verdict.NotWifi -> "先连接校园 Wi-Fi，再来这里认证。"
        Verdict.OtherNetwork -> "当前 Wi-Fi「${network?.ssid}」不是校园网，请切换到校园网 Wi-Fi。"
    }
}
