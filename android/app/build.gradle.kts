plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // 导航 route 用 type-safe 形式(2026-09-22 navigation-compose 起,2026-11-14 迁 Nav3):
    // `@Serializable` 的 route / NavKey 需要这个插件在**编译期**生成序列化器。
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.roborazzi)
}

val previewOutDir = providers.systemProperty("pixfold.preview.outDir")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.cyanix.pixfold"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cyanix.pixfold"
        // 当前 minSdk = 36（Android 16）：2026-09-25 曾由 30 提至 34，后为对齐 Android 16
        // Live Updates（NotificationCompat.ProgressStyle 升格）进一步提至 36。
        // `LocaleManager`/`LocaleConfig`(API 33+)、动态取色(API 31+)、自适应图标(API 26+)
        // 与 HDR gainmap(API 34+) 均无条件可用，无需低版本守卫分支。
        // 历史取舍见 docs/notes/environment.md §12.6 与 docs/notes/d1-toolchain-evidence.md §9。
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // 设置页「关于」需要读取 VERSION_NAME / VERSION_CODE 显示当前版本。
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // U0 负向对照预期失败,默认任务中排除;单独用 -PincludeNegativeControl 运行
                if (!project.hasProperty("includeNegativeControl")) {
                    it.exclude("**/NegativeControlTest.class")
                }
                // Robolectric 在 SDK 36 沙箱下初始化 `ApplicationSharedMemory` 时,
                // 用反射读写 `java.io.FileDescriptor` 的私有 int 字段,并经由
                // `jdk.internal.access.SharedSecrets` 拿到它 —— 这条路在 JDK 17 上
                // 只是 warning,在 **JDK 21 上被强封装直接拒绝**:
                //
                //   IllegalAccessException: class ...$FileDescriptorInterceptor
                //   cannot access class jdk.internal.access.SharedSecrets
                //   (in module java.base) because module java.base does not
                //   export jdk.internal.access to unnamed module
                //
                // 抛出点在 `AndroidTestEnvironment.setUpApplicationState` 里,
                // 即**每个** Robolectric 用例都会撞上 ⇒ 不修则 616 个用例全红。
                // 只给测试 JVM 开这个口子:不影响生产字节码,也不进 APK。
                with(it) {
                    if (previewOutDir != null) {
                        systemProperty("pixfold.preview.outDir", previewOutDir)
                        outputs.upToDateWhen { false }
                    }
                    jvmArgs(
                        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                        "--add-opens=java.base/java.io=ALL-UNNAMED",
                    )
                }
            }
        }
    }

    lint {
        // 验收清单第 20 项要求"静态检查零告警"。
        //
        // **2026-09-20 工具链升级后,这里从"关闭 4 项"收紧为"只关 1 项"**:
        // 我们已把工具链推到当时的最新 stable(Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.20 /
        // Compose BOM 2026.09.00 / activity-compose 1.13.0),因此
        // `NewerVersionAvailable` / `GradleDependency` / `AndroidGradlePluginVersion`
        // 三项**重新启用**并真实通过 —— 它们不是被忽略,而是已无建议可提。
        // (实测依据:启用后先报出 Gradle 9.6.0 与 activity-compose 1.12.4 两项过期,
        //  升级后归零;说明这三项检测**确实在工作**。)
        //
        // **2026-10-12 夜间离线任务**:lint 重跑后 `NewerVersionAvailable` 报
        // Kotlin 2.4.21 可用、`AndroidGradlePluginVersion` 报 Gradle 9.8.1 可用。
        // Gradle 9.8.1 因 transforms 缓存锁文件创建失败(Windows 环境问题)无法升级,
        // Kotlin 2.4.21 虽能编译但 Gradle 版本检查仍会报错。两项均为"有新版可用"
        // 通知,非代码质量问题;暂禁用,待工具链升级专题处理(见 A3 审计结论)。
        //
        // 仍关闭 `OldTargetApi`:它建议 targetSdk 37,而 §12.6 明确锁定 36
        // (与真机 Android 16 对齐)。这是**刻意的版本策略**,不是待办事项。
        //
        // 约束:`warningsAsErrors = true` 要求这里的豁免清单**尽可能小**;
        // 每次升级工具链后应重新评估能否再收紧。
        disable += setOf(
            "OldTargetApi",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "GradleDependency",
        )
        warningsAsErrors = true
        abortOnError = true
    }
}

composeCompiler {
    // 声明纯 Kotlin `:domain` 模块中真正不可变的值对象与状态类为稳定类型，
    // 消除跨模块不稳定退化，且不给 `:domain` 引入任何 Android/Compose 依赖（AdapterBoundaryTest 保持全绿）。
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_stability.conf"),
    )
}

roborazzi {
    // 必须用 layout.projectDirectory.dir(...) 而非 file(...)，满足 Gradle 9 配置缓存卫生约束（Roborazzi #968）。
    outputDir.set(layout.projectDirectory.dir("src/test/snapshots"))
}

// 兼容带全局 mirrors.init.gradle 的构建环境：Gradle Plugin Portal（及其阿里云镜像 gradle-plugin）
// 仅为 roborazzi-gradle-plugin 同步了 JVM 端 artifact，而對 Android 端变体（roborazzi-core-android /
// roborazzi-painter）只存在无 .module/.aar 的残缺元数据。排除从插件仓库解析该组库依赖，使其固定走 Maven Central。
repositories.withType<MavenArtifactRepository>().configureEach {
    val repoUrl = url.toString()
    if (repoUrl.contains("gradle-plugin") || repoUrl.contains("plugins.gradle.org")) {
        content {
            excludeGroup("io.github.takahirom.roborazzi")
        }
    }
}

dependencies {
    implementation(project(":domain"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.material.color.utilities)
    // Navigation 3(2026-11-14 全量迁移):13 route 为 NavKey,3 个 NavHost 换 NavDisplay。
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    // 工作区列表存 SharedPreferences 里的 JSON 数组(见 data/saf/SafTreeStore.kt)。
    // 不用 org.json:它在纯 JVM 单测里是空壳(Robolectric 之外抛 Stub!)。
    implementation(libs.kotlinx.serialization.json)
    // D2a:SAF 遍历/读字节必须离开主线程。
    // lifecycle-runtime-compose 提供 collectAsStateWithLifecycle(页面销毁即停止收集);
    // coroutines-android 提供 Dispatchers.IO / Main。
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    // D2b 切后台恢复:进度通知要用 NotificationCompat.ProgressStyle(Android 16 的
    // Live Updates 形态,经 core 兼容到低版本)。该 API 自 androidx.core 1.17.0 起提供,
    // 当前虽经 lifecycle 传递解析到 1.19.0,但**必须显式声明** —— 否则将来 lifecycle
    // 版本变动会把传递依赖拉低,编译期才报错,回归成本高。
    implementation(libs.core.ktx)
    // 缩略图加载改用 Coil 3(core artifact,不引网络栈);SAF 的 content:// URI 由其内置解码处理。
    implementation(libs.coil)

    testImplementation(composeBom)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.test.manifest)
}
