# GXNU Campus Connect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 交付 Android 校园网 App：独立账号页安全保存、选择学校真实供应商、自动认证，并在电脑模拟器直接预览。

**Architecture:** Kotlin 状态与协议层不依赖页面，Android 系统桥负责目标 Wi-Fi、安全存储和前台服务。Compose 通过单向状态和事件渲染首页、服务与我的。用户选择 Comet Native；Runtime 管理完整 Shape、候选实现、独立 Verifier 和交付，不另建审批工作流。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose / Material 3、Android API 26–34、JDK 17、Gradle 8.7 / AGP 8.6.1；本机 API 34 x86_64 AVD。版本可在不改变产品行为时为兼容构建调整。

**Spec:** [校园网](../../comet/changes/gxnu-campus-connect/specs/campus-network/spec.md)、[应用外壳](../../comet/changes/gxnu-campus-connect/specs/campus-shell/spec.md)、[整体设计](../../campus-app-design.md)。

## Global Constraints

- 首发 Android；Windows 第二，iPhone 第三，本期不声称已交付后两者。
- 使用独立校园网账号页面，无自建云端账户或注册服务。
- 当前 Portal 登录端点是 https://yc.gxnu.edu.cn:802/eportal/portal/login，不能误用早期传统表单请求。
- 供应商：校园网、中国电信、中国联通、中国移动、广电网络；后缀分别为空、@ctc、@cuc、@cmc、@gd。
- 核心界面离线可用，浅色与深色都有明确状态和主要操作。
- 只在目标校园 Wi-Fi 认证，联网检测不能走移动数据；敏感信息不进入普通存储、日志或诊断。
- 自动连接由用户设置与 Android 前台服务执行，持续通知可暂停；强制停止或重启后重新打开 App 恢复。
- 在已验证的 gxnu_preview 模拟器更新安装与打开；预览状态与真实校园认证分开报告。

## Review Focus

- 密码包含空格、加号、&、= 或非 ASCII 字符时保持原值并正确编码，不生成额外查询字段；由 Task 1 验证。
- 用户换网或编辑账号时旧认证迟到，不覆盖新状态；由 Task 1 验证。
- 用户拒绝网络识别或通知权限时仍能保存账号、理解限制并手动恢复；由 Task 2/3 检查。
- 删除账号后前台服务仍存活，不得再取旧密码发出认证；由 Task 2 验证。
- 模拟器虚拟网络及演示状态不能变成真实校园认证成功；由 Task 3 检查。

## 文件边界与接口

`app/src/main/java/cn/gxnu/campus/core/Models.kt` 定义跨文件契约：

```kotlin
enum class Provider(val title: String, val suffix: String) {
    CAMPUS("校园网", ""), TELECOM("中国电信", "@ctc"),
    UNICOM("中国联通", "@cuc"), MOBILE("中国移动", "@cmc"),
    BROADCAST("广电网络", "@gd")
}
data class Credentials(val account: String, val password: String) // toString 必须脱敏
enum class ConnectionStatus {
    NO_WIFI, OUTSIDE_CAMPUS, NEED_ACCOUNT, NEED_PROVIDER, NEED_PERMISSION,
    READY, AUTHENTICATING, VERIFYING, ONLINE, AUTH_ERROR, UNREACHABLE
}
data class NetworkSnapshot(val id: String, val ssid: String, val isWifi: Boolean)
data class PortalContext(
    val ipv4: String, val ipv6: String = "", val mac: String = "000000000000",
    val acIp: String = "", val acName: String = "", val jsVersion: String = "4.2.2"
)
interface ConnectionTransport {
    suspend fun authenticate(network: NetworkSnapshot, credentials: Credentials, provider: Provider): Boolean
    suspend fun verifyInternet(network: NetworkSnapshot): Boolean
}
```

Compose 与控制器通过 `CampusUiState` 和 `CampusActions` 连接。公开状态包含状态类别、脱敏账号、是否已设置账号、选中供应商、自动连接、主题、预览标记和可显示反馈，不含密码。Actions 公开 `connect()`、`selectProvider(Provider)`、`saveAccount(String,String,Boolean)`、`deleteAccount()`、`setAutoConnect(Boolean)`、`setTheme(ThemeMode)` 与 `requestPermissions()`。

## Task 1：可构建工程、协议与连接状态

**Files:** 创建 `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、Gradle Wrapper、`app/src/main/AndroidManifest.xml`；创建 `core/Models.kt`、`core/PortalProtocol.kt`、`core/ConnectionCoordinator.kt`；测试放在 `app/src/test/java/cn/gxnu/campus/core/`。

**Interfaces:** 消费学校公开 HTML/JSONP 样本与规格；产出上面的类型、`PortalProtocol.loginParameters(Credentials,Provider,PortalContext): Map<String,String>`、`PortalProtocol.parseResponse(String): Boolean` 与可取消的 `ConnectionCoordinator`。

- [x] 设置工程包名 `cn.gxnu.campus`、应用名“师大随行”、JDK 17 与 SDK 目录；仅进程配置 JAVA_HOME，不修改系统设置。
- [x] 先写协议行为测试，运行定向 Gradle 测试确认缺失行为后再实现。例如：

```kotlin
@Test fun mobileUsesSchoolRealmAndPreservesPassword() {
    val password = "a+b&c= d"
    val parameters = PortalProtocol.loginParameters(
        Credentials("student01", password), Provider.MOBILE,
        PortalContext(ipv4 = "10.0.0.99")
    )
    assertEquals(",1,student01@cmc", parameters["user_account"])
    assertEquals(password, parameters["user_password"])
    assertEquals("2", parameters["terminal_type"])
    assertFalse(parameters.containsKey("DDDDD"))
}
```

- [x] 补充 JSONP 包装、错误结果、恶意非数据响应、已带供应商后缀、账号空值及密码编码测试；返回数据使用固定虚构样本，不复制真实用户信息。
- [x] 实现当前 Portal 参数、严格数据解析、页面终端字段发现和可显示错误；不执行远程 JS，不启用未知加密模式。
- [x] 为连续点击、认证中换网、账户修改、认证成功但外网失败写行为测试。测试使用真实协调器和受控传输实现，延迟任务返回以验证旧结果失效，而不是仅断言 mock 调用。
- [x] 实现任务去重、取消和阶段状态，跑定向测试，保留检查结果供最终 Runtime 复用或重新执行。

## Task 2：Android 网络、安全保存与自动认证

**Files:** 创建 `data/CredentialStore.kt`、`data/CampusPreferences.kt`、`network/WifiEnvironment.kt`、`network/PortalClient.kt`、`service/AutoConnectService.kt`、`CampusApplication.kt`、`CampusViewModel.kt`。

**Interfaces:** 消费 Task 1 类型和协调器；产出 `CredentialStore.save(Credentials)`、`load(): Credentials?`、`clear()`；产出 `WifiEnvironment` 的目标网络状态；`PortalClient` 实现 `ConnectionTransport`。`CampusViewModel` 提供 `StateFlow<CampusUiState>` 与 `CampusActions`。

- [x] Android Keystore AES/GCM 保存账号密码，普通偏好仅保存密文、供应商、主题及自动开关。每次保存使用新 IV；关闭保存只保留本次会话。
- [x] 在模拟器用虚构账号检查保存后的普通偏好不含明文，重启应用能恢复配置，删除后加载为空且自动连接关闭。
- [x] 注册未要求 VALIDATED 的 Wi-Fi 回调，以 GXNU-YC 识别目标；认证与外网探测使用目标 `Network.openConnection`，禁止回退到默认移动数据。
- [x] 在目标网络 GET 学校入口及必要只读配置，提取终端参数；按现网 ePortal 登录，严格处理超时、证书错误、非法响应和账户错误。
- [x] 前台服务先显示持续通知，再监听和认证；提供“暂停自动连接”操作。权限被拒绝或撤销时返回明确状态，不继续发旧凭据。
- [x] 验证删除账号期间的迟到任务、权限撤销及自动开关关闭。外网探测选择无需账户的 HTTPS 验证端点并核对预期响应，不能把可打开学校内网页当成联网成功。

## Task 3：移动界面、模块注册与电脑预览

**Files:** 创建 `ui/CampusApp.kt`、`ui/screens/HomeScreen.kt`、`ui/screens/AccountScreen.kt`、`ui/screens/ServicesScreen.kt`、`ui/screens/ProfileScreen.kt`、`ui/theme/CampusTheme.kt`、`MainActivity.kt`、`core/CampusModules.kt`；创建 `scripts/preview-android.ps1` 与使用说明。

**Interfaces:** 只消费 `CampusUiState`、`CampusActions`；不直接构造认证 URL。模块描述包含稳定 ID、名称与打开事件，首期只注册校园网。

- [x] 按 `docs/design/ui-v2-notes.md` 的第二版视觉体系实现，首页增加真实供应商选择，保持主状态与按钮首屏可见。
- [x] 首次进入独立账号页；保存后进入首页，供应商记忆，下次不用再次输入。表单固定标签、密码显示切换与字段错误完整。
- [x] 用主题 token 实现浅色与深色；首页、服务、我的固定导航，窄屏、大字体、安全区域和软键盘均可操作。只给有真实模块的入口交互。
- [x] 增加仅限 debug 意图启用的预览状态，并清楚标注预览、不发送真实认证请求；发布行为仍以真实网络结果为准。
- [x] 预览脚本选择 gxnu_preview 并核实对应设备，使用已安装 SDK/JDK 构建、`adb install -r` 更新、启动 Activity。启动新窗口前检测设备，不停止其他实例。
- [x] 在可见模拟器打开 App，实际点击账号、供应商、自动开关与导航，检查保存、删除、深色、输入错误和预览状态。截图记录实际 UI，不能只交设计示意。

## 最终验证与交接

- [x] Builder 定向检查后将 `testDebugUnitTest`、`assembleDebug`、`lintDebug` 等最终计划交 Runtime，不提前重复同一完整检查集。
- [x] 新的只读 Verifier 按 Native 协议判断全部验收项，复用与候选绑定的检查，只补缺失项。
- [x] 打开电脑模拟器中的真实 App，交付 APK、预览命令和脱敏截图；真实校园认证是否实际执行单独报告，不能由预览状态代替。
- [ ] 经用户接受后按 Runtime 执行交付与归档，仅处理本 change；记录可复用学校协议事实和任务学习检查。

2026-10-06：Runtime 接受独立验收结果，A1、A3–A9 通过；A2 因缺少实体 Android 在 `GXNU-YC` 的真实认证证据而待补。44 个单元测试通过，APK 构建通过，lint 为 0 错误、11 警告。模拟器内安装文件与交付 APK 哈希一致，浅色预览已打开。未对电脑发送校园登录或注销请求，暂未接受最终结果或归档。正式结论以 [Runtime 验收报告](../../comet/changes/gxnu-campus-connect/verification.md) 为准。
