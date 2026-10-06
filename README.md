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

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`，本轮版本 0.3.0 / versionCode 6，可覆盖安装。交付副本在检查通过后更新为 `D:\gxsf-delivery\师大随行-0.3.0.apk`。实体手机安装此 APK 后按应用内步骤设置。模拟器使用虚拟 Wi-Fi，可检查真实页面与本地保存，学校 Wi-Fi 的实际认证和后台行为需在实体 Android 手机验证。

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
```

协议和协调器测试使用虚构数据，覆盖运营商、编码、JSONP 数据解析、重复操作、换网与账号变更、失败状态。账号密码不进入日志或源码，界面不拼接带凭据的 URL。官方适配器通过 `Network.openConnection` 绑定目标 Wi-Fi，保留系统 TLS 证书校验。

0.3.0 重做视觉：软灰画布 + 白色浮起卡片、单一蓝色主操作、1px 描边分隔、8/12/16 圆角阶梯、文字三级层次，底部导航保留手机手感；浅色与深色两套配色齐备，深色为炭灰画布而非纯黑。设置行式列表用描边图标配柔和底板，状态以小胶囊呈现。新版界面见[界面说明](docs/design/ui-v5-notes.md)，旧版记录见[调整说明](docs/design/ui-v4-notes.md)，字体来源和许可见[字体记录](docs/design/fonts/README.md)。Mobbin 与 Uiverse 的参考和后续路线见[校园 App 设计](docs/campus-app-design.md)。完整需求：[校园网规格](docs/comet/changes/gxnu-campus-connect/specs/campus-network/spec.md)、[应用外壳规格](docs/comet/changes/gxnu-campus-connect/specs/campus-shell/spec.md)。静态草案和 App 实际截图各自标注来源。

课表服务在「服务」页进入：选择或拍摄课表照片后交给 DeepSeek `deepseek-flash` 识图，返回结构化课程并渲染成周课表，识别结果保存在本机。API Key 由用户在课表页填写，用 Android Keystore 加密保存在本机，不写进源码也不打进 APK；只有课表图片会发往识别服务，校园网账号密码不参与。见[课表识别](docs/design/timetable-vision.md)。识别结果依赖模型输出，未用真实 Key 端到端验证过。

新版实际画面及操作记录：[模拟器检查](docs/design/emulator-verification.md)、[当前浅色首页](docs/design/screenshots/v5-home-light.png)。真实校园网登录与手机后台重连尚未完成实测，预览结果仅用于界面检查。
