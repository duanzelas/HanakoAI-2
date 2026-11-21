import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":llm-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Compose Desktop 的主线程调度器（rememberCoroutineScope 依赖它）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // 全局热键（Compose Desktop 没有内置的全局快捷键）
    implementation("com.github.kwhat:jnativehook:2.2.2")
    // 防录屏/截屏：SetWindowDisplayAffinity 是 Win32 API，需要 JNA 拿窗口句柄并调用
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

compose.desktop {
    application {
        mainClass = "fun.kirari.hanako.pc.MainKt"
        nativeDistributions {
            // 默认只出 Exe（app-image，不需要 WiX）。
            // 需要 MSI 时加 -PwithMsi，此时会用到 WiX；本机代理对 WiX 下载源做 TLS 中间人，
            // 已手动把 wix311.zip 放到 ~/.gradle/compose-jb/ 绕开。
            val formats = if (providers.gradleProperty("withMsi").isPresent) {
                arrayOf(TargetFormat.Msi, TargetFormat.Exe)
            } else {
                arrayOf(TargetFormat.Exe)
            }
            targetFormats(*formats)
            packageName = "HanakoAI-EX"
            packageVersion = "0.0.32"
            // jpackage 在中文 Windows 上会用系统 GBK 解码这些字段，
            // 非 ASCII 会抛 MalformedInputException("Input length = 1")，这里必须纯英文。
            description = "HanakoAI-EX PC - desktop AI assistant (preview)"
            vendor = "duanzelas"
        }
    }
}
