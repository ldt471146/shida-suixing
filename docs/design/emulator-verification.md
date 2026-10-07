# Android 实际预览检查

2026-10-06，在本机已有的 Android 模拟器程序中运行 `gxnu_preview`（Android 14、x86_64、1080×2280、440 dpi）。虚拟设备位于 `D:\Android\avd`；没有切换或注销电脑的校园网连接。所有账号检查使用虚构数据，测试账号已删除。

## 本轮观察

- 新 UI 的 Debug APK 构建、更新安装及启动成功。实际执行 `scripts/open-campus-preview.cmd` 能再次打开现有模拟器中的 App，APK 大小为 20,114,251 字节。
- 正常模式识别 `AndroidWifi` 为“其他网络”，显示当前名称与 Wi-Fi 设置入口，不把宿主电脑已联网当成校园认证成功。关闭模拟器 Wi-Fi 后，首页仍可打开并显示“未连接”；随后恢复了模拟器 Wi-Fi。
- 空账号和空密码分别显示字段错误。输入虚构账号并保存后，重启实际 App 恢复脱敏账号、校园网供应商与自动开关，直接进入首页。
- 在设备 `run-as` 检查结果中，凭据偏好含 `encrypted_payload`；普通偏好与凭据文件均不含虚构账号或密码的明文。没有导出密文或实际密码到本文。
- 自动连接启用后，实际前台服务显示 `isForeground=true`，通知提供“暂停自动连接”。实际点击通知暂停有效；撤销模拟器位置权限后服务停止。测试后恢复了原权限。
- 删除账号后，密文字段和供应商字段均移除，自动设置为 false，服务停止；重启返回首次设置。后续虚构账号也已通过同一界面删除，正常模式保存的主题为浅色。
- 预览模式可选择供应商、切换自动开关，主按钮依次演示认证、验证与在线；加载、账号错误和成功截图均保留“界面预览 · 未执行校园认证”标识。
- 浅色与可选蓝灰深色均检查了实际界面。宽度 320 dp（880×1850 / 440 dpi）和系统字体 1.4 倍时，主要状态及连接按钮仍在首屏可读、可操作；插图自动让出空间。窄屏下较低优先级内容通过滚动访问。系统尺寸与字体缩放已恢复。
- 软键盘展开时账号与密码字段可操作，页面可滚动；收起键盘后保存成功。三个导航入口、主题选择和服务页正常；未接入服务只显示计划。
- 本轮读取的 AndroidRuntime 错误日志中，`FATAL EXCEPTION` 数量为 0。

## 实际截图

| 场景 | 证据 |
| --- | --- |
| 新版浅色首页（预览） | [首页](screenshots/home-v2-light.png) |
| 供应商选择 | [供应商](screenshots/provider-v2-light.png) |
| 正常模式、其他 Wi-Fi | [真实设备状态](screenshots/normal-v2-light.png) |
| 正常模式、离线 | [离线首页](screenshots/normal-v2-offline.png) |
| 账号浅色 | [账号页](screenshots/account-v2-light.png) |
| 字段验证 | [错误提示](screenshots/account-v2-validation.png) |
| 可选深色与软键盘 | [账号与键盘](screenshots/account-v2-dark.png) |
| 可选深色首页 | [深色首页](screenshots/home-v2-dark.png) |
| 窄屏 320 dp | [窄屏](screenshots/home-v2-narrow.png) |
| 系统字体 1.4 倍 | [大字体](screenshots/home-v2-large-text.png) |
| 加载、错误、在线（均为预览） | [加载](screenshots/home-v2-loading.png)、[账号错误](screenshots/home-v2-auth-error.png)、[在线](screenshots/home-v2-online.png) |
| 服务与我的 | [服务](screenshots/services-v2-light.png)、[我的](screenshots/profile-v2-light.png) |
| 自动连接通知 | [通知](screenshots/auto-notification-v2.png) |

## 边界

模拟器的虚拟 Wi-Fi 不能完成实体 Android 手机在 `GXNU-YC` 的认证验收。用户已取消使用当前电脑做真实登录测试，未提交其实际密码，也未发送登录或注销请求。实体手机的真实认证、断网重连及不同厂商后台策略仍需在该网络上实测。

本文记录实际设备操作，不替代 Comet Runtime 的候选检查及独立验收。完整单元测试、APK 构建及 lint 的最终结果由 Runtime 保存。

## 0.4.1 检查

2026-10-07，在同一 `gxnu_preview` 模拟器上检查 0.4.1（versionCode 8）。清理构建后 30 个测试套件 318 项全绿，`lintDebug` 0 错误，`assembleRelease` 产出 11,135,050 字节的签名包（证书指纹与 0.4.0 相同）。

本轮修的是用户反馈的两个原始问题的边缘情况，以及一处会丢数据的缺陷：

- **内嵌登录页在旋转后消失**（会丢数据）。`MainActivity` 用普通字段持有页面，且没有声明 `configChanges`，所以旋转会重建 Activity：页面所在的 composition 被销毁、`destroy()` 清掉 `WebStorage`，用户半填的表单和验证码一起丢失，而且这次尝试的结算被跳过。修复方式是给 Activity 声明 `configChanges`（orientation、screenSize、smallestScreenSize、screenLayout、keyboardHidden、navigation、uiMode），不重建就不会有销毁。**density、fontScale、locale 特意不声明**：Compose 在组合开始时就读取这四个值，且本应用有四处按 `fontScale` 布局，这些配置变化本身就需要一次新的 Activity。
  证据：修复前旋转后页面消失、WebView 渲染进程死亡；修复后竖屏→横屏→竖屏全程页面保持，**渲染进程 pid 始终是同一个**。另外用一项未声明的配置变化（`fontScale` 1.3）作对照：页面仍然结束，但这次只结算一次，不崩溃也不留僵尸页面。
  独立复核：读取 Android 活动事件日志，旋转前后 `wm_on_create_called` 计数不变、`wm_on_destroy_called` 为 0，确认 Activity 没有被重建。

- **学校认证页没有真正的加载态和错误态**。原来的「加载中」是一个固定 1.2 秒计时器，`onReceivedError` 被置空吞掉。根因比表面更深：主文档由 `shouldInterceptRequest` 拦截提供，打不开的页面会以**内容为空的 502 文档**交给 WebView——它不是网络错误，所以 `onReceivedError` 基本不会触发，而 `onPageFinished` 会在空白页上正常触发。只接回调会把空白页当成「已就绪」。现在由每轮加载的失败闩锁提供真实状态，来源是主文档的 `reply.failure`、主文档的 `onReceivedError`（子资源忽略）和 SSL 取消。
  用户现在看到：页面打不开 → 危险色胶囊「学校认证页未能打开」+ 中文原因 + 重新加载；页面慢 → 进度胶囊「正在打开学校登录页… 42%」。

- **服务页会先报「未连接」**。运行时还没读完 Wi-Fi 设置时，状态默认是 `NO_WIFI`，而服务页没有像首页那样守 `initializing`，于是同一状态下首页说真话、服务页说假话。已对齐。

- **「我的 → 网络与通知权限」没有任何状态提示**。现在显示 已授予 / 未授予，并在回到前台时重新读取；说明文字也讲清点按会跳系统设置。

- **账号页输入路径**。每次按键会重建 4 个 `OutlinedTextField` 参数，编译后实测每次按键 3 次 lambda 包装分配。已把它们提进 `remember`，同样是编译后实测为 0 次。字段配色**不能**用 `remember` 缓存——Material3 1.3.0 的 `OutlinedTextFieldDefaults.colors()` 本身是 `@Composable`，而且 `TextFieldColors` 按字段比较，本来就不会因为重建而多重组。测试锁定了这一点。

- **图标不再是第二套蓝色**。应用图标底色改为与应用主色一致（`#325FA4`），并去掉了前景层那块不透明满幅方块——它此前完全盖住了自适应图标声明的底色。

**真实校园网络的一次观察**：模拟器的出口经宿主电脑，宿主已在校园网内，因此模拟器能打开真实的学校认证页。页面在旋转前后保持同一渲染进程。本次没有提交任何凭据，也没有点注销；页面显示的「已登录成功」是门户对宿主既有会话的反映，不是应用提交的结果。应用自身也没有因此报「在线」——它的在线状态仍然只由绑定目标 Wi-Fi 上的 HTTPS 204 探测决定。

**未验证**：更新流程的下载与系统安装确认仍需在实体手机上确认；`fontScale`/`density`/`locale` 变化仍会结束登录页（按设计，会正常结算一次）；相机拍照识别路径仍未实拍。

## 0.4.0 检查

2026-10-06 / 10-07，在同一 `gxnu_preview` 模拟器上检查 0.4.0（versionCode 7）：

- 构建、更新安装与启动成功，`FATAL EXCEPTION` 计数为 0。清理构建后 27 个测试套件 285 项全绿，`lintDebug` 0 错误，`assembleRelease` 产出 11,118,690 字节的签名包，证书指纹 `9aecff4b…29cb` 与发布密钥一致。
- 首页、服务、课表、我的与账号页均为新版视觉：软灰画布、白色浮起卡片、单一蓝色主操作、描边图标、状态胶囊。深色主题同步更新。
- 课表端到端跑通（用真实接口，非模拟）：选图 → 上传 → 识别 → 渲染 → 落盘 → 重装后重新读取。合成课表图含 13 门课（含单双周标注与合并单元格），识别出 12 门，教师与教室均正确。周次筛选经核对：双周限定的课在第 1 周不出现。
- 设置开学日期后自动定位到当前周（第 3 周，10月5日–10月11日），并显示「当前周」胶囊；未设置时按第 1 周显示并提示。
- 点课程打开详情面板，显示上课时间、上课周次、单双周、任课教师、上课地点与当周是否有课。
- 周课表网格：五天全部显示且无需横向滚动，课程名按两字换行可读，每格显示教室，当天列用强调色胶囊标记。

| 场景 | 证据 |
| --- | --- |
| 课表空状态 | [课表空状态](screenshots/v6-timetable-empty.png) |
| 识别中 | [识别中](screenshots/v6-timetable-recognising.png) |
| 识别完成（12 门课） | [识别完成](screenshots/v6-timetable-recognized.png) |
| 当前周与今日课程 | [当前周](screenshots/v6-timetable-currentweek.png) |
| 课程详情面板 | [详情](screenshots/v6-timetable-detail.png) |
| 周课表网格 | [周课表](screenshots/v6-timetable-grid.png) |
| 设置开学日期 | [开学日期](screenshots/v6-timetable-termstart.png) |

**更新流程未在设备上端到端验证**：仓库标签数为 0，`releases/latest` 返回 404，应用如实显示「暂无发布版本」。下载与系统安装确认需要先有一个已发布的 Release，且安装确认行为需在实体手机上确认。

## 0.3.0 检查

2026-10-06，在同一 `gxnu_preview` 模拟器上检查 0.3.0（versionCode 6）：

- 构建、更新安装与启动成功，`versionName=0.3.0`，每次启动后 `FATAL EXCEPTION` 计数均为 0。
- 界面重做后首页、服务、我的、账号页均为软灰画布配白色浮起卡片；浅色与深色两套配色都已实拍，深色是炭灰画布而非纯黑。
- 「服务」页列出了「课表」，原「后续计划」中的课表已移除，说明导航接线生效。
- 「课表」页可正常进入：标题与说明、识别服务卡片（API Key 输入、保存按钮）、选择图片 / 拍照 / 开始识别、以及「还没有课表」空状态都正常渲染；未填写 Key 时识别按钮为禁用态。
- 账号页改用统一的页头组件（标题 + 弱化副标题），保存开关、主按钮与说明文字与其余页面一致。
- 本轮未登录或注销宿主电脑的校园网，未使用真实账号，未调用真实识别接口。


## 0.2.3 内嵌登录页检查

2026-10-06，在同一 `gxnu_preview` 模拟器上检查 0.2.3（versionCode 5）：

- 构建、更新安装与启动成功，`versionName=0.2.3`，`FATAL EXCEPTION` 计数为 0。
- 在 `AndroidWifi`（非校园网络）下点击「学校认证页面」，App 不打开页面，提示「请先连接 GXNU-YC 并设置校园网账号和供应商」，避免把凭据提交到其他网络。
- 内嵌页面标题栏、说明文字与「重新加载」按钮布局正常。学校域名在模拟器虚拟 Wi-Fi 下无法访问，页面显示系统网络错误而非空白或崩溃；真实表单自动填写仍需实体手机确认。
- 本轮未登录或注销宿主电脑的校园网，未使用真实账号；测试用虚构数据。


## 字体与排版调整后的检查

2026-10-06，再次构建、更新安装和打开当前 App。APK 为 17,075,760 字节，旧字体已从 Android 资源移到文档留档。当前中英文使用系统 SansSerif；截图均由模拟器实际输出，预览仍有明确标识。

- 实际选择中国电信，供应商显示更新；再启动预览恢复校园网。原生自动开关实际关闭并恢复，UI 快照确认关闭时 `checked=false`。此过程只改变预览内存状态。
- 账号页的 15 sp 输入样式、明确标签和密码显隐入口可读。空字段提交出现账号和密码的就近错误；实际展开软键盘，账号页支持滚动和 IME 操作。本次没有输入或保存真实凭据。
- 浅色、可选蓝灰深色、320 dp 宽度、1.4 倍及 2.0 倍系统字体均有实际截图。大字与窄屏时状态换到网络名下方，主按钮仍在普通竖屏首屏。横屏主操作通过页面滚动到达；平板宽度 800 dp 时内容保持居中且不过度拉宽。
- 在模拟器把动画时长缩放设为 0，实际开关仍可操作；检查后恢复原设置。加载预览显示原生进度，UI 快照确认主按钮 `enabled=false`；错误状态保留重试操作。
- 本轮独立计算文字对比：正文/白卡 12.50:1、辅助字/白卡 4.95:1、辅助字/奶白背景 4.65:1、辅助字/内层背景 4.63:1、按钮白字/主色 5.55:1、深色辅助字/卡片 6.71:1。
- 取证工具在一次 App 重启后返回空 UI 根节点，该次读取不计作通过。已补查有效快照与实际截图，临时工具随后只接受成功生成且包含当前 App 的快照，避免读取旧文件。
- 检查结束恢复了 1080×2280、字体 1.0、自动旋转 1、用户旋转 0；动画时长缩放原为未设置，已删除临时覆盖。当前为浅色 READY 预览。读取 AndroidRuntime 日志，`FATAL EXCEPTION` 数量为 0。

当前截图：[首页](screenshots/home-v3-light.png)、[账号](screenshots/account-v3-light.png)、[供应商](screenshots/provider-v3-light.png)、[服务](screenshots/services-v3-light.png)、[我的](screenshots/profile-v3-light.png)、[字段错误](screenshots/account-v3-validation.png)、[键盘](screenshots/account-v3-keyboard.png)、[窄屏](screenshots/home-v3-narrow.png)、[1.4 倍](screenshots/home-v3-large-text.png)、[2.0 倍](screenshots/home-v3-largest-text.png)、[可选深色](screenshots/home-v3-dark.png)、[横屏主操作](screenshots/home-v3-landscape-action.png)、[平板](screenshots/home-v3-tablet.png)、[加载](screenshots/home-v3-loading.png)、[认证错误](screenshots/home-v3-auth-error.png)。

本轮 UI 检查没有校园认证、注销或宿主 Wi-Fi 操作。实体手机真实认证与后台持续性仍待在 `GXNU-YC` 上实测。

## 0.2.0 连接反馈检查

本轮最终 APK 为 16,862,813 字节，SHA-256 为 `F4A7FAECD6888E08A94874F2100FBCFBE2B5B23B23152E59C05CF6CD41CD6C27`。已安装到同一个模拟器并从设备拉回安装包核对哈希；最终正常、320 dp 与两倍字号检查采用该包。

已实际操作连接、取消、重试、错误恢复、账号校验、密码显隐及键盘下滚动。连接过程显示阶段和真实等待秒数，终态保留操作入口。运营商值与箭头在正常、窄屏和两倍字号下均位于行右侧。动画开启时两帧圆环区域变化为 1,890 像素、固定图形区域为 0；关闭动画时圆环静止且取消有效。模拟器的原始动画设置为未设置，本轮未把该状态下的静止画面计作动画通过；临时设置已逐项恢复。

完整交互记录、初始包与最终包的证据边界及最终截图见 [v4 设计记录](ui-v4-notes.md)。红米 K80 在 GXNU-YC 的真实认证及厂商后台持续性仍需实体设备结果。
