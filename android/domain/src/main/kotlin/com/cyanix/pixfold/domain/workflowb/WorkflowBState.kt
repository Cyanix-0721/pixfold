package com.cyanix.pixfold.domain.workflowb

import com.cyanix.pixfold.domain.comic.BatchApplyResult
import com.cyanix.pixfold.domain.comic.batchApply
import com.cyanix.pixfold.domain.comic.replaceVolume
import com.cyanix.pixfold.domain.comic.setIncluded
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.model.groupVolumesBySeries

/**
 * 工作流 B(漫画库 → CBZ)的批次状态(规格 §4.5 / §10.1)。
 *
 * 与工作流 A 同样的风格:**不可变 data class + 纯函数**,UI 只调用这些入口。
 * 领域层不含任何 Compose 类型。
 *
 * `activeVolumeId` 放在领域状态里(而非某个 Composable 的 `remember`):
 * 步骤 1(漫画库)→ 步骤 2(元数据)→ 步骤 3(预览)必须共享"当前卷",
 * 否则用户在第 1 步选中的卷会在切步骤时丢失(归档原型的 controller 语义)。
 */
data class WorkflowBState(
    val rootPath: String,
    val volumes: List<ComicVolume>,
    val activeVolumeId: String,
) {
    /**
     * 当前卷;**空工作区时为 null**。
     *
     * 真实 SAF 扫描可能一卷都不产生(目录里没有图片,或图片全散在根目录 ——
     * 按「每个直接含图片的目录 = 一个集合」的规则,根目录本身不构成卷)。
     * 这是**合法状态**,不是异常,故这里给可空版本。
     */
    val activeVolumeOrNull: ComicVolume?
        get() = volumes.firstOrNull { it.id == activeVolumeId } ?: volumes.firstOrNull()

    /**
     * 当前卷;id 失效时退回第一卷。
     *
     * ⚠️ **空列表会抛异常**(`volumes.first()` 的语义)。界面在读取本属性**之前**
     * 必须先用 [hasVolumes] 判定并渲染空态;空态三处落点见 `MetadataPage`、
     * `PackPreviewPage`、`LibraryPage`。领域层保留非空版本是因为绝大多数调用点
     * (批量设置、计划生成、预览)都以「至少有一卷」为前提,让它们逐个判空只是噪声。
     *
     * 异常消息刻意写成 `NoSuchElementException: List is empty.` 之外的形态 ——
     * 崩溃日志里能一眼看出「读的是 activeVolume,且卷列表是空的」。
     */
    val activeVolume: ComicVolume
        get() = activeVolumeOrNull
            ?: throw NoSuchElementException("当前工作区没有任何漫画卷,不能读取 activeVolume")

    /** 是否有卷可处理(空工作区的显式判据)。 */
    val hasVolumes: Boolean get() = volumes.isNotEmpty()

    val includedCount: Int get() = volumes.count { it.included }

    val volumeCount: Int get() = volumes.size

    /** 待确认卷数(语言未确认 / 卷号缺失);卷列表按系列聚合展示用。 */
    val pendingCount: Int get() = volumes.count { it.needsConfirmation }

    /** 按系列分组(顺序 = 首次出现顺序,不用排序打乱用户的目录顺序)。 */
    val seriesGroups: List<Pair<String, List<ComicVolume>>> get() = groupVolumesBySeries(volumes)
}

fun workflowBStateOf(
    rootPath: String,
    volumes: List<ComicVolume>,
): WorkflowBState = WorkflowBState(
    rootPath = rootPath,
    volumes = volumes,
    activeVolumeId = volumes.firstOrNull()?.id.orEmpty(),
)

/** 切换当前卷(选中项只在库内有效;无效 id 原样返回)。 */
fun selectVolume(state: WorkflowBState, volumeId: String): WorkflowBState =
    if (state.volumes.none { it.id == volumeId }) state else state.copy(activeVolumeId = volumeId)

/** 勾选/取消勾选某卷是否参与打包。 */
fun setVolumeIncluded(state: WorkflowBState, volumeId: String, included: Boolean): WorkflowBState {
    val volume = state.volumes.firstOrNull { it.id == volumeId } ?: return state
    return state.copy(volumes = replaceVolume(state.volumes, setIncluded(volume, included)))
}

/** 用编辑后的卷替换库中的对应项(元数据编辑器的唯一写回入口)。 */
fun updateVolume(state: WorkflowBState, volume: ComicVolume): WorkflowBState =
    state.copy(volumes = replaceVolume(state.volumes, volume))

/**
 * 批量设置(验收第 13 项:批量设置**不覆盖**逐项例外)。
 *
 * 返回新的库状态 + **实际生效/被跳过卷数**(UI 据此提示 `已设置到 N 卷`,而非笼统"已应用")。
 */
fun batchApplyToLibrary(
    state: WorkflowBState,
    field: MetaField,
    value: MetaValue,
    force: Boolean = false,
): Pair<WorkflowBState, BatchApplyResult> {
    val result = batchApply(state.volumes, field, value, force)
    return state.copy(volumes = result.volumes) to result
}
