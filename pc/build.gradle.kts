// pc/ 是独立构建，不参与 Android 端的 settings.gradle.kts，
// 因此 Compose Desktop 相关依赖不会污染 app 模块。
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}

tasks.register("printVersions") {
    doLast {
        println("Kotlin 2.0.21 / Compose Multiplatform 1.7.3")
    }
}
