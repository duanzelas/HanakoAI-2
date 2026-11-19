# HanakoAI-EX

Android 悬浮窗 AI 客户端，把「截屏 → 识题 → 解题 → 复制/填写」压缩到最短链路。
支持纯视觉、OCR、**语音+画面**三种工作模式，以及多悬浮窗、自动模式、Shizuku 安全层截图等能力。

> **HanakoAI-EX** 是 [zyf2007/HanakoAI](https://github.com/zyf2007/HanakoAI) 的衍生分支，
> 在上游基础上做了较多面向实际刷题场景的改动，详见 [与上游的差异](#与上游的差异)。

[[下载 v0.0.28](https://github.com/duanzelas/HanakoAI-EX/releases/tag/v0.0.28)] [[更新日志](https://github.com/duanzelas/HanakoAI-EX/releases)] [[上游项目](https://github.com/zyf2007/HanakoAI)]

---

## 目录

- [工作模式](#工作模式)
- [主要功能](#主要功能)
- [下载](#下载)
- [使用方式](#使用方式)
- [悬浮球手势](#悬浮球手势)
- [权限与截图方式](#权限与截图方式)
- [设置说明](#设置说明)
- [本地构建](#本地构建)
- [技术实现概览](#技术实现概览)
- [与上游的差异](#与上游的差异)
- [已知限制](#已知限制)
- [致谢与许可](#致谢与许可)

---

## 工作模式

在首页顶部的模式选择器里切换。三种模式共用同一套悬浮球与结果面板，只是「喂给模型的东西」不同：

| 模式 | 输入 | 适用场景 |
|---|---|---|
| **纯视觉**（`MULTIMODAL_DIRECT`） | 截图直接交多模态模型 | 含图、含公式、排版复杂的题目 |
| **OCR → LLM**（`OCR_THEN_LLM`） | 先 OCR 取文字，再交文本模型 | 纯文字题，可以用更便宜/更快的文本模型 |
| **语音+画面**（`AUDIO_AND_SCREEN`） | 一段录音转写 + 当前截图 | 视频、直播、语音讲解里出题；屏幕上只有选项、题目靠听 |

**自动模式**是另一维度：主页**长按**启动按钮进入自动模式，作答**不弹面板** ——
选择题答案直接显示在悬浮球上（`ABD` 这样的胶囊），填空题/文本答案写入剪贴板。
语音+画面也支持自动模式：录完音直接出结果，不弹窗。

## 主要功能

### 悬浮球

- 常驻桌面，可拖动；单击 / 双击 / 长按三种手势 + 扇形快捷菜单。
- Android 12+ 默认在系统截屏、录屏中隐藏悬浮球、菜单与面板。
- **无障碍悬浮层**：微信收款码、银行、支付等页面会调用 `setHideOverlayWindows(true)`
  强制隐藏 `TYPE_APPLICATION_OVERLAY`，开启后改用 `TYPE_ACCESSIBILITY_OVERLAY` 显示，
  在这些页面依然可见（该服务只提供窗口令牌，不读取、不监听界面内容）。
- **多悬浮窗**：可同时放两颗球 —— 1 号球固定纯视觉、2 号球固定语音+画面，
  位置各自记住，外观各自设置。

### 语音+画面

- 两段式录制：点一下开录，再点一下结束并作答；长按/双击放弃；球上显示 `m:ss` 倒计时胶囊。
- 转写走 **DeepSeek 官方 ASR**（`/v1/tools/asr`），16 kHz 单声道 PCM WAV，超过 110 秒自动切片。
- 音源严格由入口决定：**主按钮恒为系统内录（AudioPlaybackCapture）**，麦克风只走菜单里的独立入口，
  不会在你没点麦克风的时候偷偷开麦。
- 内录缺投影会话时，弹一次系统「录屏与录音」授权，建立一路「只录音、不建虚拟屏」的会话，
  录音结束立即回收，不影响屏幕镜像。
- 语音只做增强：内录没声音、转写报错、转写为空都会降级成纯截图作答，原因写进结果与历史记录。

### 作答与记录

- 结果面板支持 Markdown / LaTeX 渲染、答案版本切换、`[copy:内容]` 一键复制片段。
- 历史记录可查看、删除、重新生成；语音记录里能直接看到转写原文。
- 联网搜索：模型按需调用 `web_search`（Tavily / Brave / Serper / 自定义），搜索结果注入上下文。
- OCR、文本、多模态、转写可分别指定不同的提供方与模型。

### 模型与登录

- 内置预设：**DeepSeek**（`deepseek-flash`）、OpenAI Compatible、OpenAI Responses、Anthropic、Google Gemini。
- 可选 The Kirari Network OIDC 登录与 LLM 网关；也可以完全用自己的 API Key。

## 下载

到 [Releases](https://github.com/duanzelas/HanakoAI-EX/releases) 取最新版本，四个包的差别：

| 版本 | ABI | 说明 |
|---|---|---|
| `app-lite-arm64-v8a-release.apk` | arm64-v8a | **推荐**，绝大多数手机 |
| `app-lite-armeabi-v7a-release.apk` | armeabi-v7a | 32 位设备 |
| `app-full-arm64-v8a-release.apk` | arm64-v8a | 内置 ML Kit 中文 OCR，体积大 10MB 左右 |
| `app-full-armeabi-v7a-release.apk` | armeabi-v7a | 32 位 + 本地 OCR |

Lite 与 Full 的唯一区别是**是否内置本地 OCR**。ML Kit 不支持 LaTeX 公式识别，
所以数学题还是建议走云端 OCR / 多模态模型 —— 没有特殊需求选 Lite 就行。

> 桌面名称从 v0.0.27 起显示为 **HanakoAI-EX**，包名仍沿用 `fun.kirari.hanako`，
> 所以可以直接覆盖安装上游或旧版本，不用卸载重装（数据与设置都保留）。

## 使用方式

### 普通模式（短按启动）

1. 首页**短按**「启动」，授予悬浮窗权限、按需完成截屏授权。
2. 点击悬浮球 → 截屏 → 框选题目区域。
3. 发送给 AI，在面板里看思路、答案，一键复制片段。

### 自动模式（长按启动）

1. 首页**长按**「启动」进入自动模式（首页按钮下方也有同样的提示）。
2. 点击悬浮球：视觉/OCR 模式下截整屏后直接作答；语音模式下先录音，再点一次结束。
3. 答案只落在悬浮球上或剪贴板，全程不弹面板；完成后可发系统通知。

### 语音+画面

1. 模式选择器切到「语音+画面」（或直接点 2 号球）。
2. 点击悬浮球开始录音，球体变成倒计时胶囊（内录蓝色 / 麦克风红色）。
3. 再点一次结束 → 转写 + 截图 → 作答。
4. 遇到禁止内录的应用（部分 App 声明 `allowAudioPlaybackCapture=false`），
   改用悬浮球菜单里的「麦克风」。

### 多悬浮窗

设置 → 更多 → **多悬浮窗** 打开后同时显示两颗球：

| 球 | 角色 | 单击 | 长按 | 双击 |
|---|---|---|---|---|
| 1 号（紫色 · 截图图标） | 纯视觉 | 截图 → 框选 → 作答 | 多页截图 | 扇形菜单 |
| 2 号（粉色 · 麦克风图标） | 语音+画面 | 开始/结束录音 | 放弃录音 | 扇形菜单 |

## 悬浮球手势

| 手势 | Idle / Copied / Error | Processing | 多页截图 | 菜单展开 |
|---|---|---|---|---|
| 单击 | 打开截屏面板 / 开始录音 | 打开截屏面板 | 截一页 | 关闭菜单 |
| 双击 | 展开扇形菜单 | 取消处理 | 退出多页 | 关闭菜单 |
| 长按 | 进入多页截图 | 展开扇形菜单 | 发送截图 | 关闭菜单 |
| 拖动 | 移动气泡 | 移动气泡 | 移动气泡 | — |

2 号球只有「录音」这一件事：单击开始/结束，长按放弃，双击开菜单。

### 扇形菜单

- **视觉**：在 OCR / 多模态之间切换，高亮表示当前是多模态。
- **联网**：开关联网搜索。
- **麦克风**：用麦克风录一段（唯一的开麦入口）。
- **设置**：打开主界面。

菜单会按气泡位置自动调整展开方向，可在「设置 → 更多 → 扇形菜单设置」关闭或调整。

### 多页截图

长按悬浮球进入，气泡上显示已截数量：单击加一页、长按一起发送、双击取消。

## 权限与截图方式

| 权限 | 用途 |
|---|---|
| 悬浮窗 | 显示悬浮球与结果面板 |
| 无障碍服务（可选） | 提供 `TYPE_ACCESSIBILITY_OVERLAY` 窗口令牌，让球在敏感页面不被隐藏 |
| 通知 | 自动模式完成提醒 |
| 录音 | 内录与麦克风都需要（`AudioPlaybackCapture` 也要 `RECORD_AUDIO`） |
| 前台服务 | 悬浮球常驻、麦克风/投影期间持有前台服务类型 |

截图后端在「设置 → 更多 → 屏幕录制方式」切换：

- **MediaProjection**：系统截屏授权，兼容性最好；同时为内录提供投影会话。
- **Shizuku ADB**：以 shell 身份直调 `WindowManager.captureDisplay` 并请求合成 secure 层，
  **可以截到受 `FLAG_SECURE` 保护的页面**（银行、支付、考试类）。失败或全黑自动回退 `screencap`。
  需要设备已运行并授权 Shizuku（建议 13.6.0+）。选它时内录会单独申请一次投影授权。

## 设置说明

| 分组 | 内容 |
|---|---|
| 模型提供方 | API Base URL / Key / 类型，连通性测试与模型列表 |
| 模型设置 | OCR、文本、多模态分别选提供方与模型，可收藏 |
| 联网搜索 | 开关、自动模式是否允许、引擎、Key、Tavily 额度 |
| 助手配置 | 名称与 OCR / 文本 / 多模态提示词 |
| 更多 → 多悬浮窗 | 是否同时显示 1、2 号球 |
| 更多 → 悬浮球外观 | 1/2 号球分别设置直径、加载动画直径、字母大小、**倒计时字号**、透明度、自定义配色（球体/字体/描边） |
| 更多 → 扇形菜单 | 开关与跟随尺寸 |
| 更多 → 语音转写 | 转写地址、Key、语种提示、仅英文 |
| 更多 → 自动模式 | 完成通知、静态模式、超时、手势说明 |
| 更多 → 屏幕录制方式 | MediaProjection / Shizuku |
| 更多 → GitHub 仓库 | 打开本项目仓库与当前版本号 |

## 本地构建

环境：

- JDK **17**（AGP 8.13 要求；源码级别为 Java 11）
- Android SDK **36**（`compileSdk = 36`，`minSdk = 24`，`targetSdk = 35`）
- Gradle 8.13（仓库自带 wrapper）

```bash
# 调试包（Lite）
./gradlew :app:assembleLiteDebug

# 发布包（各出两个 ABI）
./gradlew :app:assembleLiteRelease
./gradlew :app:assembleFullRelease

# 单元测试
./gradlew test
```

产物在 `app/build/outputs/apk/<flavor>/release/`。签名包需要项目根目录提供 `keystore.properties`：

```properties
storeFile=/absolute/path/to/your.jks
storePassword=******
keyAlias=******
keyPassword=******
```

### PC 端（预览，`hanako-PC` / 本分支 `pc/`）

桌面端是独立构建，放在 `pc/`，**不参与 APK 构建**，也不影响 Android 工程：

```bash
cd pc
./gradlew :app:run                      # 启动（托盘 + 全局热键 Ctrl+Alt+S / Ctrl+Alt+R）
./gradlew :app:run --args="--smoke"     # 无界面自检
./gradlew :app:createDistributable      # 免安装 app-image（不需要 WiX）
./gradlew :app:packageMsi -PwithMsi     # MSI 安装包（需要 WiX）
```

它直接复用 `kirari-llm-core` 的源码（该模块没有任何 `android.*` 依赖），各 provider 的适配逻辑两端共用同一份代码。
首次运行会下载 Compose Desktop 与 skiko（约 200MB）。细节见 `pc/README.md`。

## 技术实现概览

- Kotlin + Jetpack Compose + Material 3
- DataStore 持久化配置与历史
- OkHttp + SSE 处理流式输出
- MediaProjection / Shizuku 双截图后端（含反射访问隐藏 API）
- `AudioPlaybackCapture` / `AudioRecord` + 前台服务完成内录与麦克风录制
- 前台服务承载悬浮窗（`TYPE_APPLICATION_OVERLAY` / `TYPE_ACCESSIBILITY_OVERLAY`）
- 工作流式作答链路：Capture → OCR → Answer / Automation 节点编排

主要目录：

```
app/src/main/java/fun/kirari/hanako/
├── app/            应用入口、导航
├── core/           数据模型、设置存储、音频、网络
├── feature/        home / overlay / history / settings
├── platform/       capture（projection / shizuku）
└── solve/          作答工作流（workflow / runtime / application）
kirari-llm-core/    多提供方 LLM 适配与工具调用格式
kirari-auth-core/   OIDC PKCE 登录
pc/                PC 端独立工程（Compose Desktop，不参与 APK 构建）
```

分支说明（**GitHub 默认分支为 `pe`**）：

- `pe`：当前 Android 客户端主线（本文档描述的内容，默认分支）
- `hanako-PC`：`pe` 的全部内容 + `pc/` 桌面工程（Compose Desktop，托盘 + 全局热键 + 固定选区截图）；
  每次发版后从 `pe` 合并一次，两端共用同一份 `kirari-llm-core`
- `main`：沿用上游基线

## 与上游的差异

相对 [zyf2007/HanakoAI](https://github.com/zyf2007/HanakoAI)：

**截图与悬浮窗**

- Shizuku 后端改为 shell 身份直调 WMS `captureDisplay` 请求合成 secure 层，可截 `FLAG_SECURE` 页面；
  兼容 Android 14 ~ 16.2 的 `ScreenCapture` / `ScreenCaptureInternal`、布尔与整型 secure 字段差异。
- 新增无障碍悬浮层，解决敏感页面隐藏悬浮球的问题。
- 新增多悬浮窗（双球双角色）、双球独立外观与自定义配色、倒计时字号。

**语音**

- 新增语音+画面模式与 DeepSeek ASR 转写、两段式录制、内录授权会话、语音自动模式。
- 修复「内录只录到麦克风声音」「倒计时结束只闪红叉不回答」「停止录音卡主线程」等问题。

**其他**

- 新增 DeepSeek 模型预设（默认 `deepseek-flash`）。
- 自动模式图标按模式区分（视觉星芒 / 语音麦克风）。
- 设置中加入本项目仓库入口与版本号。

## 已知限制

- **内录不是万能的**：目标应用声明 `allowAudioPlaybackCapture="false"`，或音频走
  `USAGE_VOICE_COMMUNICATION`（微信语音/通话）时，系统不允许被录制，只能改用麦克风。
- **Shizuku 截图依赖隐藏 API**，系统大版本升级后可能需要重新适配。
- 双球的位置在开关切换后会重建回默认位。
- 本仓库是个人学习与自用向的衍生项目，不保证与上游保持同步；遇到问题请优先对照上游行为。

## 致谢与许可

- 上游项目：[zyf2007/HanakoAI](https://github.com/zyf2007/HanakoAI)，本项目的绝大部分基础能力来自它。
- 本项目沿用上游许可：**GNU Affero General Public License v3.0**（见 [LICENSE](LICENSE)）。
  分发修改版时需要同样以 AGPL-3.0 开源，并提供完整对应源码。
- 请仅将本项目用于个人学习研究，遵守所在平台与相关法律法规的要求。
