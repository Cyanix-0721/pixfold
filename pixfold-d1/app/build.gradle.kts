plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutLibraries)
}

android {
    namespace = "com.pixfold.d1"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pixfold.d1"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    testImplementation(composeBom)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
