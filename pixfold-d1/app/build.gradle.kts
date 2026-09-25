plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // 导航迁移(2026-09-22)起 route 用 type-safe 形式:
    // `@Serializable` route 类需要这个插件在**编译期**生成序列化器。
    // 注意 navigation 只把 kotlinx-serialization-core 放在 runtime 变体,
    // 编译期必须自己加 —— 见 docs/notes/d1-navigation-compose-migration.md §4.1。
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutLibraries)
}

android {
    namespace = "com.pixfold.d1"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pixfold.d1"
        // 2026-09-26 由 30 提到 34(用户裁决,替换 2026-09-22 的 24→30)。
        // 理由:应用内语言依赖的 `LocaleManager`/`LocaleConfig` 是 API 33+,
        // 而 33 与 34 的代码清理成本完全相同(都删那 3 处版本守卫),
        // 34 还能拿到 HDR gainmap(`Gainmap`/`Bitmap.getGainmap`)供 D3/D4 图片管线用。
        // 取舍见 HANGOFF.md §12.6 与 docs/notes/d1-toolchain-evidence.md §9。
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
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
        // 仍关闭 `OldTargetApi`:它建议 targetSdk 37,而 §12.6 明确锁定 36
        // (与真机 Android 16 对齐)。这是**刻意的版本策略**,不是待办事项。
        //
        // 约束:`warningsAsErrors = true` 要求这里的豁免清单**尽可能小**;
        // 每次升级工具链后应重新评估能否再收紧。
        disable += setOf(
            "OldTargetApi",
        )
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(project(":domain"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.material.color.utilities)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(composeBom)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
