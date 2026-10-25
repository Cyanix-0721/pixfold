package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SafTreeStore] 的持久化断言。
 *
 * 为什么单独测存储层而不只测控制器:上限 8、旧 key 迁移、JSON 损坏回退这三件事
 * **没有任何界面能自证** —— 存进去 9 条、读出来 9 条，UI 截断成 8 条看起来一切正常,
 * 而存储里到底有几条没人说得清。故上限与剔除规则钉在这里。
 *
 * ## 测试里写死 key 名
 * [SafTreeStore] 的 companion 是 `private`,测试无法引用常量。这与
 * `SharedPrefsSettingsStoreTest` 同一口径:顺手锁住存储格式,防止有人悄悄改 key 名
 * (改了会让所有老用户的工作区一次性丢失,而其它测试不会发现)。
 *
 * ## 存储顺序与 seed 的关系(读断言前必看)
 * `remember` 把条目**插到最前**,所以 `seed(8)` 按 w1…w8 依次添加后,存储顺序是
 * `[w8, w7, …, w1]` —— **尾部是 w1**,也就是最久未用的那条。下面凡涉及"挤掉尾部"
 * 的用例都按这个事实写,不要凭 seed 的书写顺序去推断存储顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafTreeStoreTest {

    private lateinit var context: Context
    private lateinit var store: SafTreeStore

    private companion object {
        const val PREFS_NAME = "pixfold_workspace"
        const val KEY_LIST = "workspaces_v2"
        const val KEY_LEGACY_URI = "tree_uri"
        const val KEY_LEGACY_NAME = "tree_display_name"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 在同一 JVM 里复用 Application,不清的话前一个用例写下的偏好
        // 会被后一个读到 —— 前例见 SharedPrefsSettingsStoreTest 顶部注释。
        prefs().edit().clear().commit()
        store = SafTreeStore(context)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun handle(name: String, displayName: String = name) =
        WorkspaceHandle(treeUri = "content://tree/$name", displayName = displayName)

    /** 依次 remember w1…w8 ⇒ 存储顺序变成 [w8, w7, …, w1]。 */
    private fun seed(count: Int): List<WorkspaceHandle> =
        (1..count).map { handle("w$it").also { store.remember(it) } }

    private fun names() = store.loadAll().map { it.handle.displayName }

    // ---- T1:上限 8,第 9 条挤掉尾部(最久未用) ----

    @Test
    fun `存满 8 条后第 9 条挤掉尾部那条`() {
        seed(8)
        val result = store.remember(handle("new"))

        assertEquals(RememberResult.Added, result)
        assertEquals(WORKSPACE_LIST_LIMIT, names().size)
        assertEquals("new", names().first(), "新加的必须排第一")
        assertTrue(names().contains("w8"), "刚刚用过的 w8 必须还在")
        assertFalse(names().contains("w1"), "尾部的 w1(最久未用)必须被挤掉")
    }

    @Test
    fun `上限之外的第十条同样只保留 8 条`() {
        seed(8)
        store.remember(handle("ninth"))
        store.remember(handle("tenth"))

        assertEquals(WORKSPACE_LIST_LIMIT, names().size)
        assertEquals(listOf("tenth", "ninth", "w8", "w7", "w6", "w5", "w4", "w3"), names())
    }

    // ---- T2:全钉住时拒绝新增,列表逐字不变 ----

    @Test
    fun `8 条全钉住时新增被拒`() {
        val seeded = seed(8)
        seeded.forEach { store.setPinned(it.treeUri, true) }
        val before = store.loadAll()

        val result = store.remember(handle("new"))

        assertEquals(RememberResult.RejectedAllPinned, result)
        assertEquals(before, store.loadAll(), "拒绝时列表一个字都不能改")
    }

    @Test
    fun `只钉住尾部那条时第 9 条仍能加进来`() {
        seed(8)
        // 存储尾部是 w1(见类注释)。剔除循环必须跳过它,落点变成它前面的 w2。
        store.setPinned("content://tree/w1", true)

        assertEquals(RememberResult.Added, store.remember(handle("new")))
        assertEquals(WORKSPACE_LIST_LIMIT, names().size)
        assertTrue(names().contains("w1"), "钉住的那条不能被挤掉")
        assertFalse(names().contains("w2"), "被挤掉的应该是它前面那条未钉住的")
    }

    @Test
    fun `剔除按尾部往前找第一条未钉住的`() {
        seed(5)
        // 存储 [w5, w4, w3, w2, w1];钉住尾部两条 ⇒ 落点应是 w3。
        store.setPinned("content://tree/w1", true)
        store.setPinned("content://tree/w2", true)
        // 再补满到 8 条,顺序变为 [w8, w7, w6, w5, w4, w3, w2, w1]。
        store.remember(handle("w6"))
        store.remember(handle("w7"))
        store.remember(handle("w8"))

        store.remember(handle("new"))

        assertEquals(
            listOf("new", "w8", "w7", "w6", "w5", "w4", "w2", "w1"),
            names(),
        )
    }

    // ---- T3:重复添加走 MovedToFront,保留 pinned,不增条数 ----

    @Test
    fun `重复添加会移到最前并保留图钉`() {
        seed(3)
        store.setPinned("content://tree/w1", true)

        val result = store.remember(handle("w1"))

        assertEquals(RememberResult.MovedToFront, result)
        val all = store.loadAll()
        assertEquals(3, all.size, "重复添加不得长出第 4 条")
        assertEquals("w1", all.first().handle.displayName)
        assertTrue(all.first().pinned, "重复添加不得丢掉图钉")
    }

    @Test
    fun `重复添加会更新显示名`() {
        seed(2)
        store.remember(handle("w1", displayName = "改了名"))

        val all = store.loadAll()
        assertEquals(2, all.size)
        assertEquals("改了名", all.first().handle.displayName)
        assertEquals("content://tree/w1", all.first().handle.treeUri, "treeUri 不变")
    }

    @Test
    fun `已满且已有该条时重复添加不触发剔除`() {
        seed(8)
        val result = store.remember(handle("w1"))

        assertEquals(RememberResult.MovedToFront, result)
        assertEquals(8, names().size)
        assertEquals("w1", names().first())
    }

    // ---- T4:旧 key 迁移 ----

    @Test
    fun `只有旧 key 时迁移出一条`() {
        prefs().edit()
            .putString(KEY_LEGACY_URI, "content://tree/legacy")
            .putString(KEY_LEGACY_NAME, "旧目录")
            .apply()

        val all = store.loadAll()

        assertEquals(1, all.size)
        assertEquals("content://tree/legacy", all.single().handle.treeUri)
        assertEquals("旧目录", all.single().handle.displayName)
        assertFalse(all.single().pinned)
    }

    @Test
    fun `迁移后旧 key 被删掉`() {
        prefs().edit().putString(KEY_LEGACY_URI, "content://tree/legacy").apply()

        store.loadAll()

        assertFalse(prefs().contains(KEY_LEGACY_URI), "迁移成功就必须删旧 key,否则下次又迁一遍")
        assertFalse(prefs().contains(KEY_LEGACY_NAME))
    }

    @Test
    fun `迁移结果真的落盘了而不是只在内存里`() {
        prefs().edit()
            .putString(KEY_LEGACY_URI, "content://tree/legacy")
            .putString(KEY_LEGACY_NAME, "旧目录")
            .apply()
        store.loadAll()

        // 新实例读同一份 prefs:旧 key 已删,只能从新 key 拿到。
        val reopened = SafTreeStore(context).loadAll()
        assertEquals(listOf("旧目录"), reopened.map { it.handle.displayName })
    }

    @Test
    fun `没有旧 key 时返回空列表`() {
        assertEquals(emptyList(), store.loadAll())
    }

    @Test
    fun `旧 key 只有 URI 没有名字时名字为空串`() {
        prefs().edit().putString(KEY_LEGACY_URI, "content://tree/legacy").apply()

        assertEquals("", store.loadAll().single().handle.displayName)
    }

    // ---- T5:JSON 损坏 / 字段缺失都不抛 ----

    @Test
    fun `JSON 损坏时返回空列表且不抛`() {
        prefs().edit().putString(KEY_LIST, "{ 这不是 JSON").apply()

        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun `JSON 是空串时返回空列表`() {
        prefs().edit().putString(KEY_LIST, "").apply()

        assertEquals(emptyList(), store.loadAll())
    }

    @Test
    fun `pinned 字段缺失时按 false 处理`() {
        // 旧版本写的数据没有这个字段(见 StoredEntry.pinned 的默认值)。
        prefs().edit()
            .putString(
                KEY_LIST,
                """[{"treeUri":"content://tree/a","displayName":"甲"},""" +
                    """{"treeUri":"content://tree/b","displayName":"乙"}]""",
            )
            .apply()

        val all = store.loadAll()

        assertEquals(listOf("甲", "乙"), all.map { it.handle.displayName })
        assertEquals(listOf(false, false), all.map { it.pinned })
    }

    @Test
    fun `损坏的数据不会在写入时被当作有效内容合并`() {
        prefs().edit().putString(KEY_LIST, "不是 JSON").apply()

        assertEquals(RememberResult.Added, store.remember(handle("w1")))
        assertEquals(listOf("w1"), names())
    }

    // ---- 图钉 ----

    @Test
    fun `切换图钉只改一条`() {
        seed(3) // 存储 [w3, w2, w1]
        store.setPinned("content://tree/w2", true)

        assertEquals(listOf(false, true, false), store.loadAll().map { it.pinned })
        assertEquals(listOf("w3", "w2", "w1"), names(), "改图钉不得移动存储位置")
    }

    @Test
    fun `取消图钉`() {
        seed(2)
        store.setPinned("content://tree/w2", true)
        store.setPinned("content://tree/w2", false)

        assertEquals(listOf(false, false), store.loadAll().map { it.pinned })
    }

    @Test
    fun `对不存在的 URI 设图钉无事发生`() {
        seed(2)
        val before = store.loadAll()
        store.setPinned("content://tree/不存在", true)
        assertEquals(before, store.loadAll())
    }

    // ---- 拖拽(纯下标) ----

    @Test
    fun `拖拽提交后顺序变了`() {
        seed(3) // 存储 [w3, w2, w1]
        store.move(0, 2)

        assertEquals(listOf("w2", "w1", "w3"), names(), "第 0 条搬到末尾")
    }

    @Test
    fun `往前拖同样按删后插`() {
        seed(3) // 存储 [w3, w2, w1]
        store.move(2, 0)

        assertEquals(listOf("w1", "w3", "w2"), names(), "末尾那条搬到最前")
    }

    @Test
    fun `越界下标被钳到合法范围`() {
        seed(3)
        store.move(0, 99)

        assertEquals(listOf("w2", "w1", "w3"), names())
    }

    @Test
    fun `from 越界时无事发生`() {
        seed(3)
        val before = store.loadAll()
        store.move(99, 0)
        store.move(-1, 0)
        assertEquals(before, store.loadAll())
    }

    @Test
    fun `move 保留图钉`() {
        seed(2)
        store.setPinned("content://tree/w1", true)
        store.move(1, 0) // 存储 [w2, w1] ⇒ [w1, w2]

        assertEquals(listOf("w1", "w2"), names())
        assertEquals(listOf(true, false), store.loadAll().map { it.pinned })
    }

    // ---- 移除 ----

    @Test
    fun `移除一条不影响其他`() {
        seed(3) // 存储 [w3, w2, w1]
        store.forget("content://tree/w2")

        assertEquals(listOf("w3", "w1"), names())
    }

    @Test
    fun `移除不存在的 URI 无事发生`() {
        seed(2)
        val before = store.loadAll()
        store.forget("content://tree/不存在")
        assertEquals(before, store.loadAll())
    }

    @Test
    fun `移除后能再添加回来`() {
        seed(8)
        store.forget("content://tree/w1")
        assertEquals(RememberResult.Added, store.remember(handle("w1")))

        assertEquals(8, names().size)
        assertEquals("w1", names().first())
    }

    @Test
    fun `空列表上的各操作都不抛`() {
        assertEquals(RememberResult.Added, store.remember(handle("only")))
        store.move(0, 0)
        store.forget("content://tree/only")
        assertEquals(emptyList(), store.loadAll())
    }

    // ---- 落盘(不是只在内存里) ----

    @Test
    fun `写入后新实例能读回同样的列表`() {
        seed(3)
        store.setPinned("content://tree/w2", true)

        val reopened = SafTreeStore(context)
        assertEquals(store.loadAll(), reopened.loadAll())
        assertEquals(
            listOf(
                WorkspaceEntry(handle("w3"), pinned = false),
                WorkspaceEntry(handle("w2"), pinned = true),
                WorkspaceEntry(handle("w1"), pinned = false),
            ),
            reopened.loadAll(),
        )
    }
}
