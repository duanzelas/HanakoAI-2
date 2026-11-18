// 直接把 Android 端的 kirari-llm-core 源码接进来编译。
// 那份代码虽然是 Android library 打包，但 11 个文件没有任何 android.* 引用，
// 只依赖 OkHttp / kotlinx-serialization / coroutines，PC 端可以原样复用，
// 避免四家 provider 的 SSE 解析被重写一遍（也不会和移动端产生分叉）。
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    named("main") {
        java.srcDir("../../kirari-llm-core/src/main/java")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnit()
}
