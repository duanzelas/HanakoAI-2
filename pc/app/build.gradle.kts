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
}

compose.desktop {
    application {
        mainClass = "fun.kirari.hanako.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "HanakoPC"
            packageVersion = "0.0.20"
            description = "Hanako PC - 桌面端 AI 助手（预览）"
            vendor = "Kirari"
        }
    }
}
