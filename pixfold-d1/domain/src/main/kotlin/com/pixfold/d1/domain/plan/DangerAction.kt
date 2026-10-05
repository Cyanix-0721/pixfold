package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 危险动作的确认模型(规格 §9.2,验收第 16 项)。
 *
 * **统一原则**:任何删除类动作 = 默认关闭 + 通用确认 + **独立危险确认**(两次),
 * 且确认框必须**列出影响范围**。
 *
 * **为什么把确认流程做成领域层的纯函数**:验收第 16 项要的是"需要**两次**确认"。
 * 若把两次确认散落在 UI 的 `if (showDialog)` 嵌套里,测试只能断言"点了一次还有弹窗",
 * 很难证明"**恰好**两次""顺序是先通用后独立""危险动作未选中时**不会**多弹一次"。
 * 这里把确认序列算成**数据**,于是"两次"成为结构性质,可被断言、可被变异验证。
 *
 * **文案**:动作的三种文案都是 [TextKey],不在此处解析(见 `text/Msg.kt`)。
 */

/** 触发独立二次确认的危险动作(默认全部关闭)。 */
enum class DangerAction(
    /** 确认框标题里的措辞。 */
    val labelKey: TextKey,
    /** 独立确认框正文。 */
    val bodyKey: TextKey,
    /** 确认按钮文案。 */
    val buttonKey: TextKey,
) {
    DeleteSourceDir(
        labelKey = TextKey.DangerDeleteSourceDirLabel,
        bodyKey = TextKey.DangerDeleteSourceDirBody,
        buttonKey = TextKey.DangerDeleteSourceDirButton,
    ),
    CleanEmptyDirs(
        labelKey = TextKey.DangerCleanEmptyDirsLabel,
        bodyKey = TextKey.DangerCleanEmptyDirsBody,
        buttonKey = TextKey.DangerCleanEmptyDirsButton,
    ),
    OverwriteExisting(
        labelKey = TextKey.DangerOverwriteExistingLabel,
        bodyKey = TextKey.DangerOverwriteExistingBody,
        buttonKey = TextKey.DangerOverwriteExistingButton,
    ),
}

/** 确认序列里的一步。 */
sealed interface ConfirmStep {
    /** 通用确认:标题 `确认执行？`,正文列明影响范围。 */
    data class General(val summaryLines: List<Msg>) : ConfirmStep

    /**
     * 独立危险确认:标题固定为 `危险操作独立确认`(前缀是矢量图标),一次只处理**一个**动作。
     * 有多个危险动作时逐个确认 —— 不合并成一个"全部确认",否则用户一次点击
     * 就放行了多个不可恢复动作。
     */
    data class Danger(val action: DangerAction) : ConfirmStep
}

/**
 * 危险动作按**固定顺序**排列,与用户在界面上看到的顺序无关。
 *
 * 固定顺序的理由:确认框是**串行**的,若顺序取决于 `Set` 的迭代顺序,
 * 用户遇到的第一个确认框会随选中的动作组合而变,同一次操作两次运行可能不同。
 */
private val DANGER_ORDER = listOf(
    DangerAction.DeleteSourceDir,
    DangerAction.OverwriteExisting,
    DangerAction.CleanEmptyDirs,
)

/**
 * 计算本次执行需要的确认序列(规格 §9.2)。
 *
 * - 第 1 步**恒为**通用确认(即使没有任何危险动作也要确认,因为重命名/打包本身会改文件);
 * - 之后**每个**危险动作各占一步独立确认;
 * - 未选中的危险动作**不产生**步骤 —— 因此"没勾选就不该多弹框"可被断言。
 *
 * @param dangerActions 本次实际生效的危险动作(空 = 纯安全操作)
 * @param summaryLines  通用确认框要列出的**影响范围**(卷数、目录、策略)
 */
fun buildConfirmSequence(
    dangerActions: Set<DangerAction>,
    summaryLines: List<Msg>,
): List<ConfirmStep> = buildList {
    add(ConfirmStep.General(summaryLines))
    DANGER_ORDER.filter { it in dangerActions }.forEach { add(ConfirmStep.Danger(it)) }
}

/**
 * 从计划推导出"本次生效的危险动作"(唯一入口)。
 *
 * - [SourcePolicy.Delete] → [DangerAction.DeleteSourceDir];
 * - [OutputConflictPolicy.Overwrite] 且**确有**输出冲突 → [DangerAction.OverwriteExisting]
 *   (没有冲突就不存在覆盖,不该让用户白确认一次);
 * - [cleanEmptyDirs] 为 true → [DangerAction.CleanEmptyDirs](工作流 A)。
 *
 * 三者**默认都不生效**,与归档语义的默认值一致。
 */
fun effectiveDangerActions(
    plans: List<VolumePackPlan>,
    cleanEmptyDirs: Boolean = false,
): Set<DangerAction> = buildSet {
    if (plans.any { !it.skipped && it.sourcePolicy == SourcePolicy.Delete }) {
        add(DangerAction.DeleteSourceDir)
    }
    if (plans.any { it.overwritesExisting }) {
        add(DangerAction.OverwriteExisting)
    }
    if (cleanEmptyDirs) add(DangerAction.CleanEmptyDirs)
}

/**
 * 通用确认框要列出的影响范围(规格 §9.2:确认框必须列出影响范围)。
 *
 * **为什么必须由领域层生成**:这些行要同时出现在确认框与执行报告里,
 * 两处各写一份必然漂移。这里是唯一来源。
 *
 * 返回 [Msg] 列表 —— 卷数等数值以 `Int` 传参,以便英文侧用 `<plurals>` 正确变格。
 */
fun generalSummaryLines(
    plans: List<VolumePackPlan>,
    dangerActions: Set<DangerAction>,
): List<Msg> = buildList {
    val counts = planCountsOf(plans)
    add(Msg(TextKey.SummaryProcessingVolumes, listOf(counts.runnable, counts.skipped)))
    add(
        Msg(
            TextKey.SummarySourceDirPrefix,
            listOf(
                Msg(
                    if (DangerAction.DeleteSourceDir in dangerActions) {
                        TextKey.SummarySourceDirDelete
                    } else {
                        TextKey.SummarySourceDirKeep
                    },
                ),
            ),
        ),
    )
    if (DangerAction.CleanEmptyDirs in dangerActions) {
        add(Msg(TextKey.SummaryCleanEmptyDirsWarning))
    }
    if (DangerAction.OverwriteExisting in dangerActions) {
        add(
            Msg(
                TextKey.SummaryOverwriteCbz,
                listOf(
                    plans.filter { !it.skipped && it.volume.outputExists }.map { it.outputPath },
                ),
            ),
        )
    }
    // 末条必须与"本次实际会做什么"一致:选了删除源目录却说"只写入输出文件"
    // 会与上一行的「源目录：删除（危险）」直接矛盾(真机核对时发现)。
    add(
        Msg(
            if (DangerAction.DeleteSourceDir in dangerActions) {
                TextKey.SummaryDeleteSourceDirWarning
            } else {
                TextKey.SummaryWriteOnly
            },
        ),
    )
}
