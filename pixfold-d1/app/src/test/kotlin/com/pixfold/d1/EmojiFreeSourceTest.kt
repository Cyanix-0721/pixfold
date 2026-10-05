package com.pixfold.d1

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 用户可见文案里不得再出现 emoji —— 第⑥项「警告图标改用矢量图」的源码侧守卫
 * (2026-10-05 用户裁决:「警告图标你直接用的emoji不好看,没有更好看的矢量图吗」)。
 *
 * ## 为什么只能靠「扫源码」守护
 *
 * 原先的写法有两处:资源字符串里写死 emoji 前缀(`⚠️ …`、`⛔ %1$s`),
 * 以及 `PlanComponents.kt:229` 那句 `text = "⛔ $body"`。这类字符:
 *  1. 不参与任何 `testTag`,语义树里查不到,UI 测试碰不到;
 *  2. 中英各一份,**漏改一份没有任何测试会红** —— `TranslationCoverageTest` 只比
 *     占位符的个数/类型/顺序与「两语言渲染是否相同」,不比「有没有 emoji」;
 *  3. 真机上只有翻到那一页才看得见。
 *
 * 于是它和 `LauncherIconTest` 守护的启动器图标属于同一类:**"坏掉也全绿"的资源**。
 * 唯一能在离线阶段证伪的场合就是源码层,故本类直接读文件。
 *
 * ## 钉住四件事
 *  1. `res/` 下的 XML 与 `kotlin/` 的**非注释**部分不得出现 emoji;
 *  2. 去除 emoji 后只剩裸占位符的两个键(`plan_skip_reason_prefix`、`a_plan_skip_reason`)
 *     必须**整体删除**,而不是留一个空壳键 —— 留着就等于把「符号前缀」换个地方藏;
 *  3. 检测器本身认得被替换掉的两种前缀(否则"扫描全绿"可能只是检测器瞎了);
 *  4. 扫描范围非空(路径写错时 `listFiles` 返回 null,断言会**假绿**)。
 *
 * ⚠️ 注释**不在**射程内:资源文件头部与代码注释里留着 emoji 是为了记录「这里原先是什么」,
 * 用户看不到。故扫描前必须把注释挖空 —— 而挖空**必须保留换行**,否则失败信息会指错行
 * (本轮实测:整段删除注释后把 `:114` 报成了 `:93`)。
 *
 * ⚠️ 测试源码不在射程内(`app/src/test` 里有 `★`/`✔` 这类只用于失败信息的分隔符)。
 * 本类只扫 `app/src/main` —— 那才是会进 APK 的一侧。
 *
 * ⚠️ 工作目录是 `pixfold-d1/app`(Gradle 测试进程的 cwd,已实测),故路径是 `src/main/…`。
 */
class EmojiFreeSourceTest {

    // ------------------------------------------------------------ 主断言

    @Test
    fun no_emoji_in_resource_text() {
        val res = scanResources()
        assertEquals(
            emptyList(),
            res.hits,
            buildString {
                appendLine("资源文案里还有 emoji(第⑥项已裁决改用矢量图):")
                res.hits.forEach { appendLine("  $it") }
                appendLine("改法:删掉字符串开头的 emoji 前缀,警示语义交给 Icon(painterResource(...));")
                appendLine("若某个键去掉前缀后只剩裸占位符(如「⛔ %1\$s」),该键应整体删除、直接渲染正文。")
            },
        )
    }

    /**
     * 与资源侧**分开**成两条:合在一起时,资源那条先失败就会短路掉 Kotlin 扫描
     * —— `PlanComponents.kt` 里那句 `"⛔ $body"` 会一直没被验证过(本轮实测踩到)。
     */
    @Test
    fun no_emoji_in_kotlin_sources() {
        val kt = scanKotlin()
        assertEquals(
            emptyList(),
            kt.hits,
            buildString {
                appendLine("Kotlin 源码的非注释部分还有 emoji:")
                kt.hits.forEach { appendLine("  $it") }
                appendLine("改法:把字面量里的 emoji 换成 Icon(painterResource(...));")
                appendLine("注释里保留 emoji 是允许的(记录历史),本测试已把注释挖空。")
            },
        )
    }

    @Test
    fun degenerate_symbol_prefix_keys_are_deleted_not_kept_as_empty_shells() {
        val names = listOf("plan_skip_reason_prefix", "a_plan_skip_reason")
        val keyPattern = Regex("""name="(${names.joinToString("|")})"""")
        val offenders = mutableListOf<String>()

        // 注释要挖空:注释里完全可能**提到**这两个键名(删键时要连注释一起改),
        // 那是记录历史,不是"键还在"。
        valuesXml().forEach { (dir, file) ->
            keyPattern.find(maskXmlComments(file.readText()))?.let {
                offenders += "res/${dir.name}/${file.name} 仍有键: ${it.groupValues[1]}"
            }
        }
        val ktRoot = mainKotlinRoot()
        mainKotlinFiles().forEach { file ->
            val code = maskBlockComments(file.readText())
                .split('\n').joinToString("\n") { stripLineComment(it) }
            names.forEach { n ->
                if (code.contains("R.string.$n"))
                    offenders += "kotlin/${file.relativeTo(ktRoot).path} 仍引用 R.string.$n"
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "去掉 emoji 后这两个键只剩一个裸占位符,应当**删除**并直接渲染正文,而不是留空壳:" +
                "留着只会把「符号前缀」换个地方藏,而 `⛔` 的警示语义要由图标承担。",
        )
    }

    // ------------------------------------------------------------ 自检(反假绿)

    @Test
    fun the_scan_actually_reaches_the_sources() {
        val res = scanResources()
        val kt = scanKotlin()
        assertTrue(
            res.files >= 20,
            "只扫到 ${res.files} 个资源 XML ⇒ 路径大概写错了,第一条断言会变成假绿",
        )
        assertTrue(
            res.stringTags >= 300,
            "只扫到 ${res.stringTags} 个 <string>/<item> ⇒ 注释掩码可能把正文也吃掉了",
        )
        assertTrue(
            kt.files >= 30,
            "只扫到 ${kt.files} 个 Kotlin 文件 ⇒ 路径大概写错了",
        )
    }

    @Test
    fun the_detector_knows_the_two_prefixes_it_replaced() {
        assertTrue(
            emojiIn(WARNING_PREFIX).isNotEmpty(),
            "检测器不认识原先的警告前缀 ⇒ 扫描再全也是假绿",
        )
        assertTrue(
            emojiIn(FORBIDDEN_PREFIX).isNotEmpty(),
            "检测器不认识原先的禁止前缀 ⇒ 扫描再全也是假绿",
        )
        listOf("→", "·", "abc", "%1\$s").forEach { sample ->
            assertEquals("", emojiIn(sample), "把非 emoji 的「$sample」误判成 emoji ⇒ 会出假红")
        }
    }

    @Test
    fun the_maskers_hide_comments_but_keep_the_code_around_them() {
        val masked = maskXmlComments("<!-- $WARNING_PREFIX 注释 -->\n<string name=\"x\">$WARNING_PREFIX 正文</string>\n")
        assertFalse(masked.contains("注释"), "XML 注释没被挖空 ⇒ 注释里的 emoji 会算成命中")
        assertTrue(masked.contains("正文"), "掩码把正文也吃掉了 ⇒ 扫描变假绿")
        assertEquals(3, masked.split('\n').size, "掩码改变了行数 ⇒ 失败信息会指错行")

        assertEquals(
            "val u = \"https://a/b\" ",
            stripLineComment("val u = \"https://a/b\" // 说明"),
            "行注释剥离把字符串里的 // 当成了注释 ⇒ 该行后半段的 emoji 会漏检",
        )
        assertEquals(
            "val s = \"// 不是注释\"",
            stripLineComment("val s = \"// 不是注释\""),
            "字符串里的 // 被当成了行注释",
        )
    }

    // ------------------------------------------------------------ 扫描实现

    private data class Scan(val files: Int, val stringTags: Int, val hits: List<String>)

    private fun scanResources(): Scan {
        val hits = mutableListOf<String>()
        var stringTags = 0
        // 文案条数只从 values* 数,用来给"扫描器真的读到了正文"当分母。
        valuesXml().forEach { (_, file) ->
            val lines = maskXmlComments(file.readText()).split('\n')
            stringTags += lines.count { it.contains("<string ") || it.contains("<item ") }
        }
        // 命中判定扫 `res/` 下**所有** XML,不止 values*:drawable 等正文同样会进 APK。
        val all = resXmlFiles()
        all.forEach { (relative, file) ->
            maskXmlComments(file.readText()).split('\n').forEachIndexed { i, line ->
                val bad = emojiIn(line)
                // 行号从 1 数起,与编辑器/失败信息一致
                if (bad.isNotEmpty()) hits += "res/$relative:${i + 1}  $bad  ${line.trim().take(72)}"
            }
        }
        return Scan(all.size, stringTags, hits)
    }

    private fun scanKotlin(): Scan {
        val root = mainKotlinRoot()
        val hits = mutableListOf<String>()
        var files = 0
        mainKotlinFiles().forEach { file ->
            files++
            val relative = file.relativeTo(root).path
            maskBlockComments(file.readText()).split('\n').forEachIndexed { i, line ->
                val code = stripLineComment(line)
                val bad = emojiIn(code)
                if (bad.isNotEmpty()) hits += "$relative:${i + 1}  $bad  ${code.trim().take(72)}"
            }
        }
        return Scan(files, 0, hits)
    }

    // ------------------------------------------------------------ 注释掩码

    /** 用空格替换注释内容,但**保留换行** —— 行号必须与真实文件一致。 */
    private fun blank(comment: String): String =
        comment.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private fun maskXmlComments(src: String): String =
        Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(src) { blank(it.value) }

    private fun maskBlockComments(src: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { blank(it.value) }

    /**
     * 剥掉行尾 `//` 注释,但**不**碰字符串字面量里的 `//`(如 `"https://…"`)。
     *
     * 逐字符扫一遍即可:进/出字符串靠未转义的 `"` 切换。若图省事按「行首是 `//` 就整行丢掉」,
     * 字符串里带 URL 的那一行后半段的 emoji 就会漏检(本仓库确实有 `https://` 字面量)。
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

    // ------------------------------------------------------------ emoji 判定

    /** 返回该行出现的 emoji 字符(去重、按码点升序),按码点比较而非字形,免得受编码影响。 */
    private fun emojiIn(text: String): String {
        val found = sortedSetOf<Int>()
        var i = 0
        while (i < text.length) {
            val hi = text[i].code
            // 手写代理对合并:不依赖 `String.codePointAt` 这类平台扩展,免得换个 JDK 就编译不过。
            val cp: Int
            if (hi in 0xD800..0xDBFF && i + 1 < text.length) {
                val lo = text[i + 1].code
                if (lo in 0xDC00..0xDFFF) {
                    cp = 0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
                    i += 2
                } else {
                    cp = hi
                    i += 1
                }
            } else {
                cp = hi
                i += 1
            }
            if (isEmojiCodePoint(cp)) found += cp
        }
        return found.joinToString("") { codePointToString(it) }
    }

    /** 码点还原成字符(含辅助平面),同样手写,不用 `Character.toChars`。 */
    private fun codePointToString(cp: Int): String =
        if (cp < 0x10000) {
            cp.toChar().toString()
        } else {
            val v = cp - 0x10000
            String(charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()))
        }

    /**
     * emoji 覆盖区:主符号区 + 杂项符号区 + 几个散落的组合/标点码点。
     *
     * 取「区段」而非列清单,是为了让**新加的** emoji 也拦得住 —— 枚举清单挡不住没料到的那一个。
     * 代价是 `★`(0x2605)、`✔`(0x2714) 也算 emoji;这两类只出现在测试注释里(`app/src/test` 不在射程)。
     * `→`(0x2192)、`·`(0x00B7) 不在区段内,它们是排版符号而非 emoji,不受本测试约束。
     */
    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in EMOJI_EXTRAS

    // ------------------------------------------------------------ 文件定位

    private fun resRoot(): File {
        val f = File("src/main/res")
        assertTrue(f.isDirectory, "资源目录不存在: ${f.absolutePath}(cwd=${File(".").absolutePath})")
        return f
    }

    private fun mainKotlinRoot(): File {
        val f = File("src/main/kotlin")
        assertTrue(f.isDirectory, "Kotlin 源码目录不存在: ${f.absolutePath}(cwd=${File(".").absolutePath})")
        return f
    }

    /** `values` / `values-night` / `values-zh` … 全部默认槽位目录。 */
    private fun valuesDirs(): List<File> =
        resRoot().listFiles { f: File -> f.isDirectory && f.name.startsWith("values") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun valuesXml(): List<Pair<File, File>> = valuesDirs().flatMap { dir ->
        (dir.listFiles { f: File -> f.extension == "xml" }?.sortedBy { it.name } ?: emptyList())
            .map { dir to it }
    }

    /** `res/` 下所有 XML,返回 (相对 `res/` 的路径, 文件) —— 失败信息里要能直接拿去 grep。 */
    private fun resXmlFiles(): List<Pair<String, File>> {
        val root = resRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .sortedBy { it.path }
            .map { it.relativeTo(root).path to it }
            .toList()
    }

    private fun mainKotlinFiles(): List<File> =
        mainKotlinRoot().walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList()

    private companion object {
        /**
         * 原先字面写在资源里的两种前缀。**用码点写**,不让本文件自身的编码问题
         * 把「检测器自检」变成假绿。
         */
        private val WARNING_PREFIX = "\u26A0\uFE0F" // ⚠️
        private val FORBIDDEN_PREFIX = "\u26D4" // ⛔

        private val EMOJI_EXTRAS = setOf(0xFE0F, 0x20E3, 0x2B50, 0x2B55, 0x2049, 0x203C)
    }
}
