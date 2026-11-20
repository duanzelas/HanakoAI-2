# HanakoAI-EX PC（预览）

桌面端是独立构建，**不参与**根目录的 Android 构建（`settings.gradle.kts` 里没有 `include(":pc")`），
所以引入 Compose Desktop 不会给 APK 带进任何多余依赖。

## 当前状态

可以编译、可以运行，功能对齐 Android 主线（0.0.28）的核心能力：

- **托盘常驻**：关闭窗口缩到托盘；托盘图标直接画出答案字母（`A` / `AB` / `ABD`），鼠标悬停可看完整答案摘要
- **全局热键**：`Ctrl+Alt+S` 截图作答、`Ctrl+Alt+R` 重新框选、`Ctrl+Alt+V` 开始/结束录音
- **框选一次、位置固定**：选区坐标存进配置，之后每次截图都用同一个矩形，不会再弹框
- **三条处理链路**：多模态直答、先 OCR 再作答、**语音+画面**（录音转写 + 当前截图一起交给模型）
- **语音转录**：麦克风录 16 kHz 单声道 PCM，超过 110 秒自动切片，转写走 DeepSeek 官方 ASR（`/v1/tools/asr`）；
  录到静音或转写失败会自动降级成纯截图作答，不会把整次作答废掉
- **历史记录**：每次作答都落到 `~/.hanako-pc/history.json`，右侧「历史」页可回看、复制、删除、清空
- **答案版本**：同一张图可以「重新生成」，用 `<` `>` 在多个答案之间翻，不会互相覆盖
- **联网搜索**：可选 Tavily / Brave / Serper，作答前把题干检索一遍，结果作为参考资料拼进提示词
- **模型预设**：DeepSeek / OpenAI / Anthropic / Gemini 一键填好 Base URL 与模型名
- **Markdown 渲染**：标题、列表、代码块、粗体、行内代码；公式原样显示（桌面端不做 LaTeX 排版）
- **剪贴板取图**：托盘菜单项或界面按钮，支持剪贴板位图与图片文件
- **答案浮窗**：右下角浮出字母小球，点击复制完整答案，15 秒后自动消失
- 配置持久化到 `~/.hanako-pc/config.properties`（明文，仅本机）
- 配置面板底部有「关于」：显示当前版本号，点仓库地址用浏览器打开
  `https://github.com/duanzelas/HanakoAI-EX`

还没做：多悬浮窗（桌面端没有系统级悬浮层，只有答案浮窗）、LaTeX 排版渲染、多显示器下按屏幕分别框选。

## 用法

1. 顶部点一个预设（如 **DeepSeek**），或自己填 Base URL / API Key / 视觉模型与文本模型
2. 选处理方式：`多模态` / `OCR→LLM` / `语音+画面`
3. 按 `Ctrl+Alt+R`（或点「重新框选」），拖动框住题目区域，松手即固定
4. 之后按 `Ctrl+Alt+S`（或点托盘菜单「按固定位置截图」）直接出答案
5. **语音+画面**：按 `Ctrl+Alt+V` 开始录音，再按一次结束 → 转写 → 截当前固定区域 → 一起作答；
   也可以直接点左侧红色「● 录音作答」按钮
6. 选择题答案会同时出现在：托盘图标、右下角浮窗、主窗口
7. 点浮窗即可复制完整答案；右侧「历史」页可回看之前的每一次作答

### 语音设置

| 字段 | 说明 |
|---|---|
| 转写地址 | 默认 `https://api.deepseek.com/v1`，路径 `/tools/asr` |
| 转写 API Key | 留空则复用主 API Key（DeepSeek 的 chat 与 ASR 是同一个 Key） |
| 录音上限 | 默认 110 秒（ASR 单次 120 秒上限留余量），到点自动结束并作答 |

桌面端只有麦克风一条音源：Java Sound 拿不到 WASAPI loopback，系统内录在 PC 上做不到。

## 目录结构

```
pc/
├── settings.gradle.kts        独立构建入口（含 :llm-core 与 :app）
├── build.gradle.kts           插件版本集中声明
├── llm-core/                  JVM 模块，直接编译 ../../kirari-llm-core 的源码
└── app/src/main/kotlin/fun/kirari/hanako/pc/
    ├── Main.kt                入口、主窗口（配置 / 结果 / 历史）、答案浮窗
    ├── AppState.kt            状态与动作（托盘/热键/界面共用同一套）
    ├── PcSettings.kt          配置模型与持久化（预设、三模式、转写、搜索）
    ├── Solver.kt              三条处理链路 + 搜索上下文注入，复用 kirari-llm-core
    ├── PcAudio.kt             麦克风录音（javax.sound）与 PCM→WAV 封装
    ├── PcTranscriber.kt       转写，复用 llm-core 的 TranscriptionClient
    ├── PcSearch.kt            Tavily / Brave / Serper 搜索
    ├── PcHistory.kt           历史记录（JSON 文件读写）
    ├── MarkdownText.kt        轻量 Markdown 渲染（无第三方依赖）
    ├── PcInfo.kt              版本号与仓库地址
    ├── AnswerParser.kt        从回答里抽 A-H 选项字母
    ├── Capture.kt             Robot 截图 / 剪贴板取图 / JPEG 编码
    ├── RegionSelector.kt      全屏框选遮罩（AWT，像素坐标严格对齐）
    ├── TrayController.kt      AWT 托盘，图标动态绘制字母
    └── HotkeyController.kt    全局热键（jnativehook）
```

### 为什么 llm-core 不是复制粘贴

`kirari-llm-core` 虽然是 Android library 打包，但 11 个文件**没有任何 `android.*` 引用**，
只依赖 OkHttp / kotlinx-serialization / coroutines，本质是纯 JVM 代码。
`pc/llm-core/build.gradle.kts` 里用一行把它接进 JVM 编译：

```kotlin
sourceSets.named("main") { java.srcDir("../../kirari-llm-core/src/main/java") }
```

好处是四家 provider 的 SSE 解析、工具调用循环、多模态消息拼装都不用重写，
也不会出现"移动端修了 bug、桌面端还是旧的"这种分叉。Android 端构建完全不受影响。

### 为什么框选遮罩用 AWT 而不是 Compose

`Robot.createScreenCapture` 接受的是物理像素坐标。Compose 窗口的坐标是 dp，
高 DPI（125% / 150%）下需要来回换算，容易差几个像素导致截歪。
AWT `JWindow` 直接铺在 `screenBounds` 上，鼠标坐标加屏幕原点就是截图的真实坐标，没有换算误差。

## 运行

```bash
cd pc
./gradlew :app:run                      # 启动
./gradlew :app:run --args="--smoke"     # 无界面自检：版本、预设、路由、解析器、麦克风、热键、托盘
```

首次运行会下载 Compose Desktop 与 skiko（约 200MB），之后走本地缓存。

## 打包

```bash
cd pc
./gradlew :app:createDistributable       # 免安装 app-image（不需要 WiX）
./gradlew :app:packageMsi -PwithMsi      # MSI 安装包（需要 WiX）
```

产物：

| 路径 | 大小 | 说明 |
|---|---|---|
| `app/build/compose/binaries/main/app/HanakoAI-EX/HanakoAI-EX.exe` | 整目录 115MB | 双击即用，内含精简 JRE，目标机不用装 Java |
| `app/build/compose/binaries/main/msi/HanakoAI-EX-0.0.29.msi` | 58MB | 安装包，装完有开始菜单项 |

版本号在两处，必须一起改：`PcInfo.VERSION`（窗口标题与「关于」显示）和
`pc/app/build.gradle.kts` 的 `packageVersion`（安装包版本）。

两个打包坑（都已在构建脚本里处理）：

- **jpackage 在中文 Windows 上按 GBK 解码** `packageName` / `description` / `vendor`，字段里出现中文会抛
  `MalformedInputException: Input length = 1`，整个打包失败。所以 `nativeDistributions` 里这几个字段必须纯英文。
- **WiX 需要预置在 `~/.gradle/compose-jb/wix311.zip`**。Compose 插件用 Java 的 SSLContext 下载它，
  本机代理做 TLS 中间人时证书链校验失败；用系统 `curl`（走系统证书库）分片下载再放进去即可。
  本机就是这么补的：GitHub Release 单连接只有 16KB/s，改成 20 段并行 Range 下载，2 分钟拉完 34.6MB。

## 已知限制

- 框选只在主屏（`screenBounds` 取的是虚拟桌面并集，但选区按绝对坐标记录，跨屏拖动可能截不全）
- 托盘图标只有 64×64，四个字母时字号会缩到 18px，细节靠 tooltip 和浮窗补
- `jnativehook` 需要注入键盘钩子，个别杀软会拦截；注册失败时界面会显示「注册失败」，功能退化为只用托盘和按钮
- 配置是明文 Properties，API Key 直接落盘，不要在多用户共用机器上使用

## 环境要求

- JDK 17（`jvmToolchain(17)`，Gradle 会用工具链自动定位）
- Gradle 8.13（用自带的 wrapper 即可）

> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 指向腾讯云镜像，
> 因为本机代理会拦截 `services.gradle.org` 的 TLS 握手（证书链校验失败）。
> 境外网络环境可以改回官方地址：`https://services.gradle.org/distributions/gradle-8.13-bin.zip`。
