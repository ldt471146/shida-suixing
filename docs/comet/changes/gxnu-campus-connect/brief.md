# Outcome

为广西师范大学学生建立可持续扩展的校园 App。首期解决连接校园 Wi-Fi 后需要手动打开浏览器、访问认证页面才能上网的问题，提供快速认证和真实的网络状态反馈。

# Scope

本 change 首发 Android，设计并实现校园服务外壳、校园网模块、独立账号页面、运营商选择、连接反馈、安全保存和自动连接。Windows 排第二、iPhone 排第三，在本期规划接口与设计复用，后续分别交付。Android 产物需能在本机模拟器中直接预览，尽量自动安装免去手动转移 APK。

## Source coverage

覆盖边界为用户本轮描述的校园网快速登录、校园 App 扩展规划和移动界面设计。学校页面调查认证流程及必要网络依赖；Mobbin 是公开移动界面的设计参考，不要求覆盖整站数据库。

| 来源条目与位置 | 读取状态 | 需要保留的内容 | Spec 位置 | 验收 ID | 覆盖状态 | 理由或替代关系 |
| --- | --- | --- | --- | --- | --- | --- |
| S1：用户需求，Wi-Fi 连接后手动打开网页 | complete | 快速认证与可辨认的网络状态 | specs/campus-network/spec.md，NETWORK-2/3/5 | A1–A6 | covered | Android 首发与自动认证已明确 |
| S2：用户需求，未来拓展校园功能 | complete | 可扩展的模块、导航和路线 | specs/campus-shell/spec.md，SHELL-1/4；docs/campus-app-design.md | A7、A8 | covered | Windows、iPhone 和校园服务路线已明确 |
| S3：https://yc.gxnu.edu.cn/，公开 pc.js/a40.js/a41.js | complete | 登录模板、运营商映射、生效 Portal 请求与终端字段来源 | specs/campus-network/spec.md，NETWORK-1/6 | A2、A3 | covered | 已读在线页与未认证模板，纠正早期备用流程推断；真实认证另行验证 |
| S4：https://mobbin.com/ | complete | 实看 Brilliant 主操作首页、Ultrahuman 多状态首页，并补充支付宝中文服务入口参考 | docs/campus-app-design.md 的参考表及 docs/design/references/ | — | background | 覆盖公开设计参考边界；Swiggy 仅页面描述，不推断未见截图或付费流程 |
| S5：用户本轮答复，平台与电脑预览 | complete | Android 首发，Windows 第二，iPhone 最后；本机模拟器直接预览 | specs/campus-shell/spec.md，SHELL-1/5 | A7、A9 | covered | 首发顺序已明确，模拟器适配由事实调查确定 |
| S6：用户本轮答复，账号、运营商与自动连接 | complete | 先在独立校园网账号页面保存信息，选运营商连接，下次自动 | specs/campus-network/spec.md，NETWORK-3/4/5 | A2、A5、A6 | covered | Q3 已确认使用校园网账号，无 App 自有注册服务 |

# Non-goals

首期不接入教务、课表、成绩、校园卡、图书馆或缴费，不绕过校园认证。未来规划不代表已获得学校接口。首发只交付 Android；自动认证的运行条件需按 Android 限制说明与验证。

# Acceptance examples

- 无外网或校园网未认证时，本地首页、账户页及必要资源仍能打开，显示正确网络类别与下一步。
- 配置完整并连接目标校园 Wi-Fi 后，一次主操作完成正常认证；只有目标 Wi-Fi 的外网验证成功才显示可上网，不以移动数据成功代替。
- 错误账户、学校超时、非预期响应或必须人工操作时显示原因与处理入口，不误显示在线，也不无限重试错误账户。
- 连续点击、重复网络事件、认证途中换网或修改账户时，同一目标只有一个有效任务，旧结果不能覆盖当前状态。
- 按用户选择在设备安全存储保存凭据；修改后不再使用旧凭据，删除时清除密码与相关自动配置，仓库和诊断中无明文密码或完整带凭据请求。
- 自动连接只按确认的平台、触发条件与开关执行；关闭后不自动认证，不在其他网络提交校园凭据，支持范围与实机限制可检查。
- 浅色、深色、窄屏、大字体和键盘下，主要状态与操作可见、可读、可触控；目标桌面如纳入首发，在 1280×800 下可完成主操作。
- 服务页只提供已接入校园网功能并保存后续路线；服务注册与学校协议职责独立，可增加服务而不改写 GXNU 认证核心。
- 提供可安装的 Android APK 和本机预览入口；在可用模拟器上自动安装并打开 App，模拟器不适合校园真实认证时准确区分界面预览与实机联网验证。

# Constraints and invariants

使用用户的合法校园账户执行学校正常认证流程。认证入口按用户提供的 https://yc.gxnu.edu.cn/ 调查。凭据不进入仓库或日志，保存方式按平台安全存储能力设计。成功状态须经真实网络验证，离线时应用核心页面仍可用。

# Decisions

- 用户已明确首期只做校园网登录，整体按可拓展的校园 App 规划。
- 用户已明确参考 Mobbin 手机 App 案例并重视前端视觉质量。
- Comet 已创建 gxnu-campus-connect，使用当前目录 D:\gxsf。
- 这是新项目，按 architectural 路径调查与设计；Native 正式规格作为统一设计来源。
- 只读环境调查确认暂无产品代码、Git 仓库或 CodeGraph 索引；本机具备 Node、JDK 17 与 Android SDK。
- 当前电脑通过 WLAN 连接 GXNU-YC。浏览器访问学校入口显示已登录，页面明确提示 120 分钟不上网需重新登录。
- 公开 a40.js / a41.js 已确认 Dr.COM/ePortal 体系。早期 default_login 是备用流程；后续 login.login 赋值覆盖分派，现网 login_method=1 使用 https://yc.gxnu.edu.cn:802/eportal/portal/login。Portal 密码不做 Base64/MD5，Android 普通前缀 ,1,，再追加账号与供应商后缀。result=1 或 ok 仍需验证目标 Wi-Fi 外网。
- 调查未主动填写账户、点击登录或触发注销；当前观察不能证明未认证时仅打开网页即可上网，也不能证明移动端认证已成功。
- 已保存平台中立的架构草案 docs/campus-app-design.md 和移动端视觉示意 docs/design/campus-mobile-concept.png；视觉示意不是产品实测截图。
- Mobbin 的两份实际参考截图及支付宝官方公开展示已保存到 docs/design/references/，原始页面链接与观察范围在设计草案登记。
- 首期作为一个 Native change 推进：界面、账户、协议和系统能力紧密相关，当前没有需要独立交付的多个服务；暂不拆 Supervisor 子 change。
- Q1 已确认：Android 手机优先，Windows 次之，iPhone 最后；本期交付 Android，后两者进入后续路线。
- 用户确认期望流程：独立账号登录并保存 → 选择校园网供应商 → 连接 → 下次自动连接。Q2 的使用意图已明确；学校运营商名称与协议参数仍由只读调查核实。
- 本机已有 qz_arm64 不适合 Intel CPU；已为预览创建 gxnu_preview，API 34 / x86_64 / Pixel 4，实测 boot_completed=1、1080×2280、440 dpi。核验用隐藏实例已关闭，交付时打开可见窗口。
- Q3 已确认：使用独立的校园网账号页面，不建设 App 自有注册/登录服务。账号与密码只供学校正常认证，首页保留供应商选择。
- 学校 pc.js 登录模板已实际读到五项运营商：校园网（空后缀）、中国电信（@ctc）、中国联通（@cuc）、中国移动（@cmc）、广电网络（@gd）。实际认证须按生效配置选择 ePortal 或传统 Dr.COM，不只依据早期默认函数。
- Android 采用 Kotlin + Jetpack Compose 原生实现，首期重视 Wi-Fi 网络绑定与自动认证稳定性；未来 Windows/iPhone 通过协议与服务接口复用规划接入。
- 用户在红米 K80 的 GXNU-YC 上确认 0.2.1 仍无法解析学校认证配置，明确要求下一版转接学校原网页：填写已保存账号、密码，选择供应商，点击原登录按钮。0.2.2 依此调整认证适配器，保留全部已确认验收项、原生界面、安全保存及目标 Wi-Fi 的真实联网判断。实现与验证边界见 docs/design/official-portal-bridge.md。
- 预览环境已确认 WHPX 可用、本机已有 API 34 x86_64 镜像；创建独立 gxnu_preview，不覆盖原来的 qz_arm64。构建工具只在进程中设置正确 JDK，不改变系统 JAVA_HOME。

# Open questions

Q1、Q2、Q3 均已有明确答复，没有未解决的用户决定。活动认证函数、供应商映射与模拟器启动已完成核对，进入完整 Shape 确认准备。

# Verification expectations

检查所选平台的可运行构建、网络状态与错误反馈、敏感信息保护、核心流程定向测试，以及浅色/深色实际界面。真实认证需在校园 Wi-Fi 下由用户本地输入账户验证；演示结果不可代替真实网络验收。

实施计划位于 docs/superpowers/plans/2026-10-06-gxnu-campus-connect.md，按接口先准备工程与核心协议，再并行推进界面和 Android 设备能力；Runtime 最终执行完整检查并启动新的只读 Verifier。模拟器已验证可用，交付时会打开实际 App；初次权限与后台限制需在界面中说明。
