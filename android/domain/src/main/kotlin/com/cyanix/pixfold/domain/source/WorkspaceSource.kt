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
