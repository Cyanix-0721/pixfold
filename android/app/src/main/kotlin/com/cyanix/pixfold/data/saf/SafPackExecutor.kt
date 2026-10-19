package com.cyanix.pixfold.data.saf

import android.content.Context
import com.cyanix.pixfold.domain.comic.PageBytes
import com.cyanix.pixfold.domain.comic.pageByteSourceOf
import com.cyanix.pixfold.domain.comic.packableVolumes
import com.cyanix.pixfold.domain.pack.PackExecutionOutcome
import com.cyanix.pixfold.domain.pack.PackPreflightResult
import com.cyanix.pixfold.domain.pack.executePackPlans
import com.cyanix.pixfold.domain.pack.packRunAsExecutionReport
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.ui.library.PackExecutorPort
import com.cyanix.pixfold.ui.library.PackUiOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打包执行器的工作区（SAF）实现（D2b「大归档写入」）。
 *
 * ### 它把四件事串起来
 * 1. **页面字节**：`SafPageBytes` 按 `SourceItem.uri` 读原图（`domain` 层的
 *    `pageByteSourceOf` 负责 itemId → uri 的映射）；
 * 2. **输出流**：`SafCbzOutputSink` 在 SAF 目录下建文件 / 覆盖写；
 * 3. **空间查询**：`FilesystemSpaceSource` 问 `StatFs`。问不到时是 **null**，
 *    而 `executePackPlans` 把 null 解释成「无法判定」⇒ 不做空间阻断（不是放行，是
 *    「我没有信息，别替用户做决定」）；
 * 4. **报告转换**：`packRunAsExecutionReport` 把 `PackRunReport` 变成界面认的
 *    `ExecutionReport`。
 *
 * ### 为什么在这里切 IO 线程
 * `executePackPlans` 是**同步阻塞**的（逐页 `readBytes`、逐卷写 ZIP）。它被刻意
 * 设计成同步的 —— 领域层不引协程，才好在 JVM 单测里直接跑。切线程的责任因此落在
 * 这个平台实现上，见 [execute] 里的 `withContext(Dispatchers.IO)`。
 *
 * ### 进度回调的线程
 * `onProgress` 在 IO 线程上被调用。本类**不替你切主线程** —— 调用方（`LibrarySteps`）
 * 用 `withContext(Dispatchers.Main)` 或快照状态原位写入。为什么不在这里切：
 * 每页一次 `withContext(Main)` 在千级文件下是真实开销，调用方更清楚该节流到什么粒度。
 *
 * @param context 任何 `Context`（内部取 `applicationContext`，不持有 Activity）。
 * @param treeUri 工作区根目录的 tree URI。
 * @param dirOfVolume 卷 → **输出目录的相对路径**（相对工作区根，空串 = 根目录）。
 *   解析规则见 `comicVolumesFrom` 的 `outputDirSug`，以及本文件调用点的说明。
 * @param rootDisplayName 工作区根的**显示名**（`ComicVolume.outputDir` 的默认值就是
 *   它），用于把 `outputDir` 反解成相对路径。见 [outputDirRelOf]。
 */
class SafPackExecutor(
    context: Context,
    private val treeUri: android.net.Uri,
    private val rootDisplayName: String,
) : PackExecutorPort {

    private val appContext = context.applicationContext

    /**
     * 空间余量阈值。
     *
     * **数值依据**：CBZ 的输出大小 ≈ 原图总和（图片已压缩，ZIP 再压收益 < 2%），
     * 故估算基本准；留 10% 是为了兜住 ZIP 目录区、ComicInfo.xml 与 provider 的块对齐。
     * HANGOFF §11 第 12 条（百分比 or 绝对值）直到 2026-10-19 仍未由真机定档 ——
     * 这里选百分比是因为它随卷大小自然伸缩，而绝对阈值在小卷上过于宽松、在大卷上过于严苛。
     * 定档后改这一个常量即可。
     */
    private val threshold: SpaceThreshold = SpaceThreshold.Percentage(marginPercent = 10)

    /**
     * 临界安全余量：32 MB。
     *
     * 为什么不是 0：空间「刚好够」时，ZIP 写入过程本身需要的临时缓冲、
     * 以及 provider 的元数据更新都可能失败，且失败发生在写到一半 —— 那正是
     * `discardFor` 要清理的半成品。留一点余量比事后清理便宜。
     */
    private val safetyMargin: Long = 32L * 1024 * 1024

    override suspend fun execute(
        plans: List<VolumePackPlan>,
        token: CancellationToken,
        onProgress: (TaskProgress) -> Unit,
    ): PackUiOutcome = withContext(Dispatchers.IO) {
        // ---- 1. 组装平台三件套 ----
        val sink = SafCbzOutputSink(appContext, treeUri) { plan -> outputDirRelOf(plan) }
        val pageBytes = SafPageBytes(appContext)
        val space = FilesystemSpaceSource(appContext)

        // itemId → documentUri 的映射。真实扫描下 `SourceItem.id` 就是 documentUri，
        // 但**不假设**这一点：映射由计划里的页列表现算，将来若 id 改成内部 id，
        // 这里不用动。缺映射的页会让 `pageByteSourceOf` 返回 null ⇒ 该页记 missing
        // ⇒ 该卷一个字节都不写（`runPackPlans` 的「先探测后开流」）。
        val uriByItemId = HashMap<String, String>()
        for (plan in plans) {
            for (page in plan.volume.pageOrder.order) {
                page.uri?.let { uriByItemId[page.id] = it }
            }
        }
        val bytes = pageByteSourceOf(uriByItemId, pageBytes)

        // ---- 2. 空间：问不到就是 null（＝无法判定，不是 0）----
        val availableBytes = space.availableBytesOf(treeUri)

        // ---- 3. 执行 ----
        val outcome = try {
            executePackPlans(
                plans = plans,
                availableBytes = availableBytes,
                threshold = threshold,
                safetyMargin = safetyMargin,
                bytes = bytes,
                sink = sink,
                token = token,
                timeMillis = System.currentTimeMillis(),
                onProgress = { progress, _ -> onProgress(progress) },
            )
        } catch (e: Exception) {
            // 领域层内部不该抛（它把 per-volume 失败都收进了报告），但「不该抛」不等于
            // 「不会抛」—— 页数极多时的 OOM、provider 在遍历中途断开都可能冒出来。
            // 界面必须能显示一句人话，而不是让协程静默死掉（那时进度条会永远停在
            // 某个百分比上，用户以为还在干活）。
            return@withContext PackUiOutcome.Error(
                e.message ?: e::class.simpleName ?: "打包失败",
            )
        }

        // ---- 4. 转成界面/撤销用的报告 ----
        when (outcome) {
            is PackExecutionOutcome.Rejected -> PackUiOutcome.Rejected(outcome.preflight)
            is PackExecutionOutcome.Ran -> PackUiOutcome.Done(
                packRunAsExecutionReport(plans, outcome.report),
            )
        }
    }

    /**
     * 卷 → 输出目录的相对路径。
     *
     * `VolumePackPlan.outputPath` 是给人看的字符串，**不是** SAF 路径；真实写入必须
     * 拿到相对工作区根的目录路径。`ComicVolume.outputDir` 的默认值来自
     * `comicVolumesFrom` 的 `outputDirSug` = **卷目录的父目录**（相对路径，根下一级
     * 回退到工作区根显示名）；用户也可能手工改成别的。
     *
     * 三种情况：
     * 1. `outputDir` 等于工作区根显示名 ⇒ 空串（写在工作区根下）；
     * 2. `outputDir` 以根显示名开头（`<根>/<子目录>`）⇒ 去掉根前缀，得到相对路径；
     * 3. 其余（用户填了绝对路径 / 别的名字）⇒ 当成相对路径原样交给 sink，
     *    由它逐级下钻；找不到时 `openFor` 返回 null ⇒ 该卷记 `Failed`，
     *    **不会写到错误的目录去**。
     */
    private fun outputDirRelOf(plan: VolumePackPlan): String =
        outputDirRelOf(plan.volume.outputDir, rootDisplayName)
}

/**
 * 把卷的 `outputDir`（用户可见的**显示路径**，如 `test/系列A`）反解成
 * 相对工作区根的**目录段**（如 `系列A`），供 `SafCbzOutputSink` 逐级下钻。
 *
 * ### 为什么必须先去掉工作区根名
 * `outputDir` 的初值来自 `comicVolumesFrom` 的 `outputDirSug`，那是**给人看的**
 * 路径 —— 它带着工作区根目录的显示名（`test/系列A`）。而 SAF 的
 * `buildChildDocumentsUriUsingTree(treeUri, docId)` 期望的是**相对树的路径**：
 * 传 `test/系列A` 会去找「根下的 test 目录」（不存在）⇒ `openFor` 返回 null
 * ⇒ 每一卷都记 `Failed("无法为目标卷打开输出流")`。
 *
 * ### 三种输入的区别（都不能混为一谈）
 * - 空串 ⇒ 根目录本身（用户接受建议值、且卷就在根下一级时会出现）；
 * - 等于根显示名 ⇒ 也是根目录（`outputDirSug` 在卷位于根下一级时回退成根显示名）；
 * - 其它 ⇒ 去掉 `"<根名>/"` 前缀；**没有该前缀时原样返回**（不猜、不剥任意前缀：
 *   用户的目录名里可能恰好含斜杠片段，硬剥会把 `我的/收藏` 剥成 `收藏`）。
 *
 * 找不到对应目录时 `openFor` 返回 null 并记 `Failed` —— 这条路径是安全的：
 * 它**不会**把文件写到错误的目录里（这是 `CbzOutputSink` 契约的一部分）。
 */
internal fun outputDirRelOf(outputDir: String, rootDisplayName: String): String {
    if (outputDir.isEmpty() || outputDir == rootDisplayName) return ""
    val prefix = "$rootDisplayName/"
    return if (outputDir.startsWith(prefix)) outputDir.removePrefix(prefix) else outputDir
}

/**
 * 判断「这个卷能不能真的被打包出去」的预检辅助。
 *
 * 单独抽出来是因为 UI 需要在**点确认之前**就告诉用户「有 N 卷连页都没有」——
 * 若等到执行完才在报告里出现，用户已经点过两次确认对话框了。
 */
internal fun packableCountOf(plans: List<VolumePackPlan>): Int =
    packableVolumes(plans.map { it.volume }).size

/**
 * 把预检结果里「有没有阻断」取出来给 UI 用。
 *
 * 独立成函数而不是让 UI 直接读 `blockers.isEmpty()`：将来若出现第二个阻断类型
 * （例如「输出目录不可写」），UI 侧的判据不用改。
 */
internal fun PackPreflightResult.isBlocked(): Boolean = blockers.isNotEmpty()

/** 供调试与日志使用的空实现辅助（避免各处重复写 `PageBytes { null }`）。 */
internal val NoPageBytes = PageBytes { null }
