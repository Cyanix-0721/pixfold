package com.cyanix.pixfold.domain.source

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem

/**
 * 一个已授权的工作区（D2a）。
 *
 * @param treeUri SAF 树 URI 字符串。只有平台层知道怎么用它，领域层只透传。
 * @param displayName 给用户看的目录名（首页从属层 / 步骤 0 显示）。
 */
data class WorkspaceHandle(
    val treeUri: String,
    val displayName: String,
)

/**
 * 变更观察句柄。
 *
 * **为什么需要句柄而不是只有回调**：监听是平台资源的占用（`ContentObserver`、
 * 文件监视器、轮询定时器……），不显式释放就会在工作区切换后继续回调旧目录的变更，
 * 既泄漏又会让界面显示上一个目录的内容。[close] 必须幂等 —— 界面层的
 * `DisposableEffect` / 控制器清理路径都可能调用它。
 */
fun interface WorkspaceChangeWatcher {

    /** 停止监听并释放平台资源。重复调用必须无副作用。 */
    fun close()
}

/**
 * 平台侧只读来源端口（D2a）。
 *
 * **为什么是接口**：`domain/` 必须零 Android 依赖（`AdapterBoundaryTest` 四层强制），
 * 而真实实现绕不开 `ContentResolver` / `DocumentsContract`。范式与
 * `SettingsStore`（domain 定接口 + `InMemorySettingsStore` 测试实现 + app 层
 * `SharedPrefsSettingsStore`）一致。
 *
 * **线程契约**：实现方负责阻塞 IO，**调用方负责线程** —— 所以方法都是普通阻塞函数，
 * 不是 `suspend`（domain 不引协程依赖）。app 层调用时自己包 `withContext(Dispatchers.IO)`。
 *
 * **异常契约**：授权失效时实现方**不要**抛 `SecurityException` 穿透到 UI，
 * 而应返回 [LoadResult.Denied]（见下）。其余 IO 异常一律转成
 * [LoadResult.Failed]，由 UI 决定怎么提示。
 *
 * **为什么没有 `lastWorkspace()`**：列表世界里「上次的」= [workspaces] 的第一条
 * （remember 会把最近用过的移到最前），调用点写 `workspaces().firstOrNull()?.handle`。
 * **不留双语义**：本项目已经吃过一次「两套语义并存」的亏 —— `WorkspaceState.canOrganize`
 * 在 `None`/`Denied`/`Failed` 上都返 `true`，与首页真正要问的问题相反，
 * 2026-10-24 只能另写一个 `canStartWorkflow` 绕开。凡是能从一个来源推导的，
 * 就不再提供第二个入口。
 */
interface WorkspaceSource {

    /** 全部已记住的工作区，顺序即显示顺序（图钉只影响渲染，不影响这里）。 */
    fun workspaces(): List<WorkspaceEntry>

    /**
     * 记住一个工作区并把它移到最前。
     *
     * 已存在（同一 treeUri）⇒ 更新显示名 + 移到最前 + **保留 pinned**。
     * 已满上限且全部钉住 ⇒ 列表一个字都不改，返回 [RememberResult.RejectedAllPinned]。
     */
    fun rememberWorkspace(handle: WorkspaceHandle): RememberResult

    /** 只从列表移除这一条；不动磁盘、不动系统授权。 */
    fun forgetWorkspace(treeUri: String)

    /** 切换图钉。treeUri 不存在时无事发生。 */
    fun setPinned(treeUri: String, pinned: Boolean)

    /** 拖拽提交：把 from 这一条移到下标 to。越界时钳到合法范围。 */
    fun moveWorkspace(from: Int, to: Int)

    /**
     * 当前**全部**仍持有系统持久授权的 treeUri。
     *
     * **只有平台层能回答** —— domain 层不得 `import android.*`（`AdapterBoundaryTest` 每次闸门都跑）。
     *
     * ## 为什么是「批量」而不是「单条」
     *
     * 真实实现在 Android 上要问 `ContentResolver.persistedUriPermissions`，那是**跨进程**
     * 调用。列表上限 8 条时逐条问就是 8 次 Binder —— 而它们读的是**同一份**系统状态。
     * 一次拿全集再在内存里比，语义一字不差，代价从 N 次压到 1 次。
     *
     * **这里是唯一真相源**：单条版本 [isPersistentGrant] 是默认实现、由它推导，
     * 实现方只实现本方法。若允许两边各自实现，迟早出现「批量说有、单条说没有」的
     * 两个真相源（本项目已吃过 `canOrganize` / `canStartWorkflow` 语义并存的亏）。
     *
     * **刻意没有默认实现**（旧单条版本默认返 `true`）。默认值无论选哪边都在
     * 替实现方表态：「都在」会让假源掩盖分区逻辑，「都不在」会让界面把持着授权的目录
     * 标成「只记住了路径」。每个实现方一律写明自己那份事实 —— 编译器会点名催。
     */
    fun persistentGrants(): Set<String>

    /**
     * 这一条是否还持有系统持久授权。
     *
     * **默认实现，不要覆盖** —— 真正的来源是 [persistentGrants]（见它的 KDoc）。
     * 覆盖它就会绕过唯一真相源，两处判断迟早漂移。
     */
    fun isPersistentGrant(treeUri: String): Boolean = treeUri in persistentGrants()

    /**
     * 扫描工作区，组装成集合列表。
     *
     * 分组规则见 [collectionsFrom] —— 实现方**只负责遍历和查询**，不要自己决定
     * 「哪些算一个集合」，否则规则会漂成两份。
     */
    fun loadCollections(handle: WorkspaceHandle): LoadResult

    /** 读一个来源文件的原始字节（预览 / 打包用）。读不到返回 `null`。 */
    fun readBytes(item: SourceItem): ByteArray?

    /**
     * 监听工作区变更（用户在文件管理器里增删改图片后，希望界面自动跟上）。
     *
     * **为什么带默认实现返回 `null`**：观察能力是**可选增强**，不是端口的必需品。
     * 给默认实现，测试里的假源（`WorkspaceControllerTest.ScriptedSource` 等）与
     * 未来只读/离线的实现都不用被迫写一个空壳方法；返回 `null` 的语义明确为
     * 「这个实现不支持观察」，调用方据此静默降级到回前台重扫 / 手动刷新。
     *
     * **回调契约**：可能在任意线程回调，且**可能连续多次**（一次文件操作往往产生
     * 多个 provider 通知）—— 调用方需自行合并/去抖，不要在回调里直接重扫。
     * [onChange] 只在变更后通知，**不承诺**变更内容；重扫职责仍归
     * [loadCollections]。
     *
     * **返回值**：`null` = 不支持；非 `null` 时调用方**必须**在不再需要时
     * （换工作区 / 忘记工作区 / 界面销毁）[WorkspaceChangeWatcher.close]。
     */
    fun watchChanges(handle: WorkspaceHandle, onChange: () -> Unit): WorkspaceChangeWatcher? = null
}

/** [WorkspaceSource.loadCollections] 的结果。授权失效是**预期内**状态，不是异常。 */
sealed interface LoadResult {

    /** 扫描成功。`collections` 可能为空（目录里没有图片）。 */
    data class Success(val collections: List<ImageCollection>) : LoadResult

    /** 授权已失效（用户撤销、或授权被系统回收）。UI 应引导重选目录。 */
    data object Denied : LoadResult

    /** 其他失败（IO 错误等）。`message` 只用于日志/调试，UI 文案另定。 */
    data class Failed(val message: String) : LoadResult
}
