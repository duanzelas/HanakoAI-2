# Hanako PC（预览）

桌面端是独立构建，**不参与**根目录的 Android 构建（`settings.gradle.kts` 里没有 `include(":pc")`），
所以引入 Compose Desktop 不会给 APK 带进任何多余依赖。

## 当前状态

已完成骨架，可编译、可运行：

- 配置模型（5 种 provider：OpenAI 兼容 / OpenAI Responses / Anthropic / Google / The Kirari Network）
- 输入问题 → 流式输出回答
- 配置持久化到 `~/.hanako-pc/config.properties`（明文，仅本机）

还没有做的：截图/OCR、自动模式、悬浮窗、历史记录、Markdown/LaTeX 渲染。

## 目录结构

```
pc/
├── settings.gradle.kts        独立构建入口（含 :llm-core 与 :app）
├── build.gradle.kts           插件版本集中声明
├── llm-core/                  JVM 模块，直接编译 ../../kirari-llm-core 的源码
└── app/                       Compose Desktop 应用
    └── src/main/kotlin/fun/kirari/hanako/pc/Main.kt
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

## 运行

```bash
cd pc
./gradlew :app:run                      # 启动窗口
./gradlew :app:run --args="--smoke"     # 无界面自检，验证依赖与 JVM 正常
```

首次运行会下载 Compose Desktop 与 skiko（约 200MB），之后走本地缓存。

## 打包

```bash
cd pc
./gradlew :app:packageMsi     # 生成 MSI 安装包
./gradlew :app:createDistributable   # 只生成免安装目录
```

MSI 需要本机有 WiX Toolset（jpackage 的 Windows 打包依赖）。

## 环境要求

- JDK 17（`jvmToolchain(17)`，Gradle 会用工具链自动定位）
- Gradle 8.13（用自带的 wrapper 即可）

> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 指向腾讯云镜像，
> 因为本机代理会拦截 `services.gradle.org` 的 TLS 握手（证书链校验失败）。
> 境外网络环境可以改回官方地址：`https://services.gradle.org/distributions/gradle-8.13-bin.zip`。
