package com.pixfold.d1.domain.boundary

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3 退出条件 ② 的**可执行检查**:`domain/` 保持零 Android 依赖(2026-10-05 用户裁决补做)。
 *
 * ## 为什么需要一个可执行检查
 *
 * `HANGOFF.md` §9 D3 的退出条件 ② 原文要求:「`domain/` 保持零 Android 依赖,平台层
 * (SAF/ContentResolver)只承担资源访问、缩略图、权限、任务生命周期 —— 依赖边界**须有可执行检查
 * (如 Gradle 依赖断言),而非仅靠自觉**」。此前这条一直是「❌ 未做」:边界靠人读代码确认,
 * 没有任何东西在它被破坏时变红。
 *
 * ## 为什么是「测试」而不是「Gradle 脚本断言」
 *
 * 条件 ② 的原话举例是 Gradle 依赖断言,那需要改 `domain/build.gradle.kts` —— 该文件在本阶段的
 * 禁改清单里(阶段收口前冻结,改动需单独授权)。改放 `domain/src/test` 有额外好处:
 * 它随 **`:domain:test`** 每次四道闸门一起跑,是**持续**守卫,而不是一次性的手工断言。
 *
 * ## 钉住四层(四层互相独立,任何一层被破坏都会变红)
 *
 *  1. **运行期**:Android/AndroidX 的类**不可加载** —— `Class.forName` 逐个探测;
 *  2. **类路径**:测试运行时类路径上**没有** Android 构件 —— 从 Gradle 缓存路径解析坐标组名;
 *  3. **编译期源码**:`domain/src/main` 的**非注释**代码里没有 `import android.*` / `androidx.*`,
 *     也没有反向依赖 app 层包(`com.pixfold.d1.ui|data|nav`);
 *  4. **构建配置**:`build.gradle.kts` 没有把本模块声明成 Android 模块。
 *
 * 第 1、2 层合起来严格强于「main 的运行期类路径干净」:Gradle 的
 * `testRuntimeClasspath ⊇ main runtimeClasspath`,故测试侧无 Android 构件 ⇒ main 侧也没有。
 *
 * ## 已知射程限制(诚实登记,勿据此认为万无一失)
 *
 * 「往 `domain/build.gradle.kts` 里加一个 Android 依赖」是**唯一能编译通过**的越界路径
 * (真正的 `import android.*` 连编译都过不去:android.jar 不在编译类路径上,见台账里
 * 那次实证)。
 *
 * **2026-10-05 已做真实注入**(§7.1 步骤 ③ 变异验证):临时给 `domain/build.gradle.kts` 加上
 * `implementation("androidx.annotation:annotation-jvm:1.9.1")` 后重跑,第 1 层与第 4 层**变红**,
 * 0 逃逸;测完立即从备份还原(sha256 比对一致)。**该次注入还查出本类第 2 层曾是假绿**
 * (Windows 类路径双反斜杠导致组名解析成空串 —— 详见 `normalizeSlashes` 的注释),
 * 修复后第 2 层同样能变红。修复前的「全绿」不代表边界成立,这正是「一个不会变红的检查没有价值」
 * 的实例。
 *
 * 残留限制:第 3 层(源码扫描)是**文本级**判据,理论上可被 `import` 之外的写法绕过
 * (如全限定名书写);不过那种写法要过编译仍需 android.jar 在编译类路径上,而第 2 层已钉住类路径。
 *
 * ## 防假绿(与 `EmojiFreeSourceTest` 同规矩)
 *
 * 「扫描全绿」可能只是**扫描器瞎了**,故本类含一条独立自检:合成的越界样本必须被判定为越界,
 * 且扫描范围非空(路径写错时 `walkTopDown` 会安静地返回空集)。
 *
 * ⚠️ 注释与前缀:源码里 12 处散文式提到「Android」(如 `TextKey.kt` 的
 * 「`domain/` 是纯 Kotlin JVM 模块(无 Android `Context`)」)是**记录设计意图**,不是依赖,
 * 故扫描前必须把注释挖空;挖空**必须保留换行**,否则失败信息会指错行。
 */
class AdapterBoundaryTest {

    // ------------------------------------------------------------ 第 1 层:运行期不可加载

    /**
     * 这些类必须**加载不到**。
     *
     * 挑样本的原则:覆盖平台层四类职责各自最可能被误引的类型,以及 `androidx.annotation`
     * 这类「看起来无害、纯 Java 注解」的诱饵 —— 它正是最容易被顺手加进来的那一个。
     */
    @Test
    fun android_classes_are_not_loadable_from_the_domain_module() {
        val samples = listOf(
            "android.content.Context", // 资源访问
            "android.net.Uri", // SAF 授权
            "android.graphics.Bitmap", // 缩略图
            "android.os.Build", // 常被顺手拿来判版本
            "androidx.annotation.Keep", // 诱饵:纯注解,最易被误认为"不算 Android 依赖"
            "androidx.compose.runtime.Composable", // UI 层
        )
        val leaked = samples.filter { isLoadable(it) }
        assertEquals(
            emptyList(),
            leaked,
            buildString {
                appendLine("Android/AndroidX 的类出现在 domain 模块的测试运行时类路径上:")
                leaked.forEach { appendLine("  $it") }
                appendLine("退出条件 ② 要求 domain/ 零 Android 依赖;平台能力应由平台层实现并注入。")
                appendLine("若确有正当需要,应先改本测试与 HANGOFF.md 的边界记录,而不是顺手加依赖。")
            },
        )
    }

    // ------------------------------------------------------------ 第 2 层:类路径无 Android 构件

    @Test
    fun no_android_artifacts_on_the_domain_test_classpath() {
        val entries = classpathArtifacts()
        val offenders = entries.filter { isAndroidArtifact(it) }
        assertEquals(
            emptyList(),
            offenders,
            buildString {
                appendLine("domain 模块的测试运行时类路径上有 Android 构件:")
                offenders.forEach { appendLine("  $it") }
                appendLine("测试类路径 ⊇ main 运行期类路径,故它非空即意味着 main 侧同样越界。")
            },
        )
        assertTrue(
            entries.size >= 5,
            "只解析出 ${entries.size} 个类路径条目 ⇒ 解析逻辑大概没生效,上一条断言是假绿",
        )
    }

    // ------------------------------------------------------------ 第 3 层:源码非注释部分

    /**
     * 源码层扫描。同时拦两个方向的越界:
     *  - **向外**:`import android.*` / `androidx.*`(平台依赖);
     *  - **向上**:`com.pixfold.d1.{ui,data,nav}`(反向依赖 app 层 —— 领域层是被依赖方,不得反过来依赖上层)。
     */
    @Test
    fun no_android_or_app_layer_imports_in_domain_sources() {
        val root = mainKotlinRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0
        mainKotlinFiles().forEach { file ->
            scanned++
            val relative = file.relativeTo(root).path
            maskBlockComments(file.readText()).split('\n').forEachIndexed { i, rawLine ->
                val code = stripLineComment(rawLine)
                if (FORBIDDEN_REFERENCE.containsMatchIn(code)) {
                    offenders += "$relative:${i + 1}  ${code.trim().take(72)}"
                }
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            buildString {
                appendLine("domain 源码的非注释部分引用了平台/上层:")
                offenders.forEach { appendLine("  $it") }
                appendLine("平台能力(SAF/缩略图/权限/任务生命周期)由平台层实现;领域层只持有纯 Kotlin 类型。")
                appendLine("注释里提到 Android 是允许的(记录设计意图),本测试已把注释挖空。")
            },
        )
        assertTrue(
            scanned >= 30,
            "只扫到 $scanned 个 Kotlin 文件 ⇒ 路径大概写错了,上一条断言会变成假绿",
        )
    }

    // ------------------------------------------------------------ 第 4 层:构建配置

    @Test
    fun domain_module_is_not_an_android_module() {
        val script = File("build.gradle.kts")
        assertTrue(
            script.isFile,
            "找不到 ${script.absolutePath}(cwd=${File(".").absolutePath})⇒ 本测试无法证明构建配置",
        )
        val violations = androidBuildScriptViolations(script.readText())
        assertEquals(
            emptyList(),
            violations,
            buildString {
                appendLine("domain 模块的构建脚本声明了 Android:")
                violations.forEach { appendLine("  $it") }
                appendLine("本模块必须是 kotlin(\"jvm\") 模块;改成 Android 模块即等于取消适配层边界。")
            },
        )
    }

    // ------------------------------------------------------------ 自检(反假绿)

    /**
     * 检测器自检:合成样本必须被判定为违例,否则「扫描全绿」毫无意义。
     *
     * 这也是第 2/4 层在 `domain/build.gradle.kts` 冻结期间的替代证据(见类 KDoc 的射程限制)。
     */
    @Test
    fun self_check_the_detectors_flag_synthetic_violations() {
        // 第 3 层
        assertTrue(
            FORBIDDEN_REFERENCE.containsMatchIn("import android.content.Context"),
            "检测器漏掉 `import android.content.Context`",
        )
        assertTrue(
            FORBIDDEN_REFERENCE.containsMatchIn("import androidx.annotation.Keep"),
            "检测器漏掉 `import androidx.annotation.Keep`",
        )
        assertTrue(
            FORBIDDEN_REFERENCE.containsMatchIn("val u: android.net.Uri? = null"),
            "检测器漏掉全限定名形式的 `android.net.Uri`",
        )
        assertTrue(
            FORBIDDEN_REFERENCE.containsMatchIn("import com.pixfold.d1.ui.text.PixFoldText"),
            "检测器漏掉反向依赖 app 层",
        )
        // 不得误伤:纯 Kotlin 领域代码、以及 manifest 属性这种「冒号不是点」的写法
        listOf(
            "import com.pixfold.d1.domain.model.Metadata",
            "import java.util.zip.ZipOutputStream",
            "import kotlin.test.Test",
            "val attr = \"android:enableOnBackInvokedCallback\"",
        ).forEach {
            assertTrue(
                !FORBIDDEN_REFERENCE.containsMatchIn(it),
                "把合法代码「$it」误判成越界 ⇒ 会出假红",
            )
        }

        // 第 2 层:Gradle 缓存路径里的坐标组名必须解析得出来
        val syntheticCache =
            "C:\\Users\\x\\.gradle\\caches\\modules-2\\files-2.1\\" +
                "androidx.annotation\\annotation\\1.7.0\\abc123\\annotation-1.7.0.jar"
        assertTrue(
            isAndroidArtifact(syntheticCache),
            "从 Gradle 缓存路径解析不出 androidx 坐标 ⇒ 第 2 层是假绿",
        )
        // ⚠️ 回归样本:Gradle 实际交给 test worker 的路径里反斜杠是**成对**的。
        // 这一条对应 2026-10-05 那次真实注入查出的假绿 —— 少了它,同样的缺陷会再悄悄回来。
        assertTrue(
            isAndroidArtifact(
                "C:\\\\Users\\\\x\\\\.gradle\\\\caches\\\\modules-2\\\\files-2.1\\\\" +
                    "androidx.annotation\\\\annotation-jvm\\\\1.9.1\\\\abc\\\\annotation-jvm-1.9.1.jar",
            ),
            "双反斜杠的 Gradle 路径解析不出 androidx 坐标 ⇒ 真实注入时会假绿(已发生过一次)",
        )
        assertTrue(
            !isAndroidArtifact(
                "C:\\\\Users\\\\x\\\\.gradle\\\\caches\\\\modules-2\\\\files-2.1\\\\" +
                    "org.jetbrains.kotlin\\\\kotlin-stdlib\\\\2.4.20\\\\abc\\\\kotlin-stdlib-2.4.20.jar",
            ),
            "把双反斜杠的 kotlin-stdlib 误判成 Android 构件 ⇒ 会出假红",
        )
        assertTrue(
            !isAndroidArtifact(
                "C:\\Users\\x\\.gradle\\caches\\modules-2\\files-2.1\\" +
                    "org.jetbrains.kotlin\\kotlin-stdlib\\2.4.20\\abc\\kotlin-stdlib-2.4.20.jar",
            ),
            "把 kotlin-stdlib 误判成 Android 构件 ⇒ 会出假红",
        )
        // 兜底判据(不依赖 `files-2.1` 标记)也必须生效
        assertTrue(
            isAndroidArtifact("C:\\Users\\x\\.gradle\\caches\\8.0\\androidx.core\\core-1.0.jar"),
            "Gradle 缓存布局变化时兜底判据失效 ⇒ 第 2 层会假绿",
        )

        // 第 4 层
        assertTrue(
            androidBuildScriptViolations("plugins { id(\"com.android.library\") }").isNotEmpty(),
            "检测器漏掉 `com.android.library` 插件 ⇒ 第 4 层是假绿",
        )
        assertTrue(
            androidBuildScriptViolations("dependencies { implementation(\"androidx.annotation:annotation:1.7.0\") }")
                .isNotEmpty(),
            "检测器漏掉 androidx 依赖坐标 ⇒ 第 4 层是假绿",
        )
        assertTrue(
            androidBuildScriptViolations("plugins { alias(libs.plugins.kotlin.jvm) }").isEmpty(),
            "把纯 Kotlin JVM 构建脚本误判成 Android 模块 ⇒ 会出假红",
        )
    }

    // ------------------------------------------------------------ 实现

    /** 转成空格但**保留换行** —— 行号必须与真实文件一致。 */
    private fun blank(comment: String): String =
        comment.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private fun maskBlockComments(src: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { blank(it.value) }

    /**
     * 剥掉行尾 `//` 注释,但不碰字符串字面量里的 `//`(如 `"https://…"`)。
     * 与 `EmojiFreeSourceTest` 同法:逐字符扫,靠未转义的 `"` 切换内外。
     */
    private fun stripLineComment(line: String): String {
        var inString = false
        var escaped = false
        for (i in 0 until line.length - 1) {
            val c = line[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '/' && line[i + 1] == '/' -> return line.substring(0, i)
            }
        }
        return line
    }

    /** 测试进程的 cwd 是 `pixfold-d1/domain`(Gradle `Test` 任务的工作目录),故路径是 `src/main/kotlin`。 */
    private fun mainKotlinRoot(): File {
        val f = File("src/main/kotlin")
        assertTrue(
            f.isDirectory,
            "Kotlin 源码目录不存在: ${f.absolutePath}(cwd=${File(".").absolutePath})",
        )
        return f
    }

    private fun mainKotlinFiles(): List<File> =
        mainKotlinRoot().walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun isLoadable(fqn: String): Boolean =
        try {
            Class.forName(fqn, false, javaClass.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            // 能找到但链接失败,同样算"在类路径上"
            true
        }

    /**
     * 测试运行时类路径的条目。
     *
     * 不用 `java.class.path` 之外的推断:Gradle 的 test worker 把它设成了完整的
     * `testRuntimeClasspath`(含项目自己的 `build/classes/kotlin/` 与 worker jar),这正是我们要查的那一份。
     */
    private fun classpathArtifacts(): List<String> =
        (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }

    /**
     * 反斜杠转正斜杠,并把**重复分隔符折叠成一个**。
     *
     * ⚠️ 折叠这一步不是洁癖,是本测试 2026-10-05 那次真实注入查出来的缺陷:
     * Gradle 把 `java.class.path` 交给 test worker 时,Windows 路径里的 `\` 是**成对**出现的
     * (`C:\\Users\\…\\files-2.1\\androidx.annotation\\…`)。只做 `\` → `/` 会得到
     * `…//files-2.1//androidx.annotation//…`,而标记串 `/files-2.1/` 恰好只匹配到
     * `//files-2.1//` 的**后半**,于是 `substringAfter(marker)` 以 `/` 开头、
     * `substringBefore('/')` 解析出**空串**,组名判空 ⇒ 判定为「非 Android 构件」。
     * 结果:类路径上明明躺着 `annotation-jvm-1.9.1.jar`,第 2 层却**绿灯通过**(假绿)。
     */
    private fun normalizeSlashes(entry: String): String =
        entry.replace('\\', '/').replace(Regex("/{2,}"), "/")

    /**
     * 判断一个类路径条目是否来自 Android/AndroidX。
     *
     * 看的是 **Gradle 缓存里的坐标组名**(`…/files-2.1/<group>/<module>/<version>/<hash>/<file>.jar`),
     * 而不是整条路径 —— 否则用户名或工程目录里出现 `android` 就会假红。
     */
    private fun isAndroidArtifact(entry: String): Boolean {
        val normalized = normalizeSlashes(entry)
        // 主判据:`files-2.1` 布局
        val marker = "/files-2.1/"
        if (normalized.contains(marker)) {
            val group = normalized.substringAfter(marker).substringBefore('/')
            if (isAndroidGroup(group)) return true
        }
        // 兜底:缓存根之后的任意一段是 Android/AndroidX 坐标组名。
        // 不依赖 `files-2.1` 这个标记,故 Gradle 缓存布局变化时依然有效(两条判据互为备份)。
        val cacheRoot = "/.gradle/caches/"
        if (normalized.contains(cacheRoot)) {
            val segments = normalized.substringAfter(cacheRoot).split('/')
            if (segments.any { it.isNotBlank() && isAndroidGroup(it) }) return true
        }
        val name = normalized.substringAfterLast('/')
        return name.startsWith("android-") || name.startsWith("androidx-") ||
            name.startsWith("androidx.") || name == "android.jar"
    }

    private fun isAndroidGroup(group: String): Boolean =
        group == "android" || group.startsWith("android.") ||
            group == "androidx" || group.startsWith("androidx.")

    /** 构建脚本里出现这些即认为本模块成了 Android 模块(或引了 Android 依赖)。 */
    private fun androidBuildScriptViolations(script: String): List<String> {
        val masked = maskBlockComments(script).split('\n').joinToString("\n") { stripLineComment(it) }
        val violations = mutableListOf<String>()
        val patterns = listOf(
            "com.android" to "声明了 com.android.* 插件",
            "androidx." to "引用了 androidx.* 依赖",
            "android.jar" to "引用了 android.jar",
        )
        patterns.forEach { (needle, why) ->
            if (masked.contains(needle)) violations += "$why(命中「$needle」)"
        }
        if (Regex("""(?m)^\s*(android|androidx)\s*\{""").containsMatchIn(masked)) {
            violations += "出现 android { } 配置块"
        }
        return violations
    }

    private companion object {
        /**
         * 非注释代码里不得出现的引用。
         *
         * `android.` / `androidx.` 都要求**点后跟标识符**,故 manifest 属性那种
         * `"android:enableOnBackInvokedCallback"`(冒号)不会被误伤。
         */
        private val FORBIDDEN_REFERENCE = Regex(
            """(^|\s)(import\s+)?(android|androidx)\.[A-Za-z_]""" + "|" +
                """(^|\s)import\s+com\.pixfold\.d1\.(ui|data|nav)\.""",
        )
    }
}
