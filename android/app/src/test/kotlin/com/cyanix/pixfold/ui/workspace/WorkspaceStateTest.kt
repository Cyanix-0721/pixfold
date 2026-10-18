package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.FailureCause
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

/**
 * 授权失效恢复策略单测（D2b 退出条件 ③）。
 *
 * 这一层的价值全在**「什么时候该重试、什么时候该打扰用户」**这个判断上：
 * 判错了两种方向都很难受 ——
 * - 太激进：冷启动必弹一次「授权已失效」，用户以为自己的目录坏了；
 * - 太保守：真的撤销了还一直自动重试，界面永远转圈。
 */
class WorkspaceRecoveryPolicyTest {

    @Test
    fun `Denied 归为临时性失效而不是确定性撤销`() {
        // 平台层分不清「真撤销」与「冷启动尚未就绪」，故必须按临时性处理。
        assertEquals(FailureCause.Transient, LoadResult.Denied.failureCause)
    }

    @Test
    fun `Failed 归为 IO 失效`() {
        assertEquals(FailureCause.Io, LoadResult.Failed("boom").failureCause)
    }

    @Test
    fun `成功没有失效成因`() {
        assertEquals(null, LoadResult.Success(emptyList()).failureCause)
    }

    @Test
    fun `临时性失效第一次失败允许自动重试`() {
        assertTrue(shouldAutoRetry(FailureCause.Transient, retriedTimes = 0))
    }

    @Test
    fun `临时性失效重试一次后不再重试`() {
        // 无限重试会留下永远转圈的界面。
        assertFalse(shouldAutoRetry(FailureCause.Transient, retriedTimes = AUTO_RETRY_LIMIT))
    }

    @Test
    fun `确定性撤销一次都不重试`() {
        // 撤销后重试一百次也不会成功，只会让用户多等。
        assertFalse(shouldAutoRetry(FailureCause.Revoked, retriedTimes = 0))
    }

    @Test
    fun `IO 失效同样给一次重试机会`() {
        assertTrue(shouldAutoRetry(FailureCause.Io, retriedTimes = 0))
        assertFalse(shouldAutoRetry(FailureCause.Io, retriedTimes = AUTO_RETRY_LIMIT))
    }

    @Test
    fun `失效状态携带重试次数以便文案如实告知`() {
        // 「已重试过仍失败」与「一次没试就放弃」对用户是两种信号。
        assertEquals(0, WorkspaceState.Denied(handle = null).autoRetried)
        assertEquals(1, WorkspaceState.Denied(handle = null, autoRetried = 1).autoRetried)
    }

    @Test
    fun `自动重试上限是一次`() {
        // 这个数字是产品决策，改动须有真机依据（冷启动实测一次内恢复）。
        assertEquals(1, AUTO_RETRY_LIMIT)
    }
}
