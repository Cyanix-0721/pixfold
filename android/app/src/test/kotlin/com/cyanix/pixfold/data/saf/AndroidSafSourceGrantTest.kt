package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AndroidSafSource.isPersistentGrant] 的两侧都要覆盖。
 *
 * **false 侧**：没有授权时必须返回 false —— 这是下分区「只记住了路径」的唯一判据。
 * 若它恒返 true，用户会看到一条挂着 ✓ 却一点就报「授权已失效」的记录，且永远不会落到下区。
 *
 * **true 侧由 `ShadowContentResolver.takePersistableUriPermission` 在同一用例里制造**：
 * Robolectric 4.17 的 `ShadowContentResolver` 完整实现了 take / release /
 * getPersistedUriPermissions（take ⇒ 按 URI 去重合并 flag ⇒ 加入静态 uriPermissions 列表），
 * 所以先 take 再查必然非空，**不需要真实 DocumentsProvider** —— 那是系统授权表，
 * 与 provider 是否实现 query 无关。原先「true 侧只能真机」的结论不成立，已撤回。
 *
 * 只有 `isPersistentGrant` 的 **catch 分支**（查询本身抛异常时按无授权处理）属真机项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidSafSourceGrantTest {

    @Test
    fun `没有系统授权时返回 false`() {
        val source = AndroidSafSource(ApplicationProvider.getApplicationContext())
        assertFalse(source.isPersistentGrant("content://tree/never-granted"))
    }

    /**
     * 列表已满 [WORKSPACE_LIST_LIMIT] 条且**全部钉住**时，[SafTreeStore.remember] 一个字都不改，
     * [AndroidSafSource.rememberWorkspace] 必须就此返回、**不去 take 持久授权**。
     *
     * 反过来（先 take 再记列表）会在系统里留一条谁都不会再引用的持久授权：列表里没有对应条目，
     * 应用内没有任何入口能枚举或删除它，用户只能进系统设置手动清 —— 这就是孤儿授权。
     */
    @Test
    fun `列表满且全钉住时不提升持久授权`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SafTreeStore(ctx)
        repeat(WORKSPACE_LIST_LIMIT) { i ->
            store.remember(WorkspaceHandle("content://tree/p$i", "p$i"))
            store.setPinned("content://tree/p$i", true)
        }
        val source = AndroidSafSource(ctx)
        val fresh = WorkspaceHandle("content://tree/rejected", "被拒")

        assertEquals(RememberResult.RejectedAllPinned, source.rememberWorkspace(fresh))
        assertFalse(
            source.isPersistentGrant(fresh.treeUri),
            "被拒的目录不得留下孤儿持久授权 —— 列表里没有它，用户再也删不掉",
        )
        assertEquals(WORKSPACE_LIST_LIMIT, source.workspaces().size, "列表一个字都不该变")
    }

    /** 正常路径：记住成功后必须同时持有持久授权，否则下次冷启动会误判成「需重新授权」。 */
    @Test
    fun `正常记住时会提升持久授权`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val source = AndroidSafSource(ctx)
        val handle = WorkspaceHandle("content://tree/ok", "ok")

        source.rememberWorkspace(handle)

        assertTrue(source.isPersistentGrant(handle.treeUri))
    }
}
