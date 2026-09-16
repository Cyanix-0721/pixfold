# D1 阶段 P1：工程脚手架 + 领域地基 实施计划

> **面向 Agent 执行者**：必需子技能：使用 superpower-subagent-driven-development（推荐）或 superpower-executing-plans 按任务逐项执行本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 搭起 `pixfold-d1/` 两模块 Gradle 工程（`domain/` 纯 Kotlin JVM + `app/` Compose），实现被后续所有阶段共用的领域地基（实体模型、自然排序、多级排序、`PageOrderState` 三重状态、确定性 mock 数据），并让首页两条工作流入口可渲染。

**架构：** `domain/` 是零 Android 依赖的纯 Kotlin JVM 模块，全部用不可变 `data class` + 纯函数表达；`app/` 只做状态持有与 Compose 渲染，依赖方向单向 `app → domain`。本阶段不实现拖拽/命名/元数据/计划的 UI，只把它们依赖的领域原语钉死。

**技术栈：** Kotlin 2.2.10（AGP 内置）、AGP 9.4.0、Gradle 9.6.0、Jetpack Compose（BOM 2026.02.01）、JDK 17（temurin-17）、JUnit4 + Robolectric 4.17。**零三方依赖**。

**规格：** `docs/superpowers/specs/2026-09-16-d1-interaction-prototype-design.md`（执行本计划时须同时阅读；本计划所有类型名与语义以该规格 §4/§5/§10/§11/§12 为准）

**前置阅读：**
- `docs/notes/d1-archive-domain-semantics.md` §1–§3（实体字段、排序算法、`moveItemTo`/`applyRule` 精确语义）
- `docs/notes/d1-toolchain-evidence.md`（版本组合与三个必须避开的坑）

## 全局约束

1. 验收判据不可调整：HANGOFF §9《D1 验收清单》20 项是 D1 唯一出口；不得改判据以迁就现状。
2. 修复自证三层：凡交互或数据改动，依次过「领域单测 → UI 层断言 → 真机实证」；不得只凭"测试通过"交付。
3. 零三方依赖：只用 Kotlin 标准库、AndroidX/Compose、JUnit4、Robolectric；不引入任何拖拽/排序/日期/序列化三方库。
4. 不应用 `kotlin-android` 插件（AGP 9 内置 Kotlin）；Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` 必须单独应用且版本锁 **2.2.10**；子模块声明插件**不带版本号**。
5. 构建走 Windows 原生：经 `cmd.exe /c gradlew.bat` 调用，不在 WSL 内跑 Gradle。
6. compileSdk / minSdk / targetSdk = 36 / 24 / 36；namespace 与 applicationId = `com.pixfold.d1`。
7. `local.properties`、`build/`、`.gradle/`、`.kotlin/` 不入库。
8. `domain/` 不得 import 任何 `android.*` / `androidx.*`。
9. 不可变状态 + 纯函数；不用可变通知链。
10. UI 文案用中文，与规格 §4/§7/§9 的措辞一致。
11. 阈值取自规格，不得臆造：超长 180、非法字符 `\ / : * ? " < > |`、多级排序 1..4 级、页码 `>=100 ? 4 : 3` 位、`indexStart=1`、`indexPadding=3`。
12. 每完成一个任务即提交（只 commit，push 由用户处理）。

---

## 文件结构

本阶段创建的文件与职责：

| 文件 | 职责 |
| --- | --- |
| `pixfold-d1/settings.gradle.kts` | 声明 `:app`、`:domain`，统一仓库 |
| `pixfold-d1/build.gradle.kts` | 根：插件版本 `apply false`（AGP 9.4.0 / compose 2.2.10 / kotlin.jvm 2.2.10） |
| `pixfold-d1/gradle.properties` | JVM 参数、AndroidX 开关 |
| `pixfold-d1/gradle/libs.versions.toml` | 版本目录（单一版本来源） |
| `pixfold-d1/.gitignore` | 排除 `build/`、`.gradle/`、`.kotlin/`、`local.properties` |
| `pixfold-d1/local.properties` | `sdk.dir`（**不入库**） |
| `pixfold-d1/domain/build.gradle.kts` | 纯 Kotlin JVM 模块 + JUnit |
| `domain/.../model/SourceItem.kt` | 来源项实体（含 `fileName`/`relPath`/`sizeLabel`） |
| `domain/.../model/ImageCollection.kt` | 图片集合实体 |
| `domain/.../model/Sort.kt` | `SortField`/`SortKey`/`SortRule` |
| `domain/.../sort/NaturalOrder.kt` | `naturalCompare`（含防溢出数字段比较） |
| `domain/.../sort/PageOrder.kt` | `PageOrderState` + `pageOrderOf`/`moveItemTo`/`togglePin`/`applyRule` |
| `domain/.../mock/MockData.kt` | 确定性 mock 数据（含全部异常样例） |
| `app/build.gradle.kts` | Compose 模块，`implementation(project(":domain"))` |
| `app/src/main/AndroidManifest.xml` | 单 Activity |
| `app/.../MainActivity.kt` | 入口，`setContent { PixFoldApp() }` |
| `app/.../ui/theme/Theme.kt` | Material 3 主题（indigo seed） |
| `app/.../ui/HomeScreen.kt` | 首页：两条工作流入口（验收 U1） |
| `app/.../PixFoldApp.kt` | 应用根 Composable |
| `app/src/test/.../HomeScreenTest.kt` | U1 首页渲染断言 |
| `app/src/test/.../NegativeControlTest.kt` | U0 负向对照（证明 UI 断言非空转） |

**任务顺序**：1 脚手架 → 2 自然排序 → 3 多级排序 → 4 PageOrder 三重状态 → 5 mock 数据 → 6 首页骨架 → 7 阶段收口。

---

### 任务 1：工程脚手架与双模块构建

**文件：**
- 新建：`pixfold-d1/settings.gradle.kts`
- 新建：`pixfold-d1/build.gradle.kts`
- 新建：`pixfold-d1/gradle.properties`
- 新建：`pixfold-d1/gradle/libs.versions.toml`
- 新建：`pixfold-d1/.gitignore`
- 新建：`pixfold-d1/local.properties`
- 新建：`pixfold-d1/domain/build.gradle.kts`
- 新建：`pixfold-d1/app/build.gradle.kts`
- 新建：`pixfold-d1/app/src/main/AndroidManifest.xml`
- 新建：`pixfold-d1/app/src/main/res/values/strings.xml`
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/MainActivity.kt`
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/Placeholder.kt`
- 新建：`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/PlaceholderTest.kt`
- 测试：`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/PlaceholderTest.kt`

**接口：**
- 依赖输入：无（首个任务）
- 对外产出：可用的 Gradle 工程骨架；`gradlew.bat`；模块 `:domain`（纯 JVM，可用 `kotlin.test`）与 `:app`（Compose，可引用 `:domain`）。后续任务在此骨架上添加源文件。

- [ ] **步骤 1：创建目录与版本目录**

```bash
cd /mnt/d/Programing/Personal/pixfold
mkdir -p pixfold-d1/gradle pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain \
         pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain \
         pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/theme \
         pixfold-d1/app/src/main/res/values \
         pixfold-d1/app/src/test/kotlin/com/pixfold/d1
```

`pixfold-d1/gradle/libs.versions.toml`：

```toml
[versions]
agp = "9.4.0"
kotlin = "2.2.10"
composeBom = "2026.02.01"
activityCompose = "1.12.4"
junit = "4.13.2"
robolectric = "4.17"
androidxTestExtJunit = "1.3.0"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
```

- [ ] **步骤 2：创建根 Gradle 文件**

`pixfold-d1/settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pixfold-d1"
include(":app", ":domain")
```

`pixfold-d1/build.gradle.kts`（**插件版本只在此声明，子模块不得带版本号**）：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
```

`pixfold-d1/gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
```

`pixfold-d1/.gitignore`：

```gitignore
build/
.gradle/
.kotlin/
local.properties
*.iml
.idea/
```

`pixfold-d1/local.properties`（**不入库**，仅本机）：

```properties
sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk
```

- [ ] **步骤 3：创建 `:domain` 模块**

`pixfold-d1/domain/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **步骤 4：创建 `:app` 模块**

`pixfold-d1/app/build.gradle.kts`：

```kotlin
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
        }
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
```

`pixfold-d1/app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.PixFold">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`pixfold-d1/app/src/main/res/values/strings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PixFold</string>
</resources>
```

`pixfold-d1/app/src/main/res/values/themes.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.PixFold" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/MainActivity.kt`（本任务先占位，任务 6 替换为真实根组件）：

```kotlin
package com.pixfold.d1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("PixFold") }
    }
}
```

- [ ] **步骤 5：写一个冒烟测试确认 `:domain` 可跑**

`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/Placeholder.kt`：

```kotlin
package com.pixfold.d1.domain

internal const val MODULE_READY = true
```

`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/PlaceholderTest.kt`：

```kotlin
package com.pixfold.d1.domain

import kotlin.test.Test
import kotlin.test.assertTrue

class PlaceholderTest {
    @Test
    fun `domain module compiles and tests run`() {
        assertTrue(MODULE_READY)
    }
}
```

- [ ] **步骤 6：生成 Gradle wrapper**

```bash
cd /mnt/d/Programing/Personal/pixfold/pixfold-d1
GB=$(ls -d /mnt/c/Users/Administrator/.gradle/wrapper/dists/gradle-9.6.0-bin/*/gradle-9.6.0/bin | head -1)
WIN=$(echo "$GB/gradle.bat" | sed 's|^/mnt/c|C:|' | tr '/' '\\')
cat > /tmp/gen-wrapper.bat <<EOF
@echo off
cd /d D:\\Programing\\Personal\\pixfold\\pixfold-d1
call "$WIN" wrapper --gradle-version 9.6.0
EOF
cmd.exe /c "C:\\Users\\Administrator\\AppData\\Local\\Temp\\..\\..\\..\\..\\tmp\\gen-wrapper.bat" 2>/dev/null || cp /tmp/gen-wrapper.bat ./gen-wrapper.bat && cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\gen-wrapper.bat"
rm -f gen-wrapper.bat
ls -la gradlew gradlew.bat gradle/wrapper/
```

- [ ] **步骤 7：运行 `:domain` 测试并确认通过**

先创建便捷批处理（后续所有构建都用它，避免 WSL 内跑 Gradle）：

```bash
cd /mnt/d/Programing/Personal/pixfold/pixfold-d1
cat > run-gradle.bat <<'EOF'
@echo off
cd /d D:\Programing\Personal\pixfold\pixfold-d1
call gradlew.bat %*
EOF
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"
```

预期：`BUILD SUCCESSFUL`，1 个测试通过。

- [ ] **步骤 8：构建 `:app` 并确认通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:assembleDebug --no-daemon --console=plain"`

预期：`BUILD SUCCESSFUL`。若报 `Cannot add extension with name 'kotlin'` 或 `plugin is already on the classpath`，说明插件声明有误 → 对照全局约束 4 修正（**不要**加 `kotlin-android`，子模块插件不带版本号）。

- [ ] **步骤 9：提交**

```bash
cd /mnt/d/Programing/Personal/pixfold
git add pixfold-d1
git commit -m "build(d1): 搭起双模块 Gradle 工程(domain 纯 JVM + app Compose,AGP 9.4/temurin-17 实证)"
```

---

### 任务 2：自然排序

**文件：**
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/sort/NaturalOrder.kt`
- 测试：`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/NaturalOrderTest.kt`

**接口：**
- 依赖输入：无
- 对外产出：
  - `fun naturalCompare(a: String, b: String): Int`
  - `fun tokenizeNatural(s: String): List<String>`（内部记号切分；**公开以便单测钉死切分行为**）
  - 后续任务 3 的 `compareField` 会调用 `naturalCompare`。

- [ ] **步骤 1：编写失败的测试**

`NaturalOrderTest.kt`：

```kotlin
package com.pixfold.d1.domain.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderTest {

    @Test
    fun `numbers compare numerically not lexically`() {
        assertTrue(naturalCompare("img2", "img10") < 0, "2 应小于 10")
        assertTrue(naturalCompare("img10", "img1") > 0, "10 应大于 1")
        assertEquals(0, naturalCompare("img1", "img1"))
    }

    @Test
    fun `equal numbers prefer fewer leading zeros`() {
        // 归档语义:数值相等时比原始位数,位数少者在前 -> "1" < "01"
        assertTrue(naturalCompare("1", "01") < 0, "1 应在 01 之前")
        assertTrue(naturalCompare("01", "1") > 0)
    }

    @Test
    fun `very long digit runs do not overflow`() {
        // 归档用 int.parse 会抛错;新实现必须安全比较
        val big = "9".repeat(30)
        val bigger = "1" + "0".repeat(30)
        assertTrue(naturalCompare(big, bigger) < 0, "30 位 9 应小于 31 位的 10^30")
        assertEquals(0, naturalCompare(big, big))
    }

    @Test
    fun `tokenizer separates digit runs`() {
        // 钉死 Kotlin Regex.split 丢弃捕获组的陷阱:必须用 findAll 手动切记号
        assertEquals(listOf("IMG_", "10", ".jpg"), tokenizeNatural("IMG_10.jpg"))
        assertEquals(listOf("page_", "01", ".png"), tokenizeNatural("page_01.png"))
        assertEquals(listOf("plain.jpg"), tokenizeNatural("plain.jpg"))
    }

    @Test
    fun `digit and non-digit compare by code unit`() {
        // 第 2 位 '1'(0x31) vs 'b'(0x62) -> a1 在前
        assertTrue(naturalCompare("a1", "ab") < 0)
    }

    @Test
    fun `shorter string comes first when prefix equal`() {
        assertTrue(naturalCompare("img", "img1") < 0)
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：编译失败，提示 `Unresolved reference 'naturalCompare'`。

- [ ] **步骤 3：编写最小实现**

`NaturalOrder.kt`：

```kotlin
package com.pixfold.d1.domain.sort

private val DIGIT_RUN = Regex("""\d+""")

/**
 * 把字符串切成「非数字段 / 数字段」交替的记号序列。
 *
 * 注意:必须用 findAll 手动扫描,不能用 Regex.split —— Kotlin 的 split 会丢弃捕获组,
 * 导致数字段丢失、比较恒为 0(排序静默返回原序)。见规格 §13 与 toolchain-evidence。
 */
fun tokenizeNatural(s: String): List<String> {
    val tokens = mutableListOf<String>()
    var last = 0
    for (m in DIGIT_RUN.findAll(s)) {
        if (m.range.first > last) tokens += s.substring(last, m.range.first)
        tokens += m.value
        last = m.range.last + 1
    }
    if (last < s.length) tokens += s.substring(last)
    return tokens
}

private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

/** 去前导零;全零退化为 "0"。 */
private fun stripLeadingZeros(s: String): String {
    val trimmed = s.trimStart('0')
    return if (trimmed.isEmpty()) "0" else trimmed
}

/**
 * 自然排序:数字段按数值比较,非数字段按单字符码点比较。
 * 语义对齐归档原型 naturalCompare,但对超长数字串不溢出。
 */
fun naturalCompare(a: String, b: String): Int {
    val ta = tokenizeNatural(a)
    val tb = tokenizeNatural(b)
    val n = minOf(ta.size, tb.size)
    for (i in 0 until n) {
        val x = ta[i]
        val y = tb[i]
        if (isDigits(x) && isDigits(y)) {
            val nx = stripLeadingZeros(x)
            val ny = stripLeadingZeros(y)
            // 1) 数值大小:先比有效长度,再比字典序
            val c = if (nx.length != ny.length) {
                nx.length.compareTo(ny.length)
            } else {
                nx.compareTo(ny)
            }
            if (c != 0) return c
            // 2) 数值相等 -> 原始位数少者在前
            if (x.length != y.length) return x.length.compareTo(y.length)
        } else {
            // 非数字(或一边数字一边非数字):逐字符码点比较
            val m = minOf(x.length, y.length)
            for (k in 0 until m) {
                val c = x[k].code.compareTo(y[k].code)
                if (c != 0) return c
            }
            if (x.length != y.length) return x.length.compareTo(y.length)
        }
    }
    return ta.size.compareTo(tb.size)
}
```

- [ ] **步骤 4：运行测试并确认其通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`，6 个测试全部通过。

> 若 `tokenizer separates digit runs` 失败并显示记号粘连 → 说明误用了 `Regex.split`，回到步骤 3 改用 `findAll`。

- [ ] **步骤 5：提交**

```bash
git add pixfold-d1/domain
git commit -m "feat(d1/domain): 自然排序(数字段数值比较 + 防溢出 + 前导零语义)"
```

---

### 任务 3：实体模型与多级排序

**文件：**
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/model/SourceItem.kt`
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/model/ImageCollection.kt`
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/model/Sort.kt`
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/sort/SortItems.kt`
- 测试：`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/SortItemsTest.kt`

**接口：**
- 依赖输入：任务 2 的 `naturalCompare`
- 对外产出：
  - `data class SourceItem(id, collectionId, dir, baseName, ext, sizeBytes, modifiedEpochMillis, createdEpochMillis, seed)`，派生 `fileName`/`relPath`/`sizeLabel`
  - `data class ImageCollection(id, name, rootDirName, images)`
  - `enum class SortField(label)`、`data class SortKey(field, ascending)`、`data class SortRule(keys)`
  - `fun sortItems(images: List<SourceItem>, rule: SortRule): List<SourceItem>`
  - `fun defaultRule(): SortRule`
  - 任务 4/5 依赖以上全部类型。

- [ ] **步骤 1：编写失败的测试**

`SortItemsTest.kt`：

```kotlin
package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule
import kotlin.test.Test
import kotlin.test.assertEquals

private fun item(
    id: String,
    dir: String = "",
    base: String = id,
    ext: String = "jpg",
    size: Long = 100,
    modified: Long = 0,
    created: Long? = 0,
) = SourceItem(
    id = id, collectionId = "c", dir = dir, baseName = base, ext = ext,
    sizeBytes = size, modifiedEpochMillis = modified, createdEpochMillis = created, seed = 0,
)

class SortItemsTest {

    @Test
    fun `natural name sorts numerically`() {
        val list = listOf(item("i10", base = "img10"), item("i2", base = "img2"), item("i1", base = "img1"))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.NaturalName, true))))
        assertEquals(listOf("i1", "i2", "i10"), sorted.map { it.id })
    }

    @Test
    fun `lexical name sorts differently from natural`() {
        val list = listOf(item("i10", base = "img10"), item("i2", base = "img2"), item("i1", base = "img1"))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.FileName, true))))
        assertEquals(listOf("i1", "i10", "i2"), sorted.map { it.id })
    }

    @Test
    fun `each level has its own direction`() {
        val list = listOf(
            item("a2", dir = "d1", base = "p2"),
            item("a1", dir = "d1", base = "p1"),
            item("b1", dir = "d2", base = "p1"),
        )
        // 目录名升序,同目录内文件名自然序降序
        val rule = SortRule(listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, false)))
        val sorted = sortItems(list, rule)
        assertEquals(listOf("a2", "a1", "b1"), sorted.map { it.id })
    }

    @Test
    fun `ties fall back to id for determinism`() {
        val list = listOf(item("z"), item("a"), item("m"))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.Size, true))))
        assertEquals(listOf("a", "m", "z"), sorted.map { it.id })
    }

    @Test
    fun `sorting does not mutate input`() {
        val list = listOf(item("i10", base = "img10"), item("i2", base = "img2"))
        val before = list.map { it.id }
        sortItems(list, SortRule(listOf(SortKey(SortField.NaturalName, true))))
        assertEquals(before, list.map { it.id })
    }

    @Test
    fun `default rule is dir asc then natural name asc`() {
        val r = defaultRule()
        assertEquals(listOf(SortField.DirName, SortField.NaturalName), r.keys.map { it.field })
        assertEquals(listOf(true, true), r.keys.map { it.ascending })
    }

    @Test
    fun `null created sorts first`() {
        val list = listOf(item("has", created = 100), item("none", created = null))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.Created, true))))
        assertEquals(listOf("none", "has"), sorted.map { it.id })
    }

    @Test
    fun `sizeLabel formats units`() {
        assertEquals("500 B", item("x", size = 500).sizeLabel)
        assertEquals("2 KB", item("x", size = 2048).sizeLabel)
        assertEquals("1.5 MB", item("x", size = 1572864).sizeLabel)
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：编译失败，`Unresolved reference 'SortField'` 等。

- [ ] **步骤 3：编写实体模型**

`model/SourceItem.kt`：

```kotlin
package com.pixfold.d1.domain.model

/**
 * 来源文件快照(= 归档原型的 MockImage)。
 * 时间用 epoch millis 而非 java.time,避免 minSdk 24 的 desugaring(规格 §4.1 注)。
 */
data class SourceItem(
    val id: String,
    val collectionId: String,
    val dir: String,
    val baseName: String,
    val ext: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val createdEpochMillis: Long?,
    val seed: Int,
) {
    val fileName: String get() = "$baseName.$ext"
    val relPath: String get() = if (dir.isEmpty()) fileName else "$dir/$fileName"

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024L * 1024L -> "${"%.1f".format(sizeBytes / 1024.0 / 1024.0)} MB"
            sizeBytes >= 1024L -> "${sizeBytes / 1024} KB"
            else -> "$sizeBytes B"
        }
}
```

`model/ImageCollection.kt`：

```kotlin
package com.pixfold.d1.domain.model

/** 图片集合 / 漫画候选集合。rootDirName 独立于 name,是命名组件 rootDir 的取值来源。 */
data class ImageCollection(
    val id: String,
    val name: String,
    val rootDirName: String,
    val images: List<SourceItem>,
)
```

`model/Sort.kt`：

```kotlin
package com.pixfold.d1.domain.model

/** 枚举声明顺序 = UI 下拉顺序(HANGOFF §4 排序键定位,2026-09-10 用户定序),不得重排。 */
enum class SortField(val label: String) {
    NaturalName("文件名(自然)"),
    FileName("文件名(字典)"),
    DirName("目录名"),
    Modified("修改时间"),
    Created("创建时间"),
    Size("文件大小"),
}

data class SortKey(val field: SortField, val ascending: Boolean)

/** keys.size 必须在 1..4 之间(至少 1 级、最多 4 级)。 */
data class SortRule(val keys: List<SortKey>) {
    init {
        require(keys.isNotEmpty()) { "排序规则至少需要 1 级" }
        require(keys.size <= MAX_LEVELS) { "排序规则最多 $MAX_LEVELS 级" }
    }

    companion object {
        const val MAX_LEVELS = 4
    }
}

fun defaultRule(): SortRule = SortRule(
    listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, true)),
)
```

- [ ] **步骤 4：编写多级排序实现**

`sort/SortItems.kt`：

```kotlin
package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem

private fun compareField(a: SourceItem, b: SourceItem, field: SortField): Int = when (field) {
    // 自然序与字典序都先转小写(大小写不敏感);目录名保持原始码点序
    SortField.NaturalName -> naturalCompare(a.fileName.lowercase(), b.fileName.lowercase())
    SortField.FileName -> a.fileName.lowercase().compareTo(b.fileName.lowercase())
    SortField.DirName -> a.dir.compareTo(b.dir)
    SortField.Modified -> a.modifiedEpochMillis.compareTo(b.modifiedEpochMillis)
    // created 可能缺失,null 视为最小
    SortField.Created -> when {
        a.createdEpochMillis == null && b.createdEpochMillis == null -> 0
        a.createdEpochMillis == null -> -1
        b.createdEpochMillis == null -> 1
        else -> a.createdEpochMillis.compareTo(b.createdEpochMillis)
    }
    SortField.Size -> a.sizeBytes.compareTo(b.sizeBytes)
}

/**
 * 多级排序(纯函数,不改入参)。
 * 每级独立升降序;全部级相等时按 id 字典序兜底 -> 结果确定(必需,见规格 §5.2)。
 */
fun sortItems(images: List<SourceItem>, rule: SortRule): List<SourceItem> =
    images.sortedWith { a, b ->
        var result = 0
        for (key in rule.keys) {
            val c = compareField(a, b, key.field)
            if (c != 0) {
                result = if (key.ascending) c else -c
                break
            }
        }
        if (result != 0) result else a.id.compareTo(b.id)
    }
```

- [ ] **步骤 5：运行测试并确认其通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`，全部测试通过（含任务 2 的 6 个）。

- [ ] **步骤 6：提交**

```bash
git add pixfold-d1/domain
git commit -m "feat(d1/domain): 实体模型(SourceItem/ImageCollection/SortRule)与多级排序"
```

---

### 任务 4：`PageOrderState` 三重状态

**文件：**
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/sort/PageOrder.kt`
- 测试：`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/sort/PageOrderTest.kt`

**接口：**
- 依赖输入：任务 3 的 `SourceItem`/`SortRule`/`sortItems`/`defaultRule`
- 对外产出：
  - `data class PageOrderState(images, rule, order, manualIds, pinnedIds)` + `hasManual`/`manualCount`/`pinnedCount`/`isManual`/`isPinned`
  - `fun pageOrderOf(images, rule = defaultRule()): PageOrderState`
  - `fun moveItemTo(state, id, targetIndex): PageOrderState`
  - `fun togglePin(state, id): PageOrderState`
  - `fun applyRule(state, rule): PageOrderState`
  - `fun resetToAuto(state): PageOrderState`
  - 任务 5 的 mock 数据与 P3 的拖拽 UI 依赖这些函数。

- [ ] **步骤 1：编写失败的测试**

`PageOrderTest.kt`：

```kotlin
package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun items(n: Int): List<SourceItem> = (1..n).map {
    SourceItem(
        id = "i$it", collectionId = "c", dir = "", baseName = "img$it", ext = "jpg",
        sizeBytes = it.toLong(), modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 0,
    )
}

class PageOrderTest {

    @Test
    fun `initial state has no manual or pinned`() {
        val s = pageOrderOf(items(5))
        assertFalse(s.hasManual)
        assertEquals(0, s.manualCount)
        assertEquals(0, s.pinnedCount)
        assertEquals(5, s.order.size)
    }

    @Test
    fun `moveItemTo removes then inserts at target index`() {
        val s = pageOrderOf(items(6))
        val ids = s.order.map { it.id }
        val moved = moveItemTo(s, ids[0], 5)
        // 钉死"先删后插":等价于 removeAt(0) 再 insert(5, ids[0])
        val expected = ids.toMutableList().also { it.removeAt(0); it.add(5, ids[0]) }
        assertEquals(expected, moved.order.map { it.id })
        assertTrue(moved.isManual(ids[0]), "应记录人工标记")
    }

    @Test
    fun `moveItemTo to last position`() {
        val s = pageOrderOf(items(4))
        val id = s.order.first().id
        val moved = moveItemTo(s, id, 3)
        assertEquals(id, moved.order.last().id)
    }

    @Test
    fun `moveItemTo clamps out of range`() {
        val s = pageOrderOf(items(4))
        val first = s.order.first().id
        assertEquals(first, moveItemTo(s, first, -5).order.first().id)
        val last = s.order.last().id
        assertEquals(last, moveItemTo(s, last, 99).order.last().id)
    }

    @Test
    fun `moveItemTo onto itself is a no-op and records no manual mark`() {
        val s = pageOrderOf(items(4))
        val ids = s.order.map { it.id }
        val same = moveItemTo(s, ids[1], 1)
        assertEquals(ids, same.order.map { it.id })
        assertFalse(same.isManual(ids[1]), "拖到自己格不应记录人工标记")
    }

    @Test
    fun `moveItemTo unknown id is a silent no-op`() {
        val s = pageOrderOf(items(3))
        val after = moveItemTo(s, "nope", 0)
        assertEquals(s.order.map { it.id }, after.order.map { it.id })
    }

    @Test
    fun `pinned item keeps position across applyRule while manual marks clear`() {
        val s0 = pageOrderOf(items(5))
        val lastId = s0.order.last().id

        // 1) 拖到首位 -> 人工标记
        val s1 = moveItemTo(s0, lastId, 0)
        assertEquals(lastId, s1.order.first().id)
        assertTrue(s1.isManual(lastId))

        // 2) 固定
        val s2 = togglePin(s1, lastId)
        assertTrue(s2.isPinned(lastId))
        assertEquals(1, s2.pinnedCount)

        // 3) 重新应用排序 -> 固定项保位、人工标记清空、固定标记保留
        val s3 = applyRule(s2, defaultRule())
        assertEquals(lastId, s3.order.first().id, "固定项应保持原位")
        assertFalse(s3.hasManual, "applyRule 应清空人工标记")
        assertTrue(s3.isPinned(lastId), "固定标记应保留")

        // 4) 解除固定后回归自动序
        val s4 = togglePin(s3, lastId)
        assertFalse(s4.isPinned(lastId))
        val s5 = applyRule(s4, defaultRule())
        assertEquals(lastId, s5.order.last().id, "解除固定后应回到规则决定的位置")
    }

    @Test
    fun `resetToAuto keeps pinned positions but drops manual marks`() {
        val s0 = pageOrderOf(items(5))
        val lastId = s0.order.last().id
        val s1 = togglePin(moveItemTo(s0, lastId, 0), lastId)
        val reset = resetToAuto(s1)
        assertEquals(lastId, reset.order.first().id)
        assertFalse(reset.hasManual)
    }

    @Test
    fun `applyRule with a new rule reorders the rest`() {
        val s = pageOrderOf(items(4))
        val descending = SortRule(listOf(SortKey(SortField.NaturalName, false)))
        val applied = applyRule(s, descending)
        assertEquals(listOf("i4", "i3", "i2", "i1"), applied.order.map { it.id })
    }

    @Test
    fun `togglePin twice unpins`() {
        val s = pageOrderOf(items(3))
        val id = s.order.first().id
        assertTrue(togglePin(s, id).isPinned(id))
        assertFalse(togglePin(togglePin(s, id), id).isPinned(id))
    }

    @Test
    fun `pinned collision falls back to the nearest free slot on the left`() {
        // 归档规则:固定项落位被占时"向左找第一个空位"。
        // 正常路径下记录下标互不相同且升序,slot 不会碰撞 —— 该分支仅在退化状态(页序长于全集)可达。
        // 这里直接构造退化状态,把这条文档化语义钉死;否则"向左"被写成"向右"不会有任何测试察觉。
        val all = items(3)
        fun extra(id: String, base: String) = SourceItem(
            id = id, collectionId = "c", dir = "", baseName = base, ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 0,
        )
        val e4 = extra("i4", "img4")
        val e5 = extra("i5", "img5")

        val degenerate = PageOrderState(
            images = all,                                        // slots 只有 3 格(下标 0..2)
            rule = defaultRule(),
            order = listOf(all[0], all[1], all[2], e4, e5),       // 固定项记录下标 3 与 4
            pinnedIds = setOf(e4.id, e5.id),
        )

        val applied = applyRule(degenerate, defaultRule())

        // 两个固定项的夹取目标都是下标 2:
        //   先处理记录下标 3(e4) -> 落 slots[2]
        //   再处理记录下标 4(e5) -> slots[2] 已占 -> 向左找到 slots[1]
        // 若实现误为"向右",第二项会越界或落错位,本断言即失败。
        assertEquals(e4.id, applied.order[2].id, "先处理的固定项应落在夹取位")
        assertEquals(e5.id, applied.order[1].id, "撞位的固定项应向左落到最近空位")
        assertEquals(3, applied.order.size, "结果长度应等于全集长度")
    }
}
```

> **该用例的由来(执行时补加)**:实施本任务时用**变异测试**发现,把"向左找空位"改成"向右"时
> **全部 25 个测试仍然通过** —— 因为正常路径下固定项的记录下标互不相同且升序,该分支不可达。
> 故补上此用例直接构造退化状态(页序长于全集),使这条文档化语义真正被钉死。
> 变异复验:改回右扫 → 该用例失败;恢复左扫 → 通过。

- [ ] **步骤 2：运行测试并确认其失败**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：编译失败，`Unresolved reference 'pageOrderOf'`。

- [ ] **步骤 3：编写最小实现**

`sort/PageOrder.kt`：

```kotlin
package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule

/**
 * 页序三重状态(规格 §5.3):
 * - 自动层: rule + order(由 sortItems 算出)
 * - 人工层: manualIds —— 标记用户拖拽过的项;applyRule 时清空
 * - 固定层: pinnedIds —— 图钉;applyRule 时保留
 */
data class PageOrderState(
    val images: List<SourceItem>,
    val rule: SortRule,
    val order: List<SourceItem>,
    val manualIds: Set<String> = emptySet(),
    val pinnedIds: Set<String> = emptySet(),
) {
    val hasManual: Boolean get() = manualIds.isNotEmpty()
    val manualCount: Int get() = manualIds.size
    val pinnedCount: Int get() = pinnedIds.size
    fun isManual(id: String): Boolean = id in manualIds
    fun isPinned(id: String): Boolean = id in pinnedIds
}

fun pageOrderOf(images: List<SourceItem>, rule: SortRule = defaultRule()): PageOrderState =
    PageOrderState(images = images, rule = rule, order = sortItems(images, rule))

/**
 * 唯一重排入口(规格 §5.3)。targetIndex 是"移除源项之后"的插入位。
 * 边界:不存在的 id / 拖到自己格 -> 原样返回;越界 -> 夹取。
 */
fun moveItemTo(state: PageOrderState, id: String, targetIndex: Int): PageOrderState {
    val from = state.order.indexOfFirst { it.id == id }
    if (from < 0) return state
    val target = targetIndex.coerceIn(0, state.order.size - 1)
    if (from == target) return state
    val next = state.order.toMutableList()
    val item = next.removeAt(from)
    next.add(target, item)
    return state.copy(order = next, manualIds = state.manualIds + id)
}

fun togglePin(state: PageOrderState, id: String): PageOrderState {
    if (state.order.none { it.id == id }) return state
    val next = if (id in state.pinnedIds) state.pinnedIds - id else state.pinnedIds + id
    return state.copy(pinnedIds = next)
}

/**
 * 应用排序规则(规格 §5.3):固定项按"记录下标优先、冲突向左找空位"落位,
 * 其余项按新规则填满空位;manualIds 清空,pinnedIds 保留。
 */
fun applyRule(state: PageOrderState, rule: SortRule): PageOrderState {
    // 1) 记录固定项当前下标(按下标升序处理 -> 结果确定)
    val pinnedAt = state.order.withIndex()
        .filter { (_, item) -> item.id in state.pinnedIds }
        .associate { (i, item) -> i to item }

    // 2) 按新规则排序,剔除固定项
    val sorted = sortItems(state.images, rule)
    val rest = sorted.filter { it.id !in state.pinnedIds }

    // 3) 固定项优先落位,被占则向左找空位
    val slots = arrayOfNulls<SourceItem>(state.images.size)
    for ((recorded, item) in pinnedAt.entries.sortedBy { it.key }) {
        var idx = minOf(recorded, slots.size - 1)
        while (idx >= 0 && slots[idx] != null) idx--
        if (idx < 0) continue
        slots[idx] = item
    }

    // 4) 其余项按新规则顺序填洞
    var ri = 0
    for (i in slots.indices) {
        if (slots[i] == null) slots[i] = rest[ri++]
    }

    return state.copy(
        rule = rule,
        order = slots.filterNotNull(),
        manualIds = emptySet(),
        pinnedIds = state.pinnedIds,
    )
}

/** 放弃人工调整但保留固定项位置(走同一条 applyRule 路径)。 */
fun resetToAuto(state: PageOrderState): PageOrderState = applyRule(state, state.rule)
```

- [ ] **步骤 4：运行测试并确认其通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`，全部通过。

- [ ] **步骤 5：提交**

```bash
git add pixfold-d1/domain
git commit -m "feat(d1/domain): PageOrderState 三重状态(先删后插重排 + 固定项落位算法)"
```

---

### 任务 5：确定性 mock 数据

**文件：**
- 新建：`pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/mock/MockData.kt`
- 测试：`pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/mock/MockDataTest.kt`

**接口：**
- 依赖输入：任务 3/4 的模型与 `PageOrderState`
- 对外产出：
  - `object MockData`，含 `workspaceA: MockWorkspaceA`、`libraryB: MockLibraryB`
  - `data class MockWorkspaceA(rootPath: String, collections: List<ImageCollection>)`
  - `data class MockLibraryB(rootPath: String, volumes: List<MockVolume>)`
  - `data class MockVolume(id, dirPath, pages, titleSug, seriesSug, writerSug, volumeSug, langSug, outputExists)`
  - P2–P6 全部 UI 阶段依赖此 mock 数据。
  - **本任务只做工作流 A 的集合与页序**;工作流 B 的卷 mock 在 P5 之前可先建最小版本（见下）。

- [ ] **步骤 1：编写失败的测试**

`MockDataTest.kt`：

```kotlin
package com.pixfold.d1.domain.mock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockDataTest {

    @Test
    fun `workspace A has three collections`() {
        val ws = MockData.workspaceA
        assertEquals(listOf("trip", "scan", "misc"), ws.collections.map { it.id })
    }

    @Test
    fun `trip collection spans two dirs with 30 images`() {
        val trip = MockData.workspaceA.collections.first { it.id == "trip" }
        assertEquals(setOf("day1", "day2"), trip.images.map { it.dir }.toSet())
        assertEquals(30, trip.images.size)
    }

    @Test
    fun `scan collection covers duplicate illegal and case-collision samples`() {
        val scan = MockData.workspaceA.collections.first { it.id == "scan" }
        // 跨目录同名 baseName
        assertEquals(2, scan.images.count { it.baseName == "page_01" })
        // 非法字符样例
        assertTrue(scan.images.any { it.baseName.contains(':') || it.baseName.contains('?') })
        // 大小写冲突对
        assertTrue(scan.images.any { it.ext == "JPG" })
    }

    @Test
    fun `misc collection exposes natural vs lexical difference`() {
        val misc = MockData.workspaceA.collections.first { it.id == "misc" }
        assertEquals(12, misc.images.size)
        assertTrue(misc.images.any { it.baseName == "img10" }, "应含 img10 以体现字典序与自然序差异")
    }

    @Test
    fun `mock data is deterministic across calls`() {
        val a = MockData.workspaceA.collections.first { it.id == "misc" }.images.map { it.id }
        val b = MockData.workspaceA.collections.first { it.id == "misc" }.images.map { it.id }
        assertEquals(a, b)
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：编译失败，`Unresolved reference 'MockData'`。

- [ ] **步骤 3：编写 mock 数据**

`mock/MockData.kt`：

```kotlin
package com.pixfold.d1.domain.mock

import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SourceItem

data class MockWorkspaceA(val rootPath: String, val collections: List<ImageCollection>)

/**
 * 确定性 mock 数据(语义对齐归档原型 mock_data.dart)。
 * 异常样例是语义的一部分:重名 / 非法字符 / 大小写冲突 / 自然序 vs 字典序。
 */
object MockData {

    private const val DAY = 24L * 60 * 60 * 1000

    private fun img(
        id: String, collectionId: String, dir: String, base: String, ext: String = "jpg",
        size: Long = 1024, modified: Long = 0, created: Long? = 0, seed: Int = 0,
    ) = SourceItem(id, collectionId, dir, base, ext, size, modified, created, seed)

    // ---- 集合 1: 旅行照片(正常样例 + 多目录分组) ----
    private fun trip(): ImageCollection {
        val images = buildList {
            for (i in 1..18) add(
                img("trip-day1-$i", "trip", "day1", "IMG_20260701_%03d".format(i), size = 2_000_000L + i, seed = i)
            )
            for (i in 1..12) add(
                img("trip-day2-$i", "trip", "day2", "IMG_20260702_%03d".format(i), size = 1_800_000L + i, seed = 100 + i)
            )
        }
        return ImageCollection("trip", "旅行照片", "旅行照片", images)
    }

    // ---- 集合 2: 扫描件(重名 / 非法字符 / 大小写冲突) ----
    private fun scan(): ImageCollection {
        val images = buildList {
            for (i in 1..6) add(img("scan-ch01-$i", "scan", "ch01", "page_%02d".format(i), ext = "png", seed = 200 + i))
            for (i in 1..6) add(img("scan-ch02-$i", "scan", "ch02", "page_%02d".format(i), ext = "png", seed = 300 + i))
            // 非法字符样例(Windows 非法字符集,Android 同样保守处理)
            add(img("scan-illegal", "scan", "ch02", "封:面?", ext = "png", seed = 400))
            // 大小写冲突对:与 ch02/page_01.png 撞 lowercase
            add(img("scan-case", "scan", "ch02", "Page_01", ext = "PNG", seed = 401))
        }
        return ImageCollection("scan", "扫描件", "扫描件", images)
    }

    // ---- 集合 3: 杂图(自然序 vs 字典序) ----
    private fun misc(): ImageCollection {
        val images = (1..12).map { i -> img("misc-$i", "misc", "", "img$i", seed = 500 + i) }
        return ImageCollection("misc", "杂图", "杂图", images)
    }

    val workspaceA: MockWorkspaceA = MockWorkspaceA(
        rootPath = "D:\\照片整理_2026",
        collections = listOf(trip(), scan(), misc()),
    )
}
```

> **注**:工作流 B 的 `MockLibraryB`(5 卷,含卷号/语言建议与来源字符串)**在 P5 实现**,
> 本任务不建空壳,以免出现未使用的类型。

- [ ] **步骤 4：运行测试并确认其通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`，全部通过。

- [ ] **步骤 5：删除占位文件**

```bash
rm pixfold-d1/domain/src/main/kotlin/com/pixfold/d1/domain/Placeholder.kt
rm pixfold-d1/domain/src/test/kotlin/com/pixfold/d1/domain/PlaceholderTest.kt
```

- [ ] **步骤 6：运行测试确认仍通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 7：提交**

```bash
git add -A pixfold-d1/domain
git commit -m "feat(d1/domain): 确定性 mock 数据(含重名/非法字符/大小写冲突/自然序样例)"
```

---

### 任务 6：首页骨架与 UI 断言

**文件：**
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/theme/Theme.kt`
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/HomeScreen.kt`
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt`
- 修改：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/MainActivity.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/HomeScreenTest.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/NegativeControlTest.kt`

**接口：**
- 依赖输入：任务 5 的 `MockData`
- 对外产出：
  - `@Composable fun PixFoldApp()`
  - `@Composable fun HomeScreen(onOpenWorkflowA: () -> Unit, onOpenWorkflowB: () -> Unit)`
  - `@Composable fun PixFoldTheme(content: @Composable () -> Unit)`
  - P2–P6 在此基础上加路由与页面。

- [ ] **步骤 1：编写失败的测试**

`HomeScreenTest.kt`：

```kotlin
package com.pixfold.d1.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.PixFoldApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** U1:首页必须同时渲染两条工作流入口(产品级并列结构,不可合并/隐藏)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `home renders both workflow entries`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 A · 图片整理与命名").assertIsDisplayed()
        rule.onNodeWithText("工作流 B · CBZ 制作").assertIsDisplayed()
    }

    @Test
    fun `clicking workflow A entry invokes callback`() {
        var clicked = 0
        rule.setContent { HomeScreen(onOpenWorkflowA = { clicked++ }, onOpenWorkflowB = {}) }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `clicking workflow B entry invokes callback`() {
        var clicked = 0
        rule.setContent { HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = { clicked++ }) }
        rule.onNodeWithText("工作流 B · CBZ 制作").performClick()
        assertEquals(1, clicked)
    }
}
```

`NegativeControlTest.kt`（**必须保留**：证明第 2 层断言不是空转）：

```kotlin
package com.pixfold.d1

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U0 负向对照:**本测试预期失败**。
 *
 * 用一个普通 var(而非 mutableStateOf)承载状态 —— 点击真的改了数据,但 Compose 不会重组,
 * 即归档原型"数据对了但界面不动"的缺陷类别(拖拽 bug 连修 4 轮的根因)。
 * 若本测试**通过**了,说明 UI 断言无法捕获该类别,第 2 层自证失效,必须排查测试基建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NegativeControlTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `UI assertion catches non-observable state - EXPECTED TO FAIL`() {
        rule.setContent { SilentStateScreen() }
        rule.onNodeWithTag("silent-count").assertTextEquals("count=0")
        rule.onNodeWithText("silent-inc").performClick()
        // 数据其实已变成 1,但界面不会重组 -> 这里必须失败
        rule.onNodeWithTag("silent-count").assertTextEquals("count=1")
    }
}

@Composable
private fun SilentStateScreen() {
    var count = 0
    Column {
        Text(text = "count=$count", modifier = Modifier.testTag("silent-count"))
        Button(onClick = { count++ }) { Text("silent-inc") }
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"`
预期：编译失败，`Unresolved reference 'PixFoldApp'`。

- [ ] **步骤 3：编写主题与首页**

`ui/theme/Theme.kt`：

```kotlin
package com.pixfold.d1.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    secondary = Color(0xFF5C6BC0),
)

@Composable
fun PixFoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
```

`ui/HomeScreen.kt`：

```kotlin
package com.pixfold.d1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

/** 首页:两条工作流入口(验收第 19 项"从首页完整走通")。 */
@Composable
fun HomeScreen(
    onOpenWorkflowA: () -> Unit,
    onOpenWorkflowB: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "PixFold", style = MaterialTheme.typography.headlineMedium)
        WorkflowEntry("工作流 A · 图片整理与命名", onOpenWorkflowA)
        WorkflowEntry("工作流 B · CBZ 制作", onOpenWorkflowB)
    }
}

@Composable
private fun WorkflowEntry(title: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(text = title, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
    }
}
```

`PixFoldApp.kt`：

```kotlin
package com.pixfold.d1

import androidx.compose.runtime.Composable
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.theme.PixFoldTheme

@Composable
fun PixFoldApp() {
    PixFoldTheme {
        // P2–P6 会在此加入路由与各步骤页面;P1 只有首页。
        HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = {})
    }
}
```

`MainActivity.kt`（替换任务 1 的占位实现）：

```kotlin
package com.pixfold.d1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixFoldApp() }
    }
}
```

- [ ] **步骤 4：运行 UI 测试并确认首页用例通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"`

预期：
- `HomeScreenTest` 3 项 **通过**；
- `NegativeControlTest` 1 项 **失败**（`AssertionError`，断言 `count=1` 时实际仍是 `count=0`）——**这是预期的**。

> 若 `NegativeControlTest` 反而**通过**了，停下来排查：说明 UI 断言没真正观察重组，第 2 层自证失效。

- [ ] **步骤 5：把负向对照隔离到单独任务，使常规测试全绿**

在 `app/build.gradle.kts` 的 `android { }` 内加测试过滤，把负向对照排除出默认测试任务：

```kotlin
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
```

- [ ] **步骤 6：运行常规测试确认全绿**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`，`HomeScreenTest` 3 项通过，`NegativeControlTest` 被排除。

- [ ] **步骤 7：运行负向对照确认它确实失败（自证有效）**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest -PincludeNegativeControl --no-daemon --console=plain"`
预期：**BUILD FAILED**，且失败用例是 `NegativeControlTest`。这证明第 2 层能捕获"数据变了界面不动"。

- [ ] **步骤 8：提交**

```bash
git add -A pixfold-d1
git commit -m "feat(d1/app): 首页两条工作流入口 + UI 层断言与负向对照(U0/U1)"
```

---

### 任务 7：阶段收口与文档回写

**文件：**
- 修改：`HANGOFF.md`（§9.1 阶段表 P1 状态）
- 新建：`docs/superpowers/plans/2026-09-16-d1-p1-scaffold-domain.md`（本文件本身）
- 修改：`README.md`（进度行，如需）

**接口：**
- 依赖输入：任务 1–6 全部完成
- 对外产出：P1 阶段在 `dev` 上的文档状态与实际代码一致；为 P2 计划提供起点。

- [ ] **步骤 1：运行完整验证（第 1 层 + 第 2 层 + 构建 + 静态检查）**

```bash
cd /mnt/d/Programing/Personal/pixfold/pixfold-d1
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :domain:test --no-daemon --console=plain"
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:assembleDebug --no-daemon --console=plain"
cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:lintDebug --no-daemon --console=plain"
```

预期：前三条 `BUILD SUCCESSFUL`；lint 若报 warning 则逐条修到零告警（验收第 20 项要求"静态检查零告警"）。

- [ ] **步骤 2：统计并记录测试数**

```bash
grep -ho 'tests="[0-9]*"' pixfold-d1/domain/build/test-results/test/*.xml | head -50
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' pixfold-d1/app/build/test-results/testDebugUnitTest/*.xml
```

记录：领域单测数 = ____；UI 断言数 = ____。

- [ ] **步骤 3：回写 HANGOFF §9.1 阶段表**

在 §9.1 的七阶段表中，把 P1 行的"交付物"后追加状态标记：

```markdown
| **P1** | 脚手架(AGP 9.4/Gradle 9.6/Compose BOM)+ 领域地基(模型、自然排序、多级排序、`PageOrder`/`moveItemTo`/`applyRule`/图钉)+ 确定性 mock 数据 ✅ **完成(YYYY-MM-DD)** | —(地基) | 第 1 层 |
```

- [ ] **步骤 4：提交文档回写**

```bash
cd /mnt/d/Programing/Personal/pixfold
git add HANGOFF.md README.md docs/
git commit -m "docs(d1): P1 阶段收口——阶段表状态回写与验证记录"
```

- [ ] **步骤 5：合并回 `dev`**

```bash
git checkout dev
git merge --no-ff d1/p1 -m "merge(d1): P1 工程脚手架 + 领域地基"
git branch -d d1/p1
```

> **不 push**（用户约定：push 由用户处理）。

---

## 自检结果

**1. 规格覆盖度**：本计划覆盖规格 §3(工程结构)、§4.1–4.3(模型与排序枚举)、§5.1–5.4(排序与三重状态)、§11(状态管理约定)、§12.1–12.2(第 1/2 层测试)、§10.2 的 P1 行。规格 §4.4–4.7/§6–§9 属 P4–P6，本计划**有意不覆盖**（阶段切片原则：领域逻辑随所属阶段进）。规格 §12.3(真机层)属 P7。

**2. 占位符扫描**：无 TBD/TODO/待补充；所有代码步骤含完整可编译代码块；`MockLibraryB` 明确标注在 P5 实现而非留空壳。

**3. 类型一致性**：`SourceItem`/`SortRule`/`PageOrderState`/`moveItemTo`/`applyRule`/`togglePin`/`resetToAuto`/`naturalCompare`/`tokenizeNatural`/`sortItems`/`defaultRule`/`MockData` 在任务 2–6 间签名一致；`SortRule.MAX_LEVELS = 4` 与规格 §2 约束 11 一致；`ILLEGAL_NAME_CHARS`/`TOO_LONG_THRESHOLD` 属 P4，本计划未提前定义。
