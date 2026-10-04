package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.comic.langIso
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 工作流 B 的**打包计划**(规格 §9.1,验收第 15 项)。
 *
 * 这是"扫描 / 决策 / 执行分离"(HANGOFF §2 约束 1)的**决策**产物:
 * 只描述"将要做什么",**不产生任何副作用**。执行在 [executePackagePlans],
 * 且在执行前必须走 [ExecutionGate] 的确认流程(验收第 16 项)。
 */

/** 输出已存在时的策略(归档语义:默认 [Rename])。 */
enum class OutputConflictPolicy(val textKey: TextKey) {
    Rename(TextKey.ConflictAutoRename),
    Skip(TextKey.ConflictSkipVolume),
    Overwrite(TextKey.ConflictOverwrite),
}

/** 源目录处置策略(归档语义:默认 [Keep];[Delete] 是**危险动作**)。 */
enum class SourcePolicy(val textKey: TextKey) {
    Keep(TextKey.SourceKeep),
    Delete(TextKey.SourceDelete),
}

/**
 * 单卷的打包计划。
 *
 * [renumbered] 是**生成计划时的快照**(归档语义:不是惰性求值)——
 * 否则用户在执行前改了页序,计划上写的张数就会与实际执行的不一致。
 *
 * [skipReason] 是 [Msg](键 + 参数),不是成品字符串 —— 见 `text/Msg.kt`。
 */
data class VolumePackPlan(
    val volume: ComicVolume,
    val outputDisplay: String,
    val pageCount: Int,
    val skipped: Boolean,
    val skipReason: Msg?,
    val conflictPolicy: OutputConflictPolicy,
    val sourcePolicy: SourcePolicy,
) {
    /** 输出全路径(展示与"跳过原因"文案共用)。 */
    val outputPath: String get() = "${volume.outputDir}/$outputDisplay"

    /**
     * 本次执行会不会**覆盖掉一个已经存在的文件**。
     *
     * **唯一判据**,由三处共用:计划层的危险动作([effectiveDangerActions] 的
     * `OverwriteExisting`)、报告层的覆盖计数([ExecutionReport.overwrittenCount])、
     * 以及撤销的安全档([undoOpsOf])。
     *
     * 为什么要收成一处:三者问的是**同一个问题**("这次是不是要盖掉别人的东西"),
     * 各写一份 `conflictPolicy == Overwrite && volume.outputExists` 的话,
     * 一旦有一处漏改,后果是"界面警告了覆盖、撤销却仍按新建删掉"—— 那正是缺陷 R3
     * 最贵的部分(被覆盖的内容已经找不回来)。[skipped] 的卷根本没执行,不算。
     */
    val overwritesExisting: Boolean
        get() = !skipped &&
            conflictPolicy == OutputConflictPolicy.Overwrite &&
            volume.outputExists
}

/**
 * 生成操作清单文案(规格 §9.1 的**文案契约**,按序):
 *
 * 1. (若 skipped)`跳过：输出已存在 …` 并 **return**(后续不产出);
 * 2. `创建 CBZ → <outputDisplay>`;
 * 3. `写入 ComicInfo.xml（Title=…，Series=…，Number=…，Writer=…，LanguageISO=…）`;
 * 4. `按页序打包 <N> 张并重命名为页码（0001 起）`;
 * 5. (若 `sourcePolicy == Delete`)`打包后删除源目录：<dirPath>`。
 *
 * **为什么把文案放在领域层**:这是"计划必须可解释"(约束 5)的载体。
 * 若文案散在 UI 里,测试就只能断言"有个按钮",而无法断言"计划说清了要做什么"。
 * 归档原型把同一份契约放在 `VolumePackPlan.describe()`,此处沿用其文本。
 *
 * 返回 [Msg] 列表 —— 数值以 `Int` 传参以便英文侧用 `<plurals>` 变格。
 */
fun VolumePackPlan.describe(): List<Msg> {
    if (skipped) {
        return listOf(Msg(TextKey.PackSkipOutputExists, listOf(outputPath)))
    }
    val v = volume
    // 参数可以是**普通字符串**(真实值,不翻译)或 **Msg**(占位词「未设置/空/不写入」,要翻译)。
    // 这样「未设置」这类占位词不会以中文字面量被冻进计划里。
    val number: Any = v.volume?.toString() ?: Msg(TextKey.PackValueUnset)
    val writer: Any = if (v.writer.isEmpty()) Msg(TextKey.PackValueEmpty) else v.writer
    val lang: Any = langIso(v.language, v.otherLangCode) ?: Msg(TextKey.PackValueNotWritten)
    return buildList {
        add(Msg(TextKey.PackCreateCbz, listOf(outputDisplay)))
        add(Msg(TextKey.PackWriteComicInfo, listOf(v.title, v.series, number, writer, lang)))
        add(Msg(TextKey.PackPackPages, listOf(pageCount)))
        if (sourcePolicy == SourcePolicy.Delete) {
            add(Msg(TextKey.PackDeleteSourceDir, listOf(v.dirPath)))
        }
    }
}

/**
 * 输出冲突时的实际文件名(规格 §9.1)。
 *
 * - [OutputConflictPolicy.Rename]:在**最后一个** `.` 前插入 ` (1)`
 *   → `name.substring(0, dot) + " (1)" + name.substring(dot)`;无扩展名则直接追加。
 * - [OutputConflictPolicy.Skip]:不改名(计划会整个跳过该卷)。
 * - [OutputConflictPolicy.Overwrite]:不改名(直接覆盖)。
 *
 * @param outputExists 输出目录下是否已存在同名文件
 */
fun outputDisplayOf(
    cbzFileName: String,
    policy: OutputConflictPolicy,
    outputExists: Boolean,
): String {
    if (!outputExists || policy != OutputConflictPolicy.Rename) return cbzFileName
    val dot = cbzFileName.lastIndexOf('.')
    return if (dot <= 0) {
        "$cbzFileName (1)"
    } else {
        cbzFileName.substring(0, dot) + " (1)" + cbzFileName.substring(dot)
    }
}

/**
 * 构建整库打包计划。
 *
 * **只处理 `included == true` 的卷**(规格 §9.1 末段)——未勾选的卷不进计划,
 * 因此也不会出现在执行报告里,不会让用户误以为"没打包是失败了"。
 *
 * 默认 `conflictPolicy = Rename`、`sourcePolicy = Keep`(归档语义)。
 */
fun buildPackagePlans(
    volumes: List<ComicVolume>,
    conflictPolicy: OutputConflictPolicy = OutputConflictPolicy.Rename,
    sourcePolicy: SourcePolicy = SourcePolicy.Keep,
): List<VolumePackPlan> = volumes.filter { it.included }.map { v ->
    val skipped = v.outputExists && conflictPolicy == OutputConflictPolicy.Skip
    VolumePackPlan(
        volume = v,
        outputDisplay = outputDisplayOf(v.cbzFileName, conflictPolicy, v.outputExists),
        pageCount = v.pageCount,
        skipped = skipped,
        skipReason = if (skipped) {
            Msg(TextKey.PackSkipOutputExists, listOf("${v.outputDir}/${v.cbzFileName}"))
        } else {
            null
        },
        conflictPolicy = conflictPolicy,
        sourcePolicy = sourcePolicy,
    )
}

/** 计划里的计数(UI 顶部「共 N 卷 · 跳过 M 卷」与测试共用)。 */
data class PlanCounts(val total: Int, val skipped: Int) {
    val runnable: Int get() = total - skipped
}

fun planCountsOf(plans: List<VolumePackPlan>): PlanCounts =
    PlanCounts(total = plans.size, skipped = plans.count { it.skipped })
