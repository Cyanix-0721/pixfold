package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [WorkspaceState] 的映射规则单测。
 *
 * 这一层刻意做成**纯函数**（不碰 Android、不碰协程）就是为了能在这里钉死：
 * 「什么情况下界面显示真实数据、什么情况下回退 mock」。D1 的 491 个测试全建立在
 * mock 上，一旦回退规则写错（例如 `Ready` 空列表也回退），用户会看到**演示数据
 * 冒充真实扫描结果** —— 这种缺陷界面全绿、测试全绿，但数据是假的。
 */
class WorkspaceStateTest {

    private val handle = WorkspaceHandle(treeUri = "content://tree/x", displayName = "照片库")

    private fun item(id: String, collectionId: String = "c1") = SourceItem(
        id = id,
        collectionId = collectionId,
        dir = "",
        baseName = id,
        ext = "jpg",
        sizeBytes = 1L,
        modifiedEpochMillis = 0L,
        createdEpochMillis = null,
        seed = 0,
    )

    private val realCollections = listOf(
        ImageCollection(id = "c1", name = "c1", rootDirName = "c1", images = listOf(item("a"))),
    )
    private val mockCollections = listOf(
        ImageCollection(id = "mock", name = "mock", rootDirName = "mock", images = listOf(item("m", "mock"))),
    )

    @Test
    fun `成功结果映射为 Ready 并带回授权`() {
        val state = LoadResult.Success(realCollections).toWorkspaceState(handle)

        val ready = assertIs<WorkspaceState.Ready>(state)
        assertEquals(handle, ready.handle)
        assertEquals(realCollections, ready.collections)
    }

    @Test
    fun `授权失效映射为 Denied 且保留授权用于文案`() {
        val state = LoadResult.Denied.toWorkspaceState(handle)

        val denied = assertIs<WorkspaceState.Denied>(state)
        // 带上 handle：界面要能说清「哪个目录失效了」，否则用户不知道该换哪个。
        assertEquals(handle, denied.handle)
    }

    @Test
    fun `其他失败映射为 Failed 并保留原因`() {
        val state = LoadResult.Failed("boom").toWorkspaceState(handle)

        assertEquals("boom", assertIs<WorkspaceState.Failed>(state).message)
    }

    @Test
    fun `只有 Ready 用真实集合 其余一律回退 mock`() {
        assertEquals(realCollections, WorkspaceState.Ready(handle, realCollections).collectionsOr(mockCollections))

        // 这四条是 D1 既有测试的生存条件：默认无授权时界面必须照旧显示 mock。
        assertEquals(mockCollections, WorkspaceState.None.collectionsOr(mockCollections))
        assertEquals(mockCollections, WorkspaceState.Loading.collectionsOr(mockCollections))
        assertEquals(mockCollections, WorkspaceState.Denied(handle).collectionsOr(mockCollections))
        assertEquals(mockCollections, WorkspaceState.Failed("x").collectionsOr(mockCollections))
    }

    @Test
    fun `Ready 但空列表不回退 mock`() {
        // 用户选了一个没有图片的目录，就该看到「没有图片」。
        // 悄悄塞回演示数据会让人以为扫到了东西 —— 这是数据正确性问题，不是体验问题。
        val empty = WorkspaceState.Ready(handle, emptyList())

        assertEquals(emptyList(), empty.collectionsOr(mockCollections))
    }

    @Test
    fun `扫描中与空目录不可开始整理`() {
        assertFalse(WorkspaceState.Loading.canOrganize)
        assertFalse(WorkspaceState.Ready(handle, emptyList()).canOrganize)
    }

    @Test
    fun `无授权或扫描失败时仍可整理 走 mock 演示`() {
        assertTrue(WorkspaceState.None.canOrganize)
        assertTrue(WorkspaceState.Denied(handle).canOrganize)
        assertTrue(WorkspaceState.Failed("x").canOrganize)
        assertTrue(WorkspaceState.Ready(handle, realCollections).canOrganize)
    }
}
