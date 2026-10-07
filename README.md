# 师大随行

广西师范大学校园 App 的 Android 首版。首次在独立账号页设置校园网账号和密码，首页选择运营商，在 `GXNU-YC` 上认证。账号可选择仅用于本次会话，或通过 Android Keystore 加密保存在本机。

首页、服务、我的与浅深色主题均可离线使用。首期接入校园网；课表、校历、校园卡和图书馆按独立服务模块逐步扩展。平台顺序是 Android、Windows、iPhone，后两个平台在后续实施。

## 在电脑上打开

本机已有模拟器程序及 Android 系统镜像，位于 `D:\Android\sdk`；专用的 `gxnu_preview` 虚拟设备数据位于 `D:\Android\avd`。原有 ARM 虚拟设备不适合本机 Intel CPU，预览使用 Android 14 / x86_64。

在项目目录执行：

也可以直接双击 `scripts/open-campus-preview.cmd`，打开已构建的界面预览。后续更新由预览脚本自动安装，无需每次传 APK。

```powershell
# 构建、更新安装并打开实际 Android App。
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\preview-android.ps1

# 明确标注的界面预览：点击主按钮可查看连接过程和成功状态。
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\preview-android.ps1 -Preview

# 已有 APK 时直接打开，无需重建。
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\preview-android.ps1 -Preview -NoBuild
```

预览脚本检测指定设备，自动启动模拟器窗口并用 `adb install -r` 更新，保存的设置随更新保留。可用 `-State ONLINE`、`AUTH_ERROR`、`NO_WIFI` 等查看不同界面。`-Preview` 仅在 Debug APK 生效，使用独立内存控制器，不初始化凭据存储或校园认证模块；界面始终显示预览标识。

APK 输出：`app/build/outputs/apk/release/app-release.apk`，本轮版本 0.6.0 / versionCode 12，可覆盖安装。正式分发走 GitHub Releases，见下节。

## 更新与分发

应用启动时检查 GitHub Releases：发现新版本后**自动下载**，下载完在首页和「我的」显示「新版本已就绪」，点一次「安装」后由系统确认即完成。用户需要做的只剩系统那一次确认——Android 不允许应用静默安装自己，这一步无法省略。

不想现在更新可以点「取消」停掉本次下载；点「✕ 暂不提示」则这个版本不再显示、也不会自动下载。「我的 → 检查更新」可随时手动检查，手动检查会清除之前的取消记录。

自动检查每个进程只跑一次，请求量在未认证限额内。

发布新版本：

```powershell
git tag v0.6.0
git push origin v0.6.0
```

推送 `v*` 标签后 [发布工作流](.github/workflows/android-release.yml) 在 runner 上构建签名 APK，并发布 `shida-suixing-<版本>.apk` 与 `version.json`。应用读取 `version.json` 的 `versionCode` 判断是否需要更新。

当前已发布：`v0.4.0`（versionCode 7）、`v0.4.1`（versionCode 8）、`v0.4.2`（versionCode 9）。`0.5.0`（10）、`0.5.1`（11）与 `0.6.0`（12）尚未打标签发布。

签名密钥在仓库之外（`D:\gxsf-signing\release.jks`），通过仓库 Secrets 提供给 CI，不进入版本库。**请另行备份该密钥和口令**：丢失后已安装的旧版本无法再被覆盖更新。

更新链路已在真实发布上端到端验证过（0.4.0 → 发现 0.4.1 → 下载 → 授权 → 系统安装 → 0.4.1 → 报已最新），下载的文件与发布资产 SHA-256 逐字节一致。实现与边界见[应用内更新](docs/design/app-update.md)。

## 课表

「服务 → 课表」有两条路，**导入教务系统导出的 Word 课表是主入口**（不需要相机、网络和 Key）：

- **导入 Word**：直接选教务系统导出的 `.doc` / `.docx`，读出课程名、教师、周次与节次跨度
- **拍照识别**：选图或拍照后由 AI 识图生成周课表
- 按周查看，设置开学日期后自动定位当前周；未设置时按第 1 周显示
- 单双周、上课周次范围都会参与筛选，互斥的课程不会同时出现
- 周课表网格按课程着色，周末与空节自动收起，点任意课程查看教师与地点
- **课表可以自己改**：课程详情进编辑面板，字段含课程名、教师、**上课地点**、星期、起止节次、起止周与单双周；管理卡片可手动新增或删除课程。教务系统打印的课表不写上课地点，补地点就靠这里
- 课表只存在本机，可重新导入、重新识别或删除

导入时**不会有第 0 节**：打印稿最上面那行「无节次」（学校给还没排时间的课留的行）整行不导入，被略过的门数会明说。

识别端点已内置进构建，正常使用无需填写 Key。Key 通过 `local.properties` 的 `vision.baseUrl` / `vision.apiKey` 在构建时注入，`local.properties` 已被忽略。Word 导入不联网、不用 Key。实现见[课表导入 Word](docs/design/word-import.md) 与[课表识别](docs/design/timetable-vision.md)。

## 在手机使用

1. 打开“校园账号”，填写学校认证页平时使用的账号和密码，选择是否保存。
2. 在首页选择校园网、中国电信、中国联通、中国移动或广电网络。
3. 连接 `GXNU-YC`，授予识别 Wi-Fi 所需权限并开启系统定位，一次点击“连接校园网”。
4. 保存账号并开启自动连接后，应用通过持续通知的前台服务监听校园 Wi-Fi；通知可暂停自动连接。

手机系统强制停止、重启或撤销权限后，重新打开 App 恢复监听。关闭自动连接或删除账号会停止自动认证。认证失败不会无限重试，遇到学校需要人工操作时可打开官方认证页。仅当同一个校园 Wi-Fi 通过外网验证，应用才显示在线。

连接中显示当前阶段、已等待秒数与取消入口；结果常驻在首页，并通过全局通知提示。失败后可重试、检查账号或打开学校认证页。「我的 → 网络诊断」提供版本、Android 版本、脱敏状态和阶段耗时，便于定位真机问题。0.3.0 在 0.2.3 内嵌登录页的基础上补全返回流程：页面打开时按同一 Wi-Fi 轮询外网验证，认证成功后显示「认证成功，校园网已可上网」并自动返回首页，也可点「完成」立即返回；失败或超时保留手动登录，关闭页面即销毁网页与资源。原网页方案见 [认证实现](docs/design/official-portal-bridge.md)，此前修复记录见 [真机排错](docs/design/real-device-repair.md)。

## 开发与验证

需要 JDK 17、Android SDK 34。脚本为本机设置进程环境，保持系统 `JAVA_HOME` 设置；可通过参数指定其他 SDK/JDK 路径。Gradle Wrapper 提供工程构建。

```powershell
powershell -NoProfile -File .\scripts\check-android.ps1 -Task testDebugUnitTest
powershell -NoProfile -File .\scripts\check-android.ps1 -Task assembleDebug
powershell -NoProfile -File .\scripts\check-android.ps1 -Task lintDebug
powershell -NoProfile -File .\scripts\check-android.ps1 -Task assembleRelease
```

当前 438 个单元测试通过，`lintDebug` 无错误。发布构建需要 `local.properties` 里的 `signing.*`；缺失时回退到 debug 签名，产物不可分发。CI 的签名材料来自仓库 Secrets。

协议和协调器测试使用虚构数据，覆盖运营商、编码、JSONP 数据解析、重复操作、换网与账号变更、失败状态。账号密码不进入日志或源码，界面不拼接带凭据的 URL。官方适配器通过 `Network.openConnection` 绑定目标 Wi-Fi，保留系统 TLS 证书校验。

0.4.0 重做视觉：软灰画布 + 白色浮起卡片、单一蓝色主操作、1px 描边分隔、8/12/16 圆角阶梯、文字三级层次，底部导航保留手机手感；浅色与深色两套配色齐备，深色为炭灰画布而非纯黑。设置行式列表用描边图标配柔和底板，状态以小胶囊呈现。新版界面见[界面说明](docs/design/ui-v5-notes.md)，旧版记录见[调整说明](docs/design/ui-v4-notes.md)，字体来源和许可见[字体记录](docs/design/fonts/README.md)。Mobbin 与 Uiverse 的参考和后续路线见[校园 App 设计](docs/campus-app-design.md)。完整需求：[校园网规格](docs/comet/changes/gxnu-campus-connect/specs/campus-network/spec.md)、[应用外壳规格](docs/comet/changes/gxnu-campus-connect/specs/campus-shell/spec.md)。静态草案和 App 实际截图各自标注来源。

0.4.1 修的是几个会丢状态或说错话的地方：**旋转手机不再丢失内嵌登录页**（此前旋转会重建 Activity，把半填的表单和验证码一起清掉；现在由 `configChanges` 保住页面，`density`/`fontScale`/`locale` 仍按设计重建）；**学校认证页有了真实的加载态和错误态**（原来是一个 1.2 秒假计时器，打不开的页面会以空白的 502 文档交给 WebView，既不是网络错误也不会触发 `onReceivedError`，所以只接回调会把空白页当成就绪）；服务页在 Wi-Fi 设置读完前不再断言「未连接」；「我的 → 网络与通知权限」显示已授予/未授予；账号页每次按键的 4 个 `OutlinedTextField` 参数改为 `remember`。

0.5.0 修的是课表识别。真正的原因有两个，都不是提示词写得不好：一是**默认模型在真实密表图上永远答不出来**——它会把 `max_tokens` 全部烧在推理里，返回 `finish_reason=length`、`content` 为空，而且调低 `reasoning_effort`、调高 `max_tokens`（会撞上服务端 60 秒网关超时）、改成流式都救不回来；换成 `glm-5v-turbo` 后同一张图 11 秒出结果、零推理 token，`deepseek-v4.1-flash` 降为没得到结论时的第二选择。二是**图片被压得太小**：同一套提示词下，同一张真图在原分辨率是满分，压到 1024px 掉到 68%、850px 掉到 48%，所以上传目标边长从 2048 提到 4096，PNG 截图改走无损直传。提示词改成先朗读星期列与节次行、再抽取课程，并按广西师大自己的行标签解读节次；实测同一模型从 83% 提到 98%。同时修掉「识别中去首页再回来点不了课表」：忙碌状态现在有可见的「取消」按钮和 90 秒看门狗兜底，且只 disable 会发起新识别的动作。课表本身支持 13 节次与 7 天（含星期六日）。

0.5.1 按用户要求去掉了「无节次」这个自造概念，并让识别结果可以手动修正。**课表里不再有节次 0**：落在无节次行的课从第 1 节开始，如果那一天的第 1 节已经被占用，就放到当天第一个空闲节次——这样它永远不会和别的课冲突到让整张课表报废，而用户仍然看得到这门课并可以手动挪位置。**识别结果现在可编辑**：课程详情里可进入编辑面板，字段含课程名、教师、上课地点、星期、起止节次、起止周与单双周，管理卡片可手动新增一门课；保存走同一条校验，冲突与越界原样报错且不动已有课表。另修四类真缺陷：`Error`（如内存不足）穿透导致页面永远停在「识别中」（现在除取消外任何异常都会收敛成一次失败）；长边超过 8192px 的截图原本会把原始字节直传而被端点拒绝；回退模型原本被 90 秒看门狗饿死（现在每次尝试各有 40 秒上限）；模型把节次读成 0 或「无节次」原本会报废整张课表。

0.6.0 加了课表导入并把它做成默认入口。**课表可以直接读教务系统导出的 Word 文件**，不用拍照、不用网络、不用 Key：`.doc`（Word 97-2003 二进制）与 `.docx`（OOXML）都支持，两条路径产出同一份表格结构，跨实现一致性有断言钉住。读取器是手写的 —— `.docx` 走 `ZipInputStream` + JDK XML 解析（关掉 DOCTYPE 与外部实体防 XXE），`.doc` 走 CFB/OLE2 → FIB → 分片表还原 UTF-16 正文，行边界与**纵向合并**取自 `Data` 流里的 `sprmTDefTable`（TC 的 `fVertMerge`/`fVertRestart` 位），所以一门课跨几个节次是读出来的、不是猜的。导入结果与识别结果走**同一条** `TimetableValidator` 校验，没有第二套规则。**「无节次」行整行不导入**（打印稿最上面那一行是学校给没排时间的课留的），被略过的门数会在提示里说明 —— 课表里不再有第 0 节。「上课地点」在课程编辑面板里，教务系统打印的课表不写地点，补它就是导入之后的常规动作。

课表识别走内置端点，默认模型 `glm-5v-turbo`，可在课表页的高级设置里改端点、模型或填自己的 Key。只有课表图片会发往识别服务，校园网账号密码不参与。识别结果依赖模型输出；正确性以实际课表为准。

新版实际画面及操作记录：[模拟器检查](docs/design/emulator-verification.md)、[当前浅色首页](docs/design/screenshots/v5-home-light.png)。更新链路的实现与边界见[应用内更新](docs/design/app-update.md)，课表导入的实现、真值与已知边界见[课表导入 Word](docs/design/word-import.md)，课表识别（含分辨率与模型选择的实测数据）见[课表识别](docs/design/timetable-vision.md)。真实校园网登录与手机后台重连尚未完成实测，预览结果仅用于界面检查。更新流程的下载与系统安装确认需在实体手机验证。
