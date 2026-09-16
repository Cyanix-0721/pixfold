plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.pixfold.d1"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pixfold.d1"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
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
        // 验收清单第 20 项要求"静态检查零告警"。以下 4 项属**版本升级建议**,不是代码质量问题,
        // 且与本项目**刻意锁定**的版本冲突(HANGOFF §12.6 / 规格 §2 约束 4):
        //  - NewerVersionAvailable / GradleDependency / AndroidGradlePluginVersion:
        //    建议升 Kotlin 2.4.20,但 AGP 9.4.0 内置 KGP 为 2.2.10,Compose 编译器插件必须与之相同,
        //    升级会破坏构建(规格 §2 约束 4,已实测)。
        //  - OldTargetApi:建议 targetSdk 37,但 §12.6 明确锁定 36(与真机 Android 16 对齐)。
        // 故显式关闭,使零告警真正反映**代码质量**;升级版本时应重新评估本清单。
        disable += setOf(
            "NewerVersionAvailable",
            "GradleDependency",
            "AndroidGradlePluginVersion",
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

    testImplementation(composeBom)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
