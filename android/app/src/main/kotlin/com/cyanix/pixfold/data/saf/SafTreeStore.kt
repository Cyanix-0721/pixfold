package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.core.content.edit
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 工作区列表上限。
 *
 * **落在这里而不是 UI**(spec §5.3):[SafTreeStore] 是**唯一写入口**。把上限写在显示层
 * 会产生"读得到 9 条、看不清第 9 条"这种无法自证的状态 —— 存储里到底有几条,没人说得清。
 */
const val WORKSPACE_LIST_LIMIT: Int = 8

/**
 * 工作区列表的持久化(SharedPreferences + JSON)。
 *
 * ## 为什么从"两个标量 key"改成"一个 JSON key"
 * 旧格式只能表达一个目录;列表要有顺序、图钉、条数上限,标量 key 表达不了。
 * 换成一个 `workspaces_v2` 存整个数组,写入是**单次** `apply()` ——
 * 不会出现"名字写进去了、图钉没写"的半截状态。
 *
 * ## 为什么 Json 要 `ignoreUnknownKeys = true`
 * 默认的 kotlinx.serialization 遇到**不认识的字段**会抛 `SerializationException`，
 * 底下那道 `runCatching` 一吞就成了空列表 ⇒ 用户升级后回滚旧 APK（或将来加了新字段再回滚）
 * 时，**全部工作区静默消失**，界面只会表现成「怎么又要重选目录」。
 * 多出来的字段对旧版本没有意义，忽略它比整条丢弃正确得多；
 * 缺字段那一侧本来就由 `StoredEntry.pinned` 的默认值兜住（见其 KDoc）。
 *
 * ## 为什么反序列化失败当作空列表而不抛
 * 这段代码在**冷启动路径**上:抛异常等于用户一打开应用就崩,且无路可退。
 * 而损坏的数据本来也没有可挽救的信息 —— 空列表让用户重新选一次即可。
 *
 * ## 顺序语义只有一条
 * **存储顺序 = 显示顺序**。[pinned][WorkspaceEntry.pinned] 只在渲染时做稳定排序
 * (见 `orderedForDisplay`),**不改变存储位置** —— 所以 [setPinned] 只改标志,不搬条目。
 */
class SafTreeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全部条目,顺序即显示顺序(图钉只影响渲染,不影响这里)。 */
    fun loadAll(): List<WorkspaceEntry> {
        val raw = prefs.getString(KEY_LIST, null)
        if (raw.isNullOrEmpty()) return migratedFromLegacy()
        val decoded = runCatching {
            json.decodeFromString<List<StoredEntry>>(raw)
        }.getOrNull() ?: return emptyList()
        return decoded.map { it.toEntry() }
    }

    /**
     * 记住一个工作区并把它移到最前。
     *
     * 已存在(同一 treeUri)⇒ 更新显示名 + 移到最前 + **保留 pinned**,返回 [RememberResult.MovedToFront]。
     * 已满 [WORKSPACE_LIST_LIMIT] 且全部钉住 ⇒ 一个字都不改,返回 [RememberResult.RejectedAllPinned]。
     * 否则从**尾部**往前找第一条未钉住的剔除。
     */
    fun remember(handle: WorkspaceHandle): RememberResult {
        val current = loadAll().toMutableList()
        val existing = current.indexOfFirst { it.handle.treeUri == handle.treeUri }
        if (existing >= 0) {
            // 重复添加**不得**无条件 add:那会静默长出重复行,条数也会突破上限。
            val pinned = current[existing].pinned
            current.removeAt(existing)
            current.add(0, WorkspaceEntry(handle, pinned))
            persist(current)
            return RememberResult.MovedToFront
        }

        if (current.size >= WORKSPACE_LIST_LIMIT) {
            val victim = current.indexOfLast { !it.pinned }
            // 全钉住时**先返回、后写入**,保证拒绝路径一个字都不改。
            if (victim < 0) return RememberResult.RejectedAllPinned
            current.removeAt(victim)
        }

        current.add(0, WorkspaceEntry(handle, pinned = false))
        persist(current)
        return RememberResult.Added
    }

    /** 只从列表移除这一条;不动磁盘(目录本身)、不动系统授权。 */
    fun forget(treeUri: String) {
        val current = loadAll()
        val kept = current.filterNot { it.handle.treeUri == treeUri }
        if (kept.size != current.size) persist(kept)
    }

    /** 切换图钉。treeUri 不存在时无事发生。 */
    fun setPinned(treeUri: String, pinned: Boolean) {
        val current = loadAll()
        if (current.none { it.handle.treeUri == treeUri }) return
        persist(current.map { if (it.handle.treeUri == treeUri) it.copy(pinned = pinned) else it })
    }

    /**
     * 拖拽提交:把第 [from] 条搬到第 [to] 条的位置(**纯存储下标**)。
     *
     * 下标换算(渲染序 → 存储序、先删后插)由 domain 的 `movedOrder` 负责,这里只管搬。
     * [from] 越界时无事发生;[to] 越界时钳到合法范围。
     */
    fun move(from: Int, to: Int) {
        val current = loadAll().toMutableList()
        if (from !in current.indices) return
        val item = current.removeAt(from)
        current.add(to.coerceIn(0, current.size), item)
        persist(current)
    }

    private fun persist(entries: List<WorkspaceEntry>) {
        val text = json.encodeToString(entries.map { StoredEntry.of(it) })
        prefs.edit { putString(KEY_LIST, text) }
    }

    /**
     * 旧格式只有 `tree_uri` / `tree_display_name` 两个 key。迁移成一条 `pinned = false`。
     *
     * **顺序**:先写新 key,再删旧 key —— 反过来的话写失败就永久丢了那条工作区。
     */
    private fun migratedFromLegacy(): List<WorkspaceEntry> {
        val legacyUri = prefs.getString(KEY_LEGACY_URI, null)
        if (legacyUri.isNullOrEmpty()) return emptyList()
        val entry = WorkspaceEntry(
            WorkspaceHandle(
                treeUri = legacyUri,
                displayName = prefs.getString(KEY_LEGACY_NAME, null).orEmpty(),
            ),
            pinned = false,
        )
        persist(listOf(entry))
        prefs.edit {
            remove(KEY_LEGACY_URI)
            remove(KEY_LEGACY_NAME)
        }
        return listOf(entry)
    }

    private companion object {
        /** 唯一一处 Json 实例：读写共用，避免「写进去的」与「读出来的」用两套策略。 */
        val json = Json { ignoreUnknownKeys = true }

        const val PREFS_NAME = "pixfold_workspace"
        const val KEY_LIST = "workspaces_v2"
        const val KEY_LEGACY_URI = "tree_uri"
        const val KEY_LEGACY_NAME = "tree_display_name"
    }
}

/**
 * 落盘形态。与 [WorkspaceEntry] 分开是因为前者是**存储格式**(字段名一旦发布就不能改),
 * 而后者是**领域类型**(可以随便改)。
 *
 * [pinned] 给默认值 `false`:旧版本写的数据没有这个字段,缺省即未钉住。
 */
@Serializable
private data class StoredEntry(
    val treeUri: String,
    val displayName: String,
    val pinned: Boolean = false,
) {
    fun toEntry() = WorkspaceEntry(WorkspaceHandle(treeUri, displayName), pinned)

    companion object {
        fun of(entry: WorkspaceEntry) = StoredEntry(
            treeUri = entry.handle.treeUri,
            displayName = entry.handle.displayName,
            pinned = entry.pinned,
        )
    }
}
