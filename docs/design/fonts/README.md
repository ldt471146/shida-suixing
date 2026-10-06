当前界面使用 Android 系统 `SansSerif`，由系统统一处理中英文与其他文字的回退。正文使用 400，标题和主操作最多使用 500，按系统字号设置缩放；字体不依赖网络。网络名和普通文字共用同一套样式，避免拉丁大标题与中文混排失衡。

以下是第二版打包字体的来源和许可记录。2026-10-06 收到字体反馈后，未再引用的资源已移到 [v2-assets](v2-assets/) 留档，不再打包进 App。保留生成脚本、校验值和许可供历史追溯；重新运行旧生成脚本会重新加入资源，当前版本无需执行。

第二版离线字体来自 Google Fonts 官方仓库，下载日期为 2026-10-06。以下文件由官方可变 TTF 固定字重生成。

| 第二版 Android 字体资源 | 原始字体 | 字重 | 文件字节数 |
| --- | --- | --- | ---: |
| `campus_sans_regular` | Noto Sans SC | 400 | 2,448,744 |
| `campus_sans_semibold` | Noto Sans SC | 600 | 2,446,848 |
| `campus_latin_medium` | Plus Jakarta Sans | 500 | 105,048 |
| `campus_latin_bold` | Plus Jakarta Sans | 700 | 104,956 |

四个资源合计 5,105,596 字节，约 4.87 MiB。中文字体包含全部 7,445 个 GB2312 字符、可打印 ASCII、中文标点、全角标点与必要的排版符号，各保留 7,607 个 Unicode 字符。拉丁字体保留原文件的 721 个字符。GSUB/GPOS 排版功能和字形变化被保留；打包文件不含可变字重轴，适用于 Android 8.0 及以上。

未包含的生僻汉字、其他文字和 Emoji 由 Android 系统字体自动回退，不会在线下载；其外观和覆盖范围取决于设备系统字体。界面可分别使用中文 400/600 与拉丁标题 500/700，避免把拉丁字体作为中文正文的唯一字体。

官方来源为 [Noto Sans SC 字体目录](https://github.com/google/fonts/tree/main/ofl/notosanssc) 与 [Plus Jakarta Sans 字体目录](https://github.com/google/fonts/tree/main/ofl/plusjakartasans)。实际下载地址为：

- [NotoSansSC\[wght\].ttf](https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf)，Git blob `fb0637bafbcd804fe32152370a1225990745b4bc`。
- [PlusJakartaSans\[wght\].ttf](https://raw.githubusercontent.com/google/fonts/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf)，Git blob `0cb13a998ed525ba226d911b10d6c4c4f923a961`。

Noto Sans SC 的版权为 © 2014–2021 Adobe，官方许可注明保留字体名 `Source`。Plus Jakarta Sans 的版权为 © 2020 The Plus Jakarta Sans Project Authors，原项目位于 [tokotype/PlusJakartaSans](https://github.com/tokotype/PlusJakartaSans)。两者均采用 SIL Open Font License 1.1，允许商业应用嵌入及随应用分发。完整官方许可保存在 [NotoSansSC-OFL.txt](NotoSansSC-OFL.txt) 与 [PlusJakartaSans-OFL.txt](PlusJakartaSans-OFL.txt)。

本项目的固定字重与中文子集属于修改版本，内部家族名为 `Campus Sans SC` 与 `Campus Latin`。原版权与必要元数据仍保留，完整 OFL 已嵌入各字体的 OpenType `name` 表第 13 项，因此 APK 内的字体也携带许可文本。修改版本继续采用 OFL 1.1。

生成工具为 fontTools 4.62.1，过程见 [generate-fonts.py](generate-fonts.py)。源文件保存在本机 D 盘的 `.local/fonts/source/`，不计入产品源码。重现时先核对 [fonts.json](fonts.json) 里的原始文件 SHA-256，再运行：

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:TEMP = 'D:/gxsf/.local/fonts/tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
python docs/design/fonts/generate-fonts.py
```

`fonts.json` 登记原文件及成品的 SHA-256、字重、字符数、文件尺寸与来源。生成时会检查全部 GB2312 字符覆盖、字重及静态字体结构。
