package com.cyanix.pixfold.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.reconcileLibrary
import com.cyanix.pixfold.domain.model.ComicVolume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作流 B 卷探测（`outputExists` + 已有 CBZ 的 `ComicInfo.xml`）的异步加载状态机（P0 #1）。
 *
 * ## 为什么要从 `PixFoldApp` 的 `remember(...)` 拆出来
 *
 * 原先 `PixFoldApp` 在组合期主线程同步对每卷执行 SAF `outputExists` 与 ZIP/XML 读取，
 * 存在三个缺陷：
 * 1. 即使用户停留在首页或「整理图片」，每次工作区加载都会急切阻塞 UI 主线程；
 * 2. 20–50+ 卷书库存在明确 ANR 风险；
 * 3. 工作区刷新或切换期间若改成异步，没有代数守卫会让慢返回的旧探测结果覆盖新卷。
 *
 * ## 职责边界（与 `WorkspaceController` 同构，零 Android 依赖，可纯 JVM 单测）
 *
 * - [syncBase]：组合期同步承接零 I/O 的基础卷列表（切换工作区重置；同工作区集合变更做
 *   [reconcileLibrary] 对账），同时推进代数并取消在途的过期探测；
 * - [requestProbe]：仅在进入 `LibraryRoute` 时由调用方按需触发，切到 [ioDispatcher]
 *   异步探测，回主线程前校验代数与当前快照，过期结果一律丢弃；
 * - [cancelProbe]：离开 `LibraryRoute` 时取消在途任务并清空已完成标记，确保下次再进能重探最新磁盘态。
 */
class LibraryVolumeLoader(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialVolumes: List<ComicVolume> = emptyList(),
) {

    /** 当前可供 UI 渲染的卷列表（初值为零 I/O 基础卷，探测完成后合入探测结果）。 */
    var volumes: List<ComicVolume> by mutableStateOf(initialVolumes)
        private set

    /** 是否有在途的异步卷探测任务。 */
    var isProbing: Boolean by mutableStateOf(false)
        private set

    private data class ProbeKey(
        val workspaceKey: String,
        val baseVolumes: List<ComicVolume>,
    )

    private var initialized: Boolean = false
    private var currentWorkspaceKey: String? = null
    private var currentRootPath: String = ""
    private var lastSyncedBase: List<ComicVolume> = emptyList()
    private var lastProbedVolumes: List<ComicVolume>? = null
    private var lastProbedKey: ProbeKey? = null
    private var inFlightKey: ProbeKey? = null
    private var generation: Long = 0L
    private var probeJob: Job? = null

    /**
     * 同步更新基础卷列表（零 I/O）。
     *
     * - 工作区切换（[workspaceKey] 变化）：取消在途探测并重置卷列表；
     * - 同工作区刷新（[baseVolumes] 变化）：取消在途探测并用 [reconcileLibrary] 保留人工调整；
     * - 相同快照的普通重组：直接返回，不触发额外状态写入或取消在途探测。
     */
    fun syncBase(
        workspaceKey: String?,
        rootPath: String,
        baseVolumes: List<ComicVolume>,
    ) {
        if (
            initialized &&
            currentWorkspaceKey == workspaceKey &&
            currentRootPath == rootPath &&
            lastSyncedBase == baseVolumes
        ) {
            return
        }

        val workspaceSwitched = !initialized || currentWorkspaceKey != workspaceKey
        invalidateProbe()

        currentWorkspaceKey = workspaceKey
        currentRootPath = rootPath
        lastSyncedBase = baseVolumes
        lastProbedVolumes = null
        initialized = true

        val next = if (workspaceSwitched) {
            baseVolumes
        } else {
            reconcileLibrary(
                previous = LibraryState(
                    rootPath = rootPath,
                    volumes = volumes,
                    activeVolumeId = "",
                ),
                scanned = baseVolumes,
            ).volumes
        }
        if (next != volumes) {
            volumes = next
        }
    }

    /** 记录用户对卷列表的手工编辑，供在途探测返回或配置变更重建时保留。 */
    fun applyUserEdit(editedVolumes: List<ComicVolume>) {
        if (editedVolumes != volumes) {
            volumes = editedVolumes
        }
    }

    /**
     * 清除用户对卷列表的手工编辑，回到当前工作区最近一次干净快照（已探测结果优先，否则基础卷）。
     * 用于工作流 B 内的「重置全部」。
     */
    fun resetUserEdits(): List<ComicVolume> {
        val baseline = lastProbedVolumes ?: lastSyncedBase
        if (volumes != baseline) {
            volumes = baseline
        }
        return baseline
    }

    /**
     * 回到首页时复位工作流 B 会话：取消在途探测、清掉手工编辑并回到基础卷。
     */
    fun resetToBase(
        workspaceKey: String?,
        rootPath: String,
        baseVolumes: List<ComicVolume>,
    ) {
        invalidateProbe()
        currentWorkspaceKey = workspaceKey
        currentRootPath = rootPath
        lastSyncedBase = baseVolumes
        lastProbedVolumes = null
        initialized = true
        if (volumes != baseVolumes) {
            volumes = baseVolumes
        }
    }

    /**
     * 按需在 [ioDispatcher] 上异步执行卷探测（仅进入 `LibraryRoute` 时调用）。
     *
     * 同一 `(workspaceKey, baseVolumes)` 快照已在途或已完成时直接忽略，不重复探测。
     */
    fun requestProbe(
        workspaceKey: String,
        rootPath: String,
        baseVolumes: List<ComicVolume>,
        probe: suspend (List<ComicVolume>) -> List<ComicVolume>,
    ) {
        syncBase(workspaceKey = workspaceKey, rootPath = rootPath, baseVolumes = baseVolumes)

        val key = ProbeKey(workspaceKey = workspaceKey, baseVolumes = baseVolumes)
        if (lastProbedKey == key || inFlightKey == key) return

        probeJob?.cancel()
        val requestGeneration = ++generation
        inFlightKey = key
        isProbing = true

        probeJob = scope.launch {
            try {
                val probed = withContext(ioDispatcher) {
                    probe(baseVolumes)
                }
                // 过期/乱序守卫：协程已取消、代数已推进，或工作区/基础卷已变化时一律丢弃结果。
                if (!isActive || generation != requestGeneration) return@launch
                if (currentWorkspaceKey != workspaceKey || lastSyncedBase != baseVolumes) return@launch

                val reconciled = reconcileLibrary(
                    previous = LibraryState(
                        rootPath = currentRootPath,
                        volumes = volumes,
                        activeVolumeId = "",
                    ),
                    scanned = probed,
                ).volumes
                if (reconciled != volumes) {
                    volumes = reconciled
                }
                lastProbedVolumes = probed
                lastProbedKey = key
            } finally {
                if (generation == requestGeneration) {
                    inFlightKey = null
                    isProbing = false
                }
            }
        }
    }

    /** 取消在途探测并清空已完成标记（离开 `LibraryRoute` 时调用）。 */
    fun cancelProbe() {
        invalidateProbe()
    }

    private fun invalidateProbe() {
        generation++
        probeJob?.cancel()
        probeJob = null
        inFlightKey = null
        lastProbedKey = null
        isProbing = false
    }
}
