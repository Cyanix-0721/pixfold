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
 */
interface WorkspaceSource {

    /** 上次授权过的工作区；`null` = 从未选过，或用户清过数据。 */
    fun lastWorkspace(): WorkspaceHandle?

    /** 记住一个刚授权的工作区，供下次冷启动恢复。 */
    fun rememberWorkspace(handle: WorkspaceHandle)

    /** 忘掉当前工作区（用户主动「更换目录」/ 授权失效后清理）。 */
    fun forgetWorkspace()

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
