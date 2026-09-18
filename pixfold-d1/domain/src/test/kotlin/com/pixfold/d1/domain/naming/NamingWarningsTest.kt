package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.ProposalWarningType
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.TOO_LONG_THRESHOLD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 冲突与警告检测单测(规格 §12.1 的 L9/L10;验收第 10 项)。
 *
 * 重点钉死三件容易做错的事:
 *  1. **Duplicate 与 CaseCollision 互斥**(组内已有完全同名时不报 CaseCollision);
 *  2. **IllegalChar 只在 sanitize=false 时产生**,与清洗互补而非重复;
 *  3. **只有 Duplicate / IllegalChar 导致跳过**,CaseCollision / TooLong 仅警告。
 */
class NamingWarningsTest {

    private fun item(id: String, dir: String = "", base: String = "img", ext: String = "jpg") =
        SourceItem(id, "c1", dir, base, ext, 1024, 0, 0, 0)

    private fun scheme(
        components: List<NameComponent> = NamingScheme.DEFAULT_COMPONENTS,
        sanitize: Boolean = true,
        replacement: String = "_",
    ) = NamingScheme("根", components, 1, 3, com.pixfold.d1.domain.model.ExtPolicy.Lower, sanitize, replacement)

    private fun typesOf(p: NameProposal) = p.warnings.map { it.type }.toSet()

    /** 用 OrigName 组件直接控制 finalName,便于构造冲突样本。 */
    private val origOnly = listOf(NameComponent(NameComponentKind.OrigName))

    // ---- L9:重名跨目录被检出 ----

    @Test
    fun `duplicate names are detected across directories`() {
        // 同名 baseName 在不同目录 -> 用 OrigName 命名后撞车
        val order = listOf(
            item("a", dir = "ch01", base = "page_01"),
            item("b", dir = "ch02", base = "page_01"),
        )
        val ps = buildProposals(order, scheme(components = origOnly))
        assertTrue(ps.all { ProposalWarningType.Duplicate in typesOf(it) }, "跨目录同名必须被检出")
        assertTrue(ps.all { it.hasConflict }, "重名应导致跳过")
    }

    @Test
    fun `unique names produce no duplicate warning`() {
        val order = listOf(item("a", base = "one"), item("b", base = "two"))
        val ps = buildProposals(order, scheme(components = origOnly))
        assertTrue(ps.all { typesOf(it).isEmpty() }, "不同名不应有任何警告")
        assertTrue(ps.none { it.hasConflict })
    }

    // ---- L9:CaseCollision 与 Duplicate 互斥 ----

    @Test
    fun `case collision is reported when only case differs`() {
        val order = listOf(item("a", base = "Page_01"), item("b", base = "page_01"))
        val ps = buildProposals(order, scheme(components = origOnly))
        // 原始名不全同 -> 每项都应报 CaseCollision,且**不报** Duplicate
        ps.forEach { p ->
            assertTrue(ProposalWarningType.CaseCollision in typesOf(p), "仅大小写不同应报 CaseCollision")
            assertFalse(ProposalWarningType.Duplicate in typesOf(p), "原始名不全同则不得报 Duplicate")
        }
        assertTrue(ps.none { it.hasConflict }, "CaseCollision 仅警告,不得导致跳过")
    }

    @Test
    fun `duplicate wins over case collision when names are exactly identical`() {
        // 三项:两个完全同名 + 一个仅大小写不同
        val order = listOf(
            item("a", base = "same"),
            item("b", base = "same"),
            item("c", base = "SAME"),
        )
        val ps = buildProposals(order, scheme(components = origOnly))
        val a = ps.first { it.image.id == "a" }
        val b = ps.first { it.image.id == "b" }
        val c = ps.first { it.image.id == "c" }

        // a/b 的 finalName 都是 "same.jpg" -> 报 Duplicate
        assertTrue(ProposalWarningType.Duplicate in typesOf(a))
        assertTrue(ProposalWarningType.Duplicate in typesOf(b))
        // c 的 finalName 是 "SAME.jpg",**单独出现 1 次** -> 不报 Duplicate。
        // 其所属 lowercase 组 {same.jpg, SAME.jpg} 内存在完全同名 -> 按规格也不报 CaseCollision。
        assertFalse(
            ProposalWarningType.Duplicate in typesOf(c),
            "c 的名字只出现一次,不应报重名(判定基于完全相同的 finalName)",
        )
        ps.forEach {
            assertFalse(
                ProposalWarningType.CaseCollision in typesOf(it),
                "该 lowercase 组内已存在完全同名 -> 只报 Duplicate,不报 CaseCollision(互斥)",
            )
        }
    }

    // ---- L10:非法字符集 + sanitize 开关互补 ----

    @Test
    fun `illegal char warning appears only when sanitize is off`() {
        val order = listOf(item("a", base = "封:面?"))
        val off = buildProposals(order, scheme(components = origOnly, sanitize = false))[0]
        assertTrue(ProposalWarningType.IllegalChar in typesOf(off), "未开清洗应报非法字符")
        assertTrue(off.hasConflict, "非法字符应导致跳过")

        val on = buildProposals(order, scheme(components = origOnly, sanitize = true))[0]
        assertFalse(ProposalWarningType.IllegalChar in typesOf(on), "开了清洗不应再报 IllegalChar")
        assertEquals("封_面_.jpg", on.proposedName, "非法字符应被替换")
    }

    @Test
    fun `sanitized names can still collide producing duplicate`() {
        // 互补而非重复:清洗把不同名字压成同一个 -> 仍应报 Duplicate
        val order = listOf(item("a", base = "a:b"), item("b", base = "a?b"))
        val ps = buildProposals(order, scheme(components = origOnly, sanitize = true))
        assertTrue(ps.all { ProposalWarningType.Duplicate in typesOf(it) }, "清洗后撞名仍须由 Duplicate 兜底")
    }

    @Test
    fun `all nine illegal chars are detected`() {
        val nine = "\\/:*?\"<>|"
        assertEquals(9, nine.length, "非法字符集应为 9 个")
        nine.forEach { ch ->
            val order = listOf(item("a", base = "x${ch}y"))
            val p = buildProposals(order, scheme(components = origOnly, sanitize = false))[0]
            assertTrue(
                ProposalWarningType.IllegalChar in typesOf(p),
                "字符 '$ch' 应被判定为非法",
            )
        }
    }

    @Test
    fun `control characters below 0x20 are illegal`() {
        val order = listOf(item("a", base = "a\tb"))
        val p = buildProposals(order, scheme(components = origOnly, sanitize = false))[0]
        assertTrue(ProposalWarningType.IllegalChar in typesOf(p), "控制字符应算非法")
    }

    @Test
    fun `a control char is replaced by sanitize`() {
        val order = listOf(item("a", base = "a\tb"))
        val p = buildProposals(order, scheme(components = origOnly, sanitize = true))[0]
        assertEquals("a_b.jpg", p.proposedName)
    }

    // ---- L10:超长阈值 180 ----

    @Test
    fun `too long triggers only above 180`() {
        // 注意:阈值判的是 **finalName**(含扩展名),不是 baseName。
        // 判定式是 `length > 180`,故恰好 180 不算超长、181 才算。
        val extLen = ".jpg".length
        val exactly = "n".repeat(TOO_LONG_THRESHOLD - extLen)   // finalName 恰好 180
        val over = "n".repeat(TOO_LONG_THRESHOLD - extLen + 1)  // finalName 恰好 181

        val pOk = buildProposals(listOf(item("a", base = exactly)), scheme(components = origOnly))[0]
        assertEquals(TOO_LONG_THRESHOLD, pOk.finalName.length, "前置条件:该样本应恰好 180 字符")
        assertFalse(
            ProposalWarningType.TooLong in typesOf(pOk),
            "finalName 长度 = $TOO_LONG_THRESHOLD 时不应超长(阈值是 > 180,不是 >=)",
        )

        val pBad = buildProposals(listOf(item("b", base = over)), scheme(components = origOnly))[0]
        assertEquals(TOO_LONG_THRESHOLD + 1, pBad.finalName.length, "前置条件:该样本应为 181 字符")
        assertTrue(ProposalWarningType.TooLong in typesOf(pBad))
    }

    @Test
    fun `chinese counts as one code unit toward the limit`() {
        // 中文按 UTF-16 码元 1 计 -> 200 个汉字应超长
        val zh = "字".repeat(200)
        val p = buildProposals(listOf(item("a", base = zh)), scheme(components = origOnly))[0]
        assertTrue(ProposalWarningType.TooLong in typesOf(p))
        assertEquals(200, zh.length)
    }

    // ---- Overridden 信息性 ----

    @Test
    fun `override adds informational warning and does not skip`() {
        val order = listOf(item("a", base = "orig"))
        val p = buildProposals(order, scheme(components = origOnly), overrides = mapOf("a" to "custom.jpg"))[0]
        assertTrue(ProposalWarningType.Overridden in typesOf(p))
        assertFalse(p.hasConflict, "覆盖是信息性警告,不得导致跳过")
    }

    @Test
    fun `conflicts are judged on finalName not proposedName`() {
        // 两个不同的 proposedName,但被覆盖成同名 -> 必须报 Duplicate
        val order = listOf(item("a", base = "one"), item("b", base = "two"))
        val overrides = mapOf("a" to "same.jpg", "b" to "same.jpg")
        val ps = buildProposals(order, scheme(components = origOnly), overrides = overrides)
        assertTrue(ps.all { ProposalWarningType.Duplicate in typesOf(it) }, "判定须基于 finalName(覆盖优先)")
    }

    @Test
    fun `override can resolve a duplicate`() {
        val order = listOf(item("a", base = "dup"), item("b", base = "dup"))
        val ps = buildProposals(
            order,
            scheme(components = origOnly),
            overrides = mapOf("a" to "renamed.jpg"),
        )
        val a = ps.first { it.image.id == "a" }
        val b = ps.first { it.image.id == "b" }
        assertFalse(ProposalWarningType.Duplicate in typesOf(a), "覆盖成唯一名后 a 不再重名")
        assertFalse(
            ProposalWarningType.Duplicate in typesOf(b),
            "只剩 b 用 dup.jpg -> 该名字只出现 1 次,也不应再报重名",
        )
        assertTrue(ps.none { it.hasConflict }, "覆盖消除了整组重名(这正是逐项覆盖的用途)")
    }

    // ---- 汇总一致性:凡行内显示的警告都必须被计入(真机实测发现的缺陷) ----

    @Test
    fun `overridden warning counts as a warning`() {
        // 真机缺陷:行内显示「人工覆盖」,汇总却写「警告 0」。
        // 根因是 hasWarningOnly 用了枚举白名单,漏掉了 Overridden。
        // 判据用"取反"表达:有警告且非冲突 -> 必属警告。
        val order = listOf(item("a", base = "orig"))
        val p = buildProposals(order, scheme(components = origOnly), overrides = mapOf("a" to "x.jpg"))[0]
        assertTrue(ProposalWarningType.Overridden in typesOf(p))
        assertFalse(p.hasConflict)
        assertTrue(p.hasWarningOnly, "人工覆盖是警告,必须被 hasWarningOnly 计入")
    }

    @Test
    fun `every displayed warning is counted in exactly one bucket`() {
        // 不变量:每个提案恰好落在 冲突 / 纯警告 / 无警告 三者之一
        val order = listOf(
            item("dup1", base = "same"),
            item("dup2", base = "same"),
            item("case1", base = "Pg"),
            item("case2", base = "pg"),
            item("long", base = "n".repeat(200)),
            item("clean", base = "ok"),
        )
        val ps = buildProposals(order, scheme(components = origOnly))
        ps.forEach { p ->
            val buckets = listOf(p.hasConflict, p.hasWarningOnly, p.warnings.isEmpty()).count { it }
            assertEquals(1, buckets, "提案 ${p.image.id} 必须恰好属于一个桶(warnings=${p.warnings.map { it.type }})")
        }
        // 且"有警告"的总数 = 冲突数 + 纯警告数
        assertEquals(
            ps.count { it.hasConflict } + ps.count { it.hasWarningOnly },
            ps.count { it.warnings.isNotEmpty() },
            "冲突数 + 纯警告数 必须等于有警告的项数(否则汇总会与实际显示不一致)",
        )
    }

    @Test
    fun `override plus duplicate counts as conflict not plain warning`() {
        // 同时有冲突与人工覆盖 -> 归入冲突桶(不重复计入警告)
        val order = listOf(item("a", base = "same"), item("b", base = "same"))
        val ps = buildProposals(
            order,
            scheme(components = origOnly),
            overrides = mapOf("a" to "same.jpg"),
        )
        val a = ps.first { it.image.id == "a" }
        assertTrue(a.hasConflict, "与 b 同名 -> 冲突")
        assertFalse(a.hasWarningOnly, "已计入冲突,不得重复计入纯警告")
    }

    // ---- 计划:跳过语义 ----

    @Test
    fun `rename plan skips only duplicate and illegal char`() {
        val dup = listOf(item("a", base = "same"), item("b", base = "same"))
        val plan = buildRenamePlan(buildProposals(dup, scheme(components = origOnly)))
        assertTrue(plan.all { it.isSkipped })
        assertTrue(plan.all { it.reason != null }, "跳过项必须带 reason")

        val caseOnly = listOf(item("a", base = "Pg"), item("b", base = "pg"))
        val plan2 = buildRenamePlan(buildProposals(caseOnly, scheme(components = origOnly)))
        assertTrue(plan2.none { it.isSkipped }, "仅大小写冲突不得跳过")
        assertTrue(plan2.all { it.reason == null })
    }

    @Test
    fun `reason takes the first conflict warning message and is human readable`() {
        val dup = listOf(item("a", base = "same"), item("b", base = "same"))
        val plan = buildRenamePlan(buildProposals(dup, scheme(components = origOnly)))
        val reason = plan.first().reason!!
        assertTrue(reason.contains("重名"), "reason 取第一个冲突警告的 message,且人类可读")
    }

    @Test
    fun `clean proposals are Ok`() {
        val order = listOf(item("a", base = "a"), item("b", base = "b"))
        val plan = buildRenamePlan(buildProposals(order, scheme(components = origOnly)))
        assertTrue(plan.all { !it.isSkipped })
    }

    // ---- 默认结构下 scan 集合的实测行为 ----

    @Test
    fun `default scheme makes scan collection names unique despite duplicate baseNames`() {
        // scan 的 ch01/page_01 与 ch02/page_01 同名,但默认结构用**序号**命名 -> 不重名
        val order = listOf(
            item("p1", dir = "ch01", base = "page_01"),
            item("p2", dir = "ch02", base = "page_01"),
        )
        val ps = buildProposals(order, scheme())
        assertEquals("根_001.jpg", ps[0].proposedName)
        assertEquals("根_002.jpg", ps[1].proposedName)
        assertTrue(ps.none { it.hasConflict }, "默认结构按序号命名,跨目录同名不会撞车")
    }
}
