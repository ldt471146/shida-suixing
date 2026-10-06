---
generated_from_state_version: 27
---

# 验证

## 当前结果

- 结果: **已阻塞**
- 验证情况: **解决报告中的阻塞项后恢复验证**
- 目标周期: 2
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-06T11:54:06.890Z
- 摘要: 全新只读 Verifier 已判断 A1–A9 各一次：7 项通过，A2/A9 因新包真实认证和实际安装运行缺证据而阻塞，无已确认代码断言失败。当前 candidateId、executionRef、stateVersion、operation/workspace 均匹配；只读重算输入 gate 和候选 gate 完全一致，四项 Runtime 日志 canonical evidenceDigest 全部匹配，复用 testDebugUnitTest/assembleDebug/lintDebug/Node exit 0 正式记录。XML 共 108 项 Kotlin 测试及 Node 26 项通过，零失败/错误/跳过；lint 0 错误、11 警告。未修改产品文件、未登录/注销宿主校园网、未操作 ADB 或启动模拟器。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 无外网或校园网未认证时，本地首页、账户页及必要资源仍能打开，显示正确网络类别与下一步。 | 本地 Compose 首页、账号、主题和图标无需外网。WifiEnvironment 分辨无 Wi-Fi、目标网络及其他网络；权限或身份不明时有明确入口。核对当前代码与 normal-v2-offline/normal-v2-light 实际截图，网页仅在连接尝试时加载，不阻止离线页面。 |
| A2 | blocked | brief.md | 配置完整并连接目标校园 Wi-Fi 后，一次主操作完成正常认证；只有目标 Wi-Fi 的外网验证成功才显示可上网，不以移动数据成功代替。 | 新候选确实切换到学校原表单 f1 的自动填写和单次点击，并将资源、凭据及 204 验证绑定同一 Android Network。Node/JVM 检查只能证明合成环境的脚本和资源行为。用户已确认 0.2.1 在 K80/GXNU-YC 失败；0.2.2 尚无实体设备正常认证和目标 Wi-Fi 外网结果，不能判通过。 |
| A3 | passed | brief.md | 错误账户、学校超时、非预期响应或必须人工操作时显示原因与处理入口，不误显示在线，也不无限重试错误账户。 | 当前网页转接保留固定账号/验证码/页面失败类别，主文档失败、25 秒网页截止及协调器截止均产生可见原因；首页常驻失败状态并提供重试、账号和学校原页入口。代码与对应 Node/Automation/Coordinator/Feedback 测试核对一致；账号一次提交，失败不会自动无限重试，成功必须 204。 |
| A4 | passed | brief.md | 连续点击、重复网络事件、认证途中换网或修改账户时，同一目标只有一个有效任务，旧结果不能覆盖当前状态。 | 协调器单任务与 generation 失效机制仍生效；Wi-Fi cache revision/epoch 防迟到身份覆盖。账户和运营商修改先撤销旧任务。新桥接在按钮点击前记提交标志并跨 document 保存，只提交一次；取消关闭资源并销毁 WebView。定向并发、网络切换、账号修改、页面重载和取消测试在有效 Runtime 108 项 Kotlin/26 项 Node 记录中通过。 |
| A5 | passed | brief.md | 按用户选择在设备安全存储保存凭据；修改后不再使用旧凭据，删除时清除密码与相关自动配置，仓库和诊断中无明文密码或完整带凭据请求。 | 凭据采用 Android Keystore AES-GCM，普通选项不含密码；串行 IO 队列先撤销旧内存凭据，删除清 key/密文/provider/auto。当前桥接使用 JSON 字符串编码、学校内存 store 和本次隔离 Cookie，不将异常正文、密码或 URL 送诊断。核对存储/删改测试及此前实际加密保存证据；新资源测试验证 Referer 脱敏、外站/注销阻断和 Cookie 隔离。 |
| A6 | passed | brief.md | 自动连接只按确认的平台、触发条件与开关执行；关闭后不自动认证，不在其他网络提交校园凭据，支持范围与实机限制可检查。 | 自动连接仅在用户保存并开启、供应商完整、权限与前台通知服务运行且目标 SSID 匹配时启动。关闭开关和撤销权限停止自动尝试，其他网络无法打开凭据请求；同一 app controller 管理 UI 与服务。代码、有效协调器测试和先前通知暂停/权限实测一致。force-stop/重启需打开 App 的限制已写入 UI/规格，K80 厂商持续性不作为已验证结论。 |
| A7 | passed | brief.md | 浅色、深色、窄屏、大字体和键盘下，主要状态与操作可见、可读、可触控；目标桌面如纳入首发，在 1280×800 下可完成主操作。 | 核对当前浅色默认/可选蓝灰深色、系统 SansSerif、48 dp 操作、IME padding 和滚动/大字号布局，查看 v4 浅深色/320 dp/2 倍字号实际截图与响应布局记录；当前原生结构保留，输入修改只缩小字符状态读取范围。连接动画尊重系统减少动画，状态文字和错误入口可读。Windows 不在首发范围。输入流畅度没有实机测量，不由此判已解决。 |
| A8 | passed | brief.md | 服务页只提供已接入校园网功能并保存后续路线；服务注册与学校协议职责独立，可增加服务而不改写 GXNU 认证核心。 | 服务列表从 CampusModules 注册表生成，仅校园网有可操作路由；后续课表、校历、校园卡/图书馆与 Windows/iPhone 路线有明确未接入说明。UI actions、学校 transport、network bridge、安全存储和 coordinator 职责独立，新增服务不要求改写 GXNU 认证核心。 |
| A9 | blocked | brief.md | 提供可安装的 Android APK 和本机预览入口；在可用模拟器上自动安装并打开 App，模拟器不适合校园真实认证时准确区分界面预览与实机联网验证。 | 已核对当前 0.2.2/versionCode 4 可构建 APK，交付副本与 app-debug.apk SHA256 均为 0FDD1EC1BA18846B913872ED5A8EC56B2E46FCAA2EAA5FFB04069C8B66959371；安装/启动预览脚本存在且明确区分预览认证。但实际安装哈希及截图属于 0.2.0，0.2.2 未在设备自动安装并打开；遵守不操作 ADB、不启动模拟器限制，不能以旧预览证明新包或新 WebView 正常运行。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| testDebugUnitTest | -NoProfile -ExecutionPolicy Bypass -File scripts/check-android.ps1 -Task testDebugUnitTest | . | passed | 0 | 3669 ms |
| assembleDebug | -NoProfile -ExecutionPolicy Bypass -File scripts/check-android.ps1 -Task assembleDebug | . | passed | 0 | 2408 ms |
| lintDebug | -NoProfile -ExecutionPolicy Bypass -File scripts/check-android.ps1 -Task lintDebug | . | passed | 0 | 2542 ms |
| Official form automation | --test scripts/test-portal-bridge.cjs | . | passed | 0 | 1247 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- Pinned resource red-green: passed — 10 real behavioral regressions pass after observed red assertions; official-resources-red.log, official-resources-final-green.log. Same Wi-Fi, charset/gzip, scope/redirect/logout/cookies/cancel boundaries; development evidence only.
- Official form JavaScript red-green: passed — 26 Node tests execute actual portal-bridge.js after initial 17 failures; f1/ee form flow, five suppliers, password preservation, load wait, no repeat, no logout, memory store, safe failure categories. OfficialPortalAutomation decision tests await complete Runtime suite.
- K80 authentication and latency 0.2.2: not-run — 0.2.1 failed on device. New 0.2.2 bridge has no physical target-Wi-Fi authentication or measured input-latency evidence; do not label either solved.
- Existing v4 UI and device preview: passed — Previously recorded actual v4 layout/keyboard/large-text/error screenshots and encrypted save/restart on 0.2.0; current layout retained. Prior emulator frame measurement did not establish smoothness; no new emulator launched.
- 已知限制: A2: K80/GXNU-YC user confirmed 0.2.1 authentication failed before credential submission. 0.2.2 first-party WebView bridge is not yet physically verified on K80.
- 已知限制: No host computer campus authentication/logout/network switch, no continued USB troubleshooting or additional simulator startup.
- 已知限制: Account-input character recomposition path was narrowed; actual K80 input latency and manufacturer background persistence remain unmeasured.
- 已知限制: A7/A9 prior actual v4 preview/installation evidence is for 0.2.0; 0.2.2 retains native layout but adds a temporary headless school WebView, whose device execution is not proven by JVM/Node tests.
- 已知限制: Synthetic credential fixture remains only on isolated gxnu_preview after interrupted delete stage; no user credential was placed in tests or repository.
- 已知限制: A local Git history was initialized with only .gitignore to provide a source fingerprint gate; without Git HEAD, Comet 0.4.3 incorrectly treats its own Gradle outputs as candidate input changes. Current candidate must receive new checks.

## 阻塞项

- **user**: 全新只读 Verifier 已判断 A1–A9 各一次：7 项通过，A2/A9 因新包真实认证和实际安装运行缺证据而阻塞，无已确认代码断言失败。当前 candidateId、executionRef、stateVersion、operation/workspace 均匹配；只读重算输入 gate 和候选 gate 完全一致，四项 Runtime 日志 canonical evidenceDigest 全部匹配，复用 testDebugUnitTest/assembleDebug/lintDebug/Node exit 0 正式记录。XML 共 108 项 Kotlin 测试及 Node 26 项通过，零失败/错误/跳过；lint 0 错误、11 警告。未修改产品文件、未登录/注销宿主校园网、未操作 ADB 或启动模拟器。 (acceptance: A2, A9) — next: `resolve-verifier-blocker`

## 风险与跳过的工作

- 未完成：K80 上 0.2.2 学校原网页加载、实际提交、真实目标 Wi-Fi 204 和断网后重连；0.2.1 已实测失败，不能沿用旧待测结论。
- 未完成：0.2.2 实际安装/启动与新 WebView 的设备执行。此前 v4 UI 和 0.2.0 安装记录只支持保留原生界面的有限结论。
- 未完成：K80 字符输入延迟、冷启动和厂商后台持续性；此前模拟器 33 帧中 22 帧超期，不能声称卡顿已彻底解决。
- 学校改动表单、HTTP/POST 登录或白名单外资源时当前桥接会停止并提示人工入口；未证明所有历史 Dr.COM 模式。
- 专用模拟器仍有此前未执行删除阶段的合成 fixture，仅在该测试设备；不包含用户真实凭据。
- lintDebug 为 0 错误、11 个已知警告；包含较旧依赖、备份规则与图标资源警告。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 0 | recovery | — | Native check input changed after the candidate was built; a new Builder candidate is required before checks can run again. | 2026-10-06T06:07:00.897Z |
| 1 | 2 | 1 | blocked | A2 | 独立只读核查 A1–A9：8 项通过，A2 因缺实体 Android 在 GXNU-YC 的真实认证证据而阻塞。候选绑定 Runtime 的 testDebugUnitTest、assembleDebug、lintDebug 均通过，44 个单元测试零失败/错误/跳过；复用有效记录，本轮未重复完整套件。APK 安装哈希和实际预览一致，用户电脑校园连接未受影响。 | 2026-10-06T06:18:48.234Z |
| 1 | 2 | 1 | recovery | — | 用户要求修正字体和过强的模板设计感：在已确认的浅色校园工具范围内调整字体、字重、字号层次、布局和文案，继续在现有模拟器直接预览。真实手机认证证据仍待补，保留电脑现有校园连接。 | 2026-10-06T07:58:54.385Z |
| 1 | 3 | 0 | recovery | — | Native check input changed after the candidate was built; a new Builder candidate is required before checks can run again. | 2026-10-06T08:22:28.824Z |
| 1 | 4 | 1 | blocked | A2 | 全新的只读 Verifier 独立核查当前候选 A1–A9：8 项通过，A2 因缺实体 Android 校园网正常认证及目标 Wi-Fi 外网证据而阻塞。候选 ID、状态版本、工作区、执行标识及 Runtime 检查 operation/inputFingerprint 一致；复用本轮有效 testDebugUnitTest、assembleDebug、lintDebug 的 exit 0 回执，没有重复完整套件。44 个既有单测 0 失败/错误/跳过；17 张 v3 实际截图、源码字体与布局、独立对比度计算、当前有效 UI 层级和安装 APK 哈希已核对。/dev/tty 首次未返回 XML 的读取未算通过，stdout 补查取得当前 App 的有效层级。Builder 交接仅在完成独立调查后复核。没有修改产品文件、截图或模拟器页面，也没有真实校园认证及宿主网络操作。 | 2026-10-06T08:37:59.101Z |
| 1 | 4 | 1 | recovery | — | 用户安卓真机测试失败：无法连接校园网，仍需手动网页认证；缺少可见的连接过程及成功/失败/错误反馈，明显卡顿，界面设计不满意。按已确认的校园认证、可用反馈、性能与 UI 范围修正实现，先调查真实协议、状态链路与主线程，再提交新的候选；不触碰宿主校园网连接。 | 2026-10-06T09:04:15.870Z |
| 1 | 5 | 0 | recovery | — | Builder handoff Runtime checks failed: android-lintDebug | 2026-10-06T11:07:08.027Z |
| 1 | 6 | 0 | recovery | — | Builder handoff Runtime checks failed: android-lintDebug | 2026-10-06T11:09:51.090Z |
| 1 | 7 | 0 | recovery | — | Native check input changed after the candidate was built; a new Builder candidate is required before checks can run again. | 2026-10-06T11:14:21.570Z |
| 1 | 8 | 0 | recovery | — | Native Shape artifacts changed | 2026-10-06T11:42:18.759Z |
| 2 | 1 | 1 | blocked | A2, A9 | 全新只读 Verifier 已判断 A1–A9 各一次：7 项通过，A2/A9 因新包真实认证和实际安装运行缺证据而阻塞，无已确认代码断言失败。当前 candidateId、executionRef、stateVersion、operation/workspace 均匹配；只读重算输入 gate 和候选 gate 完全一致，四项 Runtime 日志 canonical evidenceDigest 全部匹配，复用 testDebugUnitTest/assembleDebug/lintDebug/Node exit 0 正式记录。XML 共 108 项 Kotlin 测试及 Node 26 项通过，零失败/错误/跳过；lint 0 错误、11 警告。未修改产品文件、未登录/注销宿主校园网、未操作 ADB 或启动模拟器。 | 2026-10-06T11:54:06.890Z |



## 结论

全新只读 Verifier 已判断 A1–A9 各一次：7 项通过，A2/A9 因新包真实认证和实际安装运行缺证据而阻塞，无已确认代码断言失败。当前 candidateId、executionRef、stateVersion、operation/workspace 均匹配；只读重算输入 gate 和候选 gate 完全一致，四项 Runtime 日志 canonical evidenceDigest 全部匹配，复用 testDebugUnitTest/assembleDebug/lintDebug/Node exit 0 正式记录。XML 共 108 项 Kotlin 测试及 Node 26 项通过，零失败/错误/跳过；lint 0 错误、11 警告。未修改产品文件、未登录/注销宿主校园网、未操作 ADB 或启动模拟器。
