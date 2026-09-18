package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.effectiveScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "根目录名跟随集合"(用户 2026-09-18 指定)的领域语义。
 *
 * 这是 HANGOFF §2 约束 4「批次统一 + 逐项例外」在命名组件上的落法:
 *  - 默认(null 覆盖)= 跟随当前集合的 rootDirName;
 *  - 用户显式改过 = 记为逐项例外,不再跟随,且**切集合也不被冲掉**。
 */
class RootDirFollowTest {

    private fun item(id: String, dir: String = "") = SourceItem(id, "c", dir, "img", "jpg", 1, 0, 0, 0)

    private fun collection(id: String, rootDirName: String) =
        ImageCollection(id, id, rootDirName, listOf(item("$id-1")))

    @Test
    fun `by default root dir name follows the collection`() {
        val a = collection("a", "集合A根名")
        val b = collection("b", "集合B根名")
        val state = NamingState(NamingScheme(rootDirName = "初始"))

        assertEquals("集合A根名", effectiveScheme(state, a).rootDirName)
        assertEquals("集合B根名", effectiveScheme(state, b).rootDirName, "切集合后应随之变化")
    }

    @Test
    fun `an explicit root dir name is used instead of the collection`() {
        val a = collection("a", "集合A根名")
        val state = setRootDirNameOnScheme(NamingState(NamingScheme("初始")), "我定的名字")

        assertEquals("我定的名字", effectiveScheme(state, a).rootDirName)
        assertTrue(state.hasRootDirNameOverride)
    }

    @Test
    fun `an explicit root dir name survives switching collections`() {
        // "逐项例外"的关键:用户改过之后,切集合不得被默认值冲掉
        val a = collection("a", "集合A根名")
        val b = collection("b", "集合B根名")
        val state = setRootDirNameOnScheme(NamingState(NamingScheme("初始")), "我定的名字")

        assertEquals("我定的名字", effectiveScheme(state, a).rootDirName)
        assertEquals("我定的名字", effectiveScheme(state, b).rootDirName, "切集合也不得覆盖用户输入")
    }

    @Test
    fun `clearing the override resumes following the collection`() {
        val b = collection("b", "集合B根名")
        val overridden = setRootDirNameOnScheme(NamingState(NamingScheme("初始")), "我定的名字")
        assertTrue(overridden.hasRootDirNameOverride)

        val restored = clearRootDirNameOverride(overridden)
        assertFalse(restored.hasRootDirNameOverride)
        assertEquals("集合B根名", effectiveScheme(restored, b).rootDirName, "恢复后应重新跟随集合")
    }

    @Test
    fun `empty or null root dir name means follow the collection`() {
        val a = collection("a", "集合A根名")
        val s0 = setRootDirNameOnScheme(NamingState(NamingScheme("初始")), "X")
        assertNull(setRootDirName(s0, "").rootDirNameOverride, "空 -> 撤销覆盖(与 override 同一约定)")
        assertNull(setRootDirName(s0, null).rootDirNameOverride)
        assertEquals("集合A根名", effectiveScheme(setRootDirName(s0, ""), a).rootDirName)
    }

    @Test
    fun `only root dir name follows the collection - the rest stays batch level`() {
        // 组件/补零/扩展名仍是批次级(规格 §6.3);只有根目录名是逐集合的
        val a = collection("a", "A名")
        val scheme = NamingScheme(
            rootDirName = "旧名",
            components = listOf(NameComponent(NameComponentKind.ImageIndex)),
            indexPadding = 5,
        )
        val eff = effectiveScheme(NamingState(scheme), a)
        assertEquals("A名", eff.rootDirName)
        assertEquals(scheme.components, eff.components, "组件不得随集合变化")
        assertEquals(5, eff.indexPadding, "补零位数不得随集合变化")
    }

    @Test
    fun `the follow flag is independent of overrides`() {
        // 两个 override 互不干扰:改根目录名不得动 image 覆盖,反之亦然
        val s0 = NamingState(NamingScheme("初始"), overrides = mapOf("x" to "keep.jpg"))
        val s1 = setRootDirNameOnScheme(s0, "新根名")
        assertEquals("keep.jpg", s1.overrides["x"], "改根目录名不得影响逐项覆盖")

        val s2 = setOverride(s1, "y", "another.jpg")
        assertEquals("新根名", s2.rootDirNameOverride, "加逐项覆盖不得影响根目录名例外")
    }

    @Test
    fun `proposals use the effective scheme root dir name`() {
        // 端到端:buildProposals 拿到的应是"生效结构"
        val a = collection("a", "旅行照片")
        val state = NamingState(
            NamingScheme(rootDirName = "无用的占位"),
            overrides = emptyMap(),
        )
        val eff = effectiveScheme(state, a)
        val p = buildProposals(a.images, eff)[0]
        assertEquals("旅行照片_001.jpg", p.proposedName)
    }
}
