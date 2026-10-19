import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 工具链 21、字节码 17:
// - 21 是 Robolectric 跑 SDK 36 sandbox 的硬要求(`Failed to create a Robolectric
//   sandbox: Android SDK 36 requires Java 21`),而 app 的单测要加载 domain 的类,
//   故两边必须用同一个工具链;项目 `.mise.toml` 因此由 temurin-17 提到 temurin-21。
// - 字节码仍压到 17 是刻意的:app 侧 `compileOptions` 也是 17,D8 吃 17 是最稳的
//   已证路径;把 domain 的 class 文件版本抬到 65 只会徒增构建链风险,没有收益
//   (domain 是纯 Kotlin JVM 模块,用不到任何 Java 21 语言特性)。
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
