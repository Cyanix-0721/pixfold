package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.CasePolicy
import com.pixfold.d1.domain.model.ExtPolicy
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.ProposalWarningType
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.TOO_LONG_THRESHOLD
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 命名求值单测(规格 §12.1 的 L7;并覆盖 §6.1/§6.2 的每条分支)。
 *
 * 这些用例钉死"命名结构各策略可调"(验收第 8 项)的语义。
 */
class NamingEvalTest {

    private fun item(
        id: String,
        dir: String = "",
        base: String = "img",
        ext: String = "jpg",
    ) = SourceItem(id, "c1", dir, base, ext, 1024, 0, 0, 0)

    private fun scheme(
        components: List<NameComponent> = NamingScheme.DEFAULT_COMPONENTS,
        rootDirName: String = "根",
        indexStart: Int = 1,
        indexPadding: Int = 3,
        extPolicy: ExtPolicy = ExtPolicy.Lower,
        sanitize: Boolean = true,
        replacement: String = "_",
    ) = NamingScheme(rootDirName, components, indexStart, indexPadding, extPolicy, sanitize, replacement)

    // ---- L7:默认结构拼接 ----

    @Test
    fun `default scheme produces root underscore padded index and lowercased extension`() {
        val order = listOf(item("a", base = "IMG_1", ext = "JPG"))
        val p = buildProposals(order, scheme())[0]
        assertEquals("根_001.jpg", p.proposedName, "默认应为 <根目录名>_<三位序号>.<小写扩展名>")
    }

    @Test
    fun `first output component does not emit its separator`() {
        // 第一个组件 separatorBefore 即使是 "XXX" 也不得出现
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir, separatorBefore = "SHOULD_NOT_APPEAR"),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals("根_001.jpg", p.proposedName, "首个组件不得输出分隔符")
    }

    // ---- §6.1 步 4:空组件整体跳过(连分隔符一起丢) ----

    @Test
    fun `empty component is dropped together with its separator`() {
        // RelDir 在根目录下为空 -> 应整体跳过,不留 "__" 之类多余分隔符
        val comps = listOf(
            NameComponent(NameComponentKind.RelDir, separatorBefore = "_"),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val p = buildProposals(listOf(item("a", dir = "")), scheme(components = comps))[0]
        assertEquals("001.jpg", p.proposedName, "空目录名组件应连分隔符一起丢弃")
    }

    @Test
    fun `empty custom text is dropped together with its separator`() {
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir, separatorBefore = ""),
            NameComponent(NameComponentKind.CustomText, separatorBefore = "-", text = ""),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals("根_001.jpg", p.proposedName, "空文本组件应连分隔符一起丢弃")
    }

    @Test
    fun `separator of a following component is kept when previous is non-empty`() {
        // 反向守护:不能把"跳过分隔符"做过头,非空组件之间的分隔符必须保留
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir, separatorBefore = "X"),
            NameComponent(NameComponentKind.RelDir, separatorBefore = "-"),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val p = buildProposals(listOf(item("a", dir = "day1")), scheme(components = comps))[0]
        assertEquals("根-day1_001.jpg", p.proposedName)
    }

    // ---- 组件取值 ----

    @Test
    fun `dirIndex is assigned by directory name order and survives reordering`() {
        // dirs 字典序 = [a, b] -> "a"=1, "b"=2
        val inB = item("a", dir = "b")
        val inA = item("b", dir = "a")
        val comps = listOf(NameComponent(NameComponentKind.DirIndex))

        // 页序 [inB, inA]:b->2, a->1
        val fwd = buildProposals(listOf(inB, inA), scheme(components = comps)).map { it.proposedName }
        assertContentEquals(listOf("002.jpg", "001.jpg"), fwd)

        // 换序后**目录编号不变**(§6.1 步 1 的关键性质:编号取自目录名,与页序无关)
        val rev = buildProposals(listOf(inA, inB), scheme(components = comps)).map { it.proposedName }
        assertContentEquals(listOf("001.jpg", "002.jpg"), rev)
    }

    @Test
    fun `imageIndex uses page position not original order`() {
        val comps = listOf(NameComponent(NameComponentKind.ImageIndex))
        val order = listOf(item("x", base = "x"), item("y", base = "y"), item("z", base = "z"))
        val names = buildProposals(order, scheme(components = comps)).map { it.proposedName }
        assertContentEquals(listOf("001.jpg", "002.jpg", "003.jpg"), names)
        // 传入不同顺序 -> 序号跟随位置
        val flipped = buildProposals(order.reversed(), scheme(components = comps)).map { it.proposedName }
        assertContentEquals(listOf("001.jpg", "002.jpg", "003.jpg"), flipped)
    }

    @Test
    fun `indexStart and indexPadding are honored`() {
        val comps = listOf(NameComponent(NameComponentKind.ImageIndex))
        val p = buildProposals(
            listOf(item("a"), item("b")),
            scheme(components = comps, indexStart = 5, indexPadding = 2),
        )
        assertEquals("05.jpg", p[0].proposedName)
        assertEquals("06.jpg", p[1].proposedName)
    }

    @Test
    fun `origName uses baseName without extension`() {
        val p = buildProposals(
            listOf(item("a", base = "hello", ext = "PNG")),
            scheme(components = listOf(NameComponent(NameComponentKind.OrigName))),
        )[0]
        assertEquals("hello.png", p.proposedName, "OrigName 应取 baseName 再按策略加扩展名")
    }

    @Test
    fun `customText and prefix emit their text`() {
        val comps = listOf(
            NameComponent(NameComponentKind.Prefix, separatorBefore = "", text = "PRE"),
            NameComponent(NameComponentKind.CustomText, separatorBefore = "-", text = "MID"),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals("PRE-MID_001.jpg", p.proposedName)
    }

    // ---- §6.2 后处理:先 casePolicy,再 trimSpaces ----

    @Test
    fun `casePolicy is applied per component`() {
        val comps = listOf(
            NameComponent(NameComponentKind.Prefix, separatorBefore = "", text = "AbC", casePolicy = CasePolicy.Lower),
            NameComponent(NameComponentKind.CustomText, separatorBefore = "_", text = "dEf", casePolicy = CasePolicy.Upper),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals("abc_DEF.jpg", p.proposedName)
    }

    @Test
    fun `trimSpaces removes surrounding whitespace only`() {
        val comps = listOf(
            NameComponent(NameComponentKind.Prefix, separatorBefore = "", text = "  a b  ", trimSpaces = true),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals("a b.jpg", p.proposedName, "trim 只去首尾,不动内部空格")
    }

    @Test
    fun `trimSpaces disabled keeps whitespace`() {
        val comps = listOf(
            NameComponent(NameComponentKind.Prefix, separatorBefore = "", text = " x ", trimSpaces = false),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals(" x .jpg", p.proposedName)
    }

    @Test
    fun `component that becomes empty after trimming is dropped`() {
        // "   " trim 后为空 -> 整体跳过(连分隔符)
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir, separatorBefore = ""),
            NameComponent(NameComponentKind.CustomText, separatorBefore = "-", text = "   "),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val p = buildProposals(listOf(item("a")), scheme(components = comps))[0]
        assertEquals("根_001.jpg", p.proposedName, "trim 后为空也应整体跳过")
    }

    // ---- 扩展名策略 ----

    @Test
    fun `extPolicy keep preserves original case`() {
        val p = buildProposals(
            listOf(item("a", ext = "JPeG")),
            scheme(components = listOf(NameComponent(NameComponentKind.RootDir)), extPolicy = ExtPolicy.Keep),
        )[0]
        assertEquals("根.JPeG", p.proposedName)
    }

    @Test
    fun `extPolicy upper uppercases extension`() {
        val p = buildProposals(
            listOf(item("a", ext = "png")),
            scheme(components = listOf(NameComponent(NameComponentKind.RootDir)), extPolicy = ExtPolicy.Upper),
        )[0]
        assertEquals("根.PNG", p.proposedName)
    }

    @Test
    fun `extPolicy strip removes the dot entirely`() {
        val p = buildProposals(
            listOf(item("a", ext = "png")),
            scheme(components = listOf(NameComponent(NameComponentKind.RootDir)), extPolicy = ExtPolicy.Strip),
        )[0]
        assertEquals("根", p.proposedName, "Strip 应完全不追加扩展名(连点也不留)")
    }

    // ---- §6.1 步 7:清洗作用于完整文件名(含扩展名) ----

    @Test
    fun `sanitize applies to the whole filename including extension`() {
        val p = buildProposals(
            listOf(item("a", base = "ok", ext = "pn:g")),
            scheme(
                components = listOf(NameComponent(NameComponentKind.OrigName)),
                extPolicy = ExtPolicy.Keep,
                sanitize = true,
            ),
        )[0]
        assertEquals("ok.pn_g", p.proposedName, "扩展名内的非法字符也要被清洗")
    }

    @Test
    fun `sanitize replaces every illegal char with replacement`() {
        val p = buildProposals(
            listOf(item("a", base = "a:b*c?d")),
            scheme(
                components = listOf(NameComponent(NameComponentKind.OrigName)),
                sanitize = true,
                replacement = "-",
            ),
        )[0]
        assertEquals("a-b-c-d.jpg", p.proposedName)
    }

    // ---- 覆盖(§6.3):独立于 scheme,改结构不丢失 ----

    @Test
    fun `override wins over proposed name`() {
        val p = buildProposals(
            listOf(item("a")),
            scheme(),
            overrides = mapOf("a" to "自定义名字.jpg"),
        )[0]
        assertEquals("自定义名字.jpg", p.finalName)
        assertTrue(p.isOverridden)
    }

    @Test
    fun `changing scheme does not disturb overridden proposals`() {
        // 这是验收第 9 项的核心语义:改结构后覆盖不丢失
        val order = listOf(item("a"), item("b"))
        val overrides = mapOf("a" to "保留我.jpg")

        val before = buildProposals(order, scheme(), overrides = overrides)
        val after = buildProposals(
            order,
            scheme(components = listOf(NameComponent(NameComponentKind.ImageIndex)), indexPadding = 5),
            overrides = overrides,
        )

        assertEquals("保留我.jpg", before.first { it.image.id == "a" }.finalName)
        assertEquals("保留我.jpg", after.first { it.image.id == "a" }.finalName, "改结构后覆盖必须逐字不变")
        // 未被覆盖项应随结构变化:b 在页序下标 1 -> 默认结构 002;5 位补零 00002
        assertEquals("根_002.jpg", before.first { it.image.id == "b" }.finalName)
        assertEquals("00002.jpg", after.first { it.image.id == "b" }.finalName)
    }

    // ---- override 纯函数 ----

    @Test
    fun `setOverride with null or empty removes the override`() {
        val s0 = NamingState(scheme(), mapOf("a" to "x.jpg"))
        assertFalse(setOverride(s0, "a", null).overrides.containsKey("a"))
        assertFalse(setOverride(s0, "a", "").overrides.containsKey("a"), "清空输入框 = 撤销覆盖,不是改成空名")
        assertEquals("y.jpg", setOverride(s0, "a", "y.jpg").overrides["a"])
    }

    @Test
    fun `updateScheme keeps overrides intact`() {
        val s0 = NamingState(scheme(), mapOf("a" to "keep.jpg"))
        val s1 = updateScheme(s0, scheme(indexPadding = 4))
        assertEquals("keep.jpg", s1.overrides["a"], "改 scheme 不得影响 overrides(存储位置分离)")
    }

    @Test
    fun `addComponent appends and removeComponent keeps at least one`() {
        val s0 = NamingState(scheme())
        val n0 = s0.scheme.components.size
        val s1 = addComponent(s0, NameComponentKind.CustomText, text = "T")
        assertEquals(n0 + 1, s1.scheme.components.size)

        var s2 = NamingState(scheme(components = listOf(NameComponent(NameComponentKind.RootDir))))
        s2 = removeComponent(s2, 0)
        assertEquals(1, s2.scheme.components.size, "至少保留 1 个组件")
    }

    @Test
    fun `toggleComponent flips enabled`() {
        val s0 = NamingState(scheme(components = listOf(NameComponent(NameComponentKind.RootDir, enabled = true))))
        assertFalse(toggleComponent(s0, 0).scheme.components[0].enabled)
        // 越界安全
        assertEquals(s0, toggleComponent(s0, 9))
    }

    @Test
    fun `moveComponent reorders and clamps at both ends`() {
        // 验收第 8 项要求"顺序可调"
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir),
            NameComponent(NameComponentKind.ImageIndex),
            NameComponent(NameComponentKind.OrigName, enabled = false),
        )
        val s0 = NamingState(scheme(components = comps))

        // 下移第 0 个 -> [Index, Root, Orig]
        val down = moveComponent(s0, 0, 1)
        assertContentEquals(
            listOf(NameComponentKind.ImageIndex, NameComponentKind.RootDir, NameComponentKind.OrigName),
            down.scheme.components.map { it.kind },
        )
        // 上移回来
        val back = moveComponent(down, 1, -1)
        assertContentEquals(comps.map { it.kind }, back.scheme.components.map { it.kind })

        // 越界:顶部再上移 / 底部再下移 -> 原样返回(不循环)
        assertEquals(s0, moveComponent(s0, 0, -1), "顶部上移应无变化")
        assertEquals(s0, moveComponent(s0, comps.size - 1, 1), "底部下移应无变化")
        assertEquals(s0, moveComponent(s0, 9, 1), "越界下标应无变化")
    }

    @Test
    fun `component order really changes the produced name`() {
        // 证明"顺序可调"不只是数据结构变了,而是**输出真的变**
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir, separatorBefore = ""),
            NameComponent(NameComponentKind.ImageIndex, separatorBefore = "_"),
        )
        val s0 = NamingState(scheme(components = comps))
        assertEquals("根_001.jpg", buildProposals(listOf(item("a")), s0.scheme)[0].proposedName)

        val swapped = moveComponent(s0, 0, 1)
        // 注意:**分隔符属于各自组件**,不是"位置"。交换后
        // ImageIndex(sep="_" 默认)成为首个输出组件 -> 不出分隔符 -> "001";
        // RootDir(sep="")在后 -> "001" + "" + "根" = "001根"。
        assertEquals("001根.jpg", buildProposals(listOf(item("a")), swapped.scheme)[0].proposedName)
    }

    @Test
    fun `dirIndexMap override wins over computed`() {
        val comps = listOf(NameComponent(NameComponentKind.DirIndex))
        val order = listOf(item("a", dir = "a"), item("b", dir = "b"))
        val names = buildProposals(order, scheme(components = comps), dirIndexMap = mapOf("a" to 9))
            .map { it.proposedName }
        assertContentEquals(listOf("009.jpg", "002.jpg"), names)
    }

    @Test
    fun `too long warning is not a conflict and does not skip`() {
        val comps = listOf(NameComponent(NameComponentKind.OrigName))
        val long = "n".repeat(TOO_LONG_THRESHOLD + 5)
        val p = buildProposals(listOf(item("a", base = long)), scheme(components = comps))[0]
        assertTrue(p.warnings.any { it.type == ProposalWarningType.TooLong })
        assertFalse(p.hasConflict, "TooLong 仅警告,不得导致跳过")
        assertEquals(1, buildRenamePlan(listOf(p)).count { !it.isSkipped })
    }

    @Test
    fun `proposals keep the caller's order`() {
        val order = listOf(item("c"), item("a"), item("b"))
        val ids = buildProposals(order, scheme()).map { it.image.id }
        assertContentEquals(listOf("c", "a", "b"), ids, "不得重排入参顺序")
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(buildProposals(emptyList(), scheme()).isEmpty())
        assertTrue(buildRenamePlan(emptyList()).isEmpty())
        assertNull(buildRenamePlan(emptyList()).firstOrNull())
    }
}
