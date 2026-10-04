package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.naming.RenameOp
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 执行报告与撤销(规格 §9 / 验收第 17 项)。
 *
 * **D1 原型不触碰真实文件系统**(HANGOFF §9 D1 范围),故"执行"是**确定性模拟**:
 * 不产生副作用,只把"本会发生什么、结果如何"算成数据。
 *
 * **为什么仍要算得这么细**:验收第 17 项要的是"报告含成功/跳过/失败 **三类**"。
 * 若执行层只会返回"成功",UI 就没有可展示的失败态,报告也就无法回答
 * "哪些没做成、为什么" —— 这正是归档原型被记为薄弱的地方。故这里把
 * **失败判定条件**也做进领域层,并让它们是**用户可达**的真实条件
 * (页数为 0、输出目录为空),而不是随机注入的假失败。
 */

/** 单项执行结果。 */
enum class ExecOutcome(val textKey: TextKey) {
    Succeeded(TextKey.OutcomeSucceeded),
    Skipped(TextKey.OutcomeSkipped),
    Failed(TextKey.OutcomeFailed),
}

/**
 * 报告里的一行。
 *
 * [createdPath] 仅在该项**真的产生了输出**时非空 —— 撤销只回滚这些,
 * 不会去动原本就没动过的东西。
 *
 * [originalPath] 是**本项动手之前,这个位置上原本存在的东西**(文件名或路径);
 * `null` 表示原位置本来就是空的(纯新建)。它与 [createdPath] 一起才构成一次完整的
 * "从哪来 → 到哪去",撤销才有的可逆。
 *
 * [reason] 是 [Msg](键 + 参数);[label] 是**文件名**,不是文案,故仍是 `String`。
 */
data class ExecEntry(
    val id: String,
    val label: String,
    val outcome: ExecOutcome,
    val reason: Msg? = null,
    val createdPath: String? = null,
    val originalPath: String? = null,
) {
    init {
        // ---- 不变式:只有"成功"才产生输出(createdPath 非空 ⟺ Succeeded) ----
        //
        // 为什么把不变量**强制**在这里,而不是继续只写在文档与调用点里:
        // 此前 `undoRecordOf` 同时用了 `outcome == Succeeded` 与 `createdPath != null`
        // 两道判据 —— 二者**完全等价**,是纯冗余。P6 变异验证实测:
        // "把跳过也算进撤销记录"这个变异**未能被任何测试捕获**,正是因为
        // 去掉一道后另一道仍然挡住了它。
        //
        // 单纯删掉一道判据只是把冗余换个地方藏起来;根因是**不变量没有被强制**,
        // 于是"跳过项却带着 createdPath"这种非法组合在类型上依然可构造。
        // 这里用 require 把它堵死:非法状态根本造不出来,于是使用点只留**一道**判据即可。
        //
        // ⚠️ 这两条消息是**开发者诊断**(面向堆栈),不是用户文案 ⇒ 保持中文字面量,不建键。
        require(outcome == ExecOutcome.Succeeded || createdPath == null) {
            "$outcome 项不应携带 createdPath（$createdPath）—— 只有成功执行才产生输出"
        }
        require(outcome != ExecOutcome.Succeeded || createdPath != null) {
            "Succeeded 项必须携带 createdPath —— 否则撤销无从知道要回滚什么"
        }
        // [originalPath] 同理:它是"本次动手覆盖掉/改掉的那个旧东西",只有真动过手的
        // 成功项才可能有。跳过项与失败项什么也没动,带上它就是无中生有。
        require(outcome == ExecOutcome.Succeeded || originalPath == null) {
            "$outcome 项不应携带 originalPath（$originalPath）—— 它什么都没改动"
        }
    }
}

/**
 * 执行报告。
 *
 * [entries] 的顺序 = 计划顺序(不重排),这样"报告第 3 行"与"计划第 3 行"对得上,
 * 用户才能拿报告去核对计划。
 */
data class ExecutionReport(val entries: List<ExecEntry>, val kind: ExecKind) {
    val succeededCount: Int get() = entries.count { it.outcome == ExecOutcome.Succeeded }
    val skippedCount: Int get() = entries.count { it.outcome == ExecOutcome.Skipped }
    val failedCount: Int get() = entries.count { it.outcome == ExecOutcome.Failed }

    /** 一句话结论(报告页顶部)。数值以 `Int` 传参以便英文侧用 `<plurals>` 变格。 */
    val summary: Msg
        get() = Msg(
            TextKey.ExecSummary,
            listOf(entries.size, succeededCount, skippedCount, failedCount),
        )

    /** 是否有可撤销的动作(没有成功项就没什么可撤销的)。 */
    val canUndo: Boolean get() = succeededCount > 0

    /**
     * 本次执行**替换掉了原本已有的文件**的项数。
     *
     * 为什么它是**独立于 [succeededCount]** 的一个数:一次成功的执行可能是
     * ①**新建**(那个位置上原本什么都没有),也可能是 ②**覆盖**(那个位置上原本有用户的文件)。
     * 两者都算"成功",但撤销的**代价完全不同** —— 新建的删掉只是撤销,
     * 覆盖的删掉是**把用户原有的内容也一并抹掉**(被覆盖的内容已经找不回来了)。
     * 撤销入口若不区分这两者,就会把②当成①来删(缺陷 R3)。
     *
     * 界面据此决定"点撤销时要不要先警告"(见 [needsUndoWarning])。
     */
    val overwrittenCount: Int get() = entries.count { it.isOverwrite(kind) }

    /**
     * 撤销前是否**必须**先让用户选范围(用户 m04079 第③项)。
     *
     * 判据就是"有没有覆盖项":没有覆盖项时两种范围的结果**完全一样**,
     * 弹一个只有"确定/取消"的框只是多一次点击 —— 用户明确抱怨过这种无谓打扰。
     */
    val needsUndoWarning: Boolean get() = overwrittenCount > 0
}

/** 报告属于哪条工作流(文案与撤销语义不同)。 */
enum class ExecKind(val titleKey: TextKey) {
    Rename(TextKey.ExecKindRename),
    Package(TextKey.ExecKindPackage),
}

/**
 * 撤销的**范围**(用户 m04079 第③项:「仅选择当前操作生成的撤销还是全部删除还是取消」)。
 *
 * 两个档位只在"本次覆盖掉了别人的东西"时才有区别,其余情况结果逐字相同。
 */
enum class UndoScope {
    /**
     * **只回滚本次新建的**东西(安全档,默认)。
     *
     * 覆盖项在本次没有**新建**任何文件 —— 它替换掉的那个位置本来就有东西,
     * 所以这一档**不碰**它:不删,也就不会毁掉任何本次之前就存在的内容。
     */
    ThisRun,

    /**
     * **连被覆盖掉的那些位置一起删**(危险档)。
     *
     * ⚠️ 它删掉的不只是本次的产物:那个路径上原本是用户的文件,内容已被本次覆盖,
     * **删了无法恢复**。故界面必须先用 [ExecutionReport.needsUndoWarning] 警告并让用户选。
     */
    Everything,
}

/**
 * 本项是否「替换掉了原本已有的文件」——**唯一**判据,[ExecutionReport.overwrittenCount]
 * 与 [undoOpsOf] 共用,不允许各写一份(两份判据一旦漂移,"警告了却仍然删错"就没人挡得住)。
 *
 * 为什么按 [ExecKind] 分叉:同一个 [ExecEntry.originalPath] 在两种工作流下含义不同 ——
 *  - **打包**:输出已存在且策略是 `Overwrite` ⇒ 这一项**替换**了用户的旧 CBZ;
 *  - **改名**:`originalPath` 是本文件**原来的名字**,改名是**移动**(旧位置变空,
 *    没有谁的内容被抹掉)。改名撞名属于冲突/警告,计划层根本不会执行它。
 *
 * 所以"覆盖"只可能发生在打包侧;改名侧恒为 false。
 */
private fun ExecEntry.isOverwrite(kind: ExecKind): Boolean =
    kind == ExecKind.Package && originalPath != null

/**
 * 执行打包计划(模拟)。
 *
 * **失败判定**(用户可达的真实条件,不是随机失败):
 *  - 该卷**没有任何页** → 失败「没有任何页，无法打包」;
 *  - **输出目录为空** → 失败「输出目录为空」;
 *  - 计划已标记 skipped → 跳过,并沿用计划的 skipReason。
 *
 * 先判"计划跳过"再判失败:计划说要跳过的卷,其失败条件无意义,报"失败"会误导。
 *
 * **实现已收进统一词汇**([executePlan] + [PackagePlannedOp]):本函数保留原签名与
 * 逐字行为,只做薄委托 —— D3 之前"打包"与"改名"各有一套执行逻辑,新增第三种操作
 * (复制/移动)时没有任何结构提示"还得再补一份"。现在两种操作共用
 * [PlannedOp] 与 [executePlan],加一种就是编译期错误而不是静默漏掉。
 */
fun executePackagePlans(plans: List<VolumePackPlan>): ExecutionReport =
    executePlan(operationPlanOf(plans))

/**
 * 执行重命名计划(模拟)。
 *
 * 失败判定:目标文件名为**空** → 失败「新名称为空」(用户清空覆盖框即可触发,
 * 领域层另有"空 = 撤销覆盖"的语义,但若最终名仍为空则无法执行)。
 *
 * **实现已收进统一词汇**([executePlan] + [RenamePlannedOp]),理由同 [executePackagePlans]。
 */
fun executeRenamePlan(ops: List<RenameOp>): ExecutionReport =
    executePlan(operationPlanOfRenames(ops))

// ---- 撤销(规格 §9.2:提供但为 mock) ----

/** 逆向操作。正式版据此真正回滚;D1 只记录与展示。 */
sealed interface UndoOp {
    /** 删除本次新建的文件。 */
    data class DeleteCreated(val path: String) : UndoOp

    /**
     * 删除**被本次覆盖掉**的那个位置上的文件([UndoScope.Everything] 才产生)。
     *
     * ⚠️ 与 [DeleteCreated] 分开成两个类型,而不是复用同一个:这个删除是
     * **毁掉本次之前就存在的内容**(已被覆盖,删了找不回来)。让它在类型上就与
     * "撤销本次新建"不同,界面与正式版执行器才有机会对它区别对待 ——
     * 混成一个类型的话,"哪些项是真的危险"就只剩注释在说。
     */
    data class DeleteOverwritten(val path: String) : UndoOp

    /** 把改名后的文件改回去。 */
    data class RestoreRenamed(val from: String, val to: String) : UndoOp
}

/**
 * 一次执行的撤销记录。
 *
 * **只记录成功项**:失败项没有产生副作用,跳过项也没有 —— 把它们放进撤销记录
 * 会让"撤销 N 项"的数字虚高。
 */
data class UndoRecord(val ops: List<UndoOp>, val kind: ExecKind) {
    val opCount: Int get() = ops.size

    /**
     * 其中**会删掉本次之前就存在的文件**的项数。
     *
     * 全为 0 时这次撤销是纯粹的回退(怎么删都只删本次产物);
     * 不为 0 时它同时抹掉用户原有的内容 —— 界面/日志据此给出更重的措辞。
     */
    val overwrittenOpCount: Int get() = ops.count { it is UndoOp.DeleteOverwritten }
}

/**
 * 从报告推导撤销记录;没有成功项时返回 null(没有可撤销的东西)。
 *
 * [scope] 默认取**安全档** [UndoScope.ThisRun]。默认值刻意选保守的那一侧:
 * 若某处忘了显式声明范围,结果是"少删了"而不是"把用户的旧文件删了" ——
 * 少删可以再点一次,删错不可逆(与 [undoOpsOf] 用回落而非 `requireNotNull` 同一取舍)。
 * ⚠️ 用户明确选择"全部删除"的那条路径**必须显式传** [UndoScope.Everything],
 * 否则界面上选了危险档却什么也没多删。
 */
fun undoRecordOf(
    report: ExecutionReport,
    scope: UndoScope = UndoScope.ThisRun,
): UndoRecord? = undoOpsOf(report, report.kind, scope)?.let { UndoRecord(it, report.kind) }

/**
 * 撤销动作的**唯一**推导实现(按 [kind] 选回滚语义)。
 *
 * 只需一道判据:`ExecEntry` 的 init 已强制"只有成功项才有 createdPath"
 * (createdPath 非空 ⟺ Succeeded)。此前这里并列了 outcome 与 path 两道等价判据,
 * 属冗余 —— 冗余的代价是"去掉一道仍有另一道兜着",于是缺陷能被掩盖(见 ExecEntry 注释)。
 *
 * 收成一处之后,统一入口 [undoRecordFrom] 与既有的 [undoRecordOf] 不可能再漂移:
 * 两者只差"kind 从哪来"(计划 vs 报告)。
 *
 * ## 改名:终点必须取自 [ExecEntry.originalPath]
 *
 * `RestoreRenamed(from, to)` 是一次**移动**:`from` = 改后的名字(起点),`to` = 原名(终点)。
 * 早先两者都写 `path`(= `createdPath` = 新名),推出来的记录是
 * `RestoreRenamed("改后.jpg", "改后.jpg")` —— **空操作**,按它回滚等于什么都没做(缺陷 R1)。
 *
 * 这里**回落**而不是 `requireNotNull`:报告的构造不保证每条成功项都带原名
 * (见 `UndoAlignmentTest` 里"报告 kind 与计划 kind 不一致"那条用例,它刻意用
 * Package 报告配 Rename 计划)。回落成空操作最坏是"没回滚",而抛异常会连累
 * 整个撤销入口;删错文件则不可逆 —— 两害相权,取保守的那个。
 *
 * ## 打包的覆盖项:安全档**不产生**撤销项
 *
 * `Overwrite` 策略 + 输出已存在时,`createdPath` 指向的那个文件**覆盖前就存在**,
 * 它是被**替换**的,不是本次创建的。安全档([UndoScope.ThisRun])因此把它**跳过**:
 * 推成 `DeleteCreated` 就等于"撤销时把用户原有的 CBZ 删掉"(缺陷 R3)。
 *
 * 显式选了 [UndoScope.Everything] 才为它推 [UndoOp.DeleteOverwritten] ——
 * **类型上与普通删除分开**,因为这一删会连用户原有的内容一起抹掉。
 *
 * @return 逆向操作列表;没有可撤销的东西时返回 null
 */
internal fun undoOpsOf(
    report: ExecutionReport,
    kind: ExecKind,
    scope: UndoScope = UndoScope.ThisRun,
): List<UndoOp>? {
    val ops = report.entries.mapNotNull { e ->
        val path = e.createdPath ?: return@mapNotNull null
        val overwritten = e.isOverwrite(kind)
        // 安全档跳过覆盖项:本次没新建任何东西,删它只会删掉用户原有的文件。
        if (overwritten && scope == UndoScope.ThisRun) return@mapNotNull null
        when {
            overwritten -> UndoOp.DeleteOverwritten(path)
            kind == ExecKind.Package -> UndoOp.DeleteCreated(path)
            else -> UndoOp.RestoreRenamed(from = path, to = e.originalPath ?: path)
        }
    }
    return ops.ifEmpty { null }
}

/** 撤销结果。D1 为 mock,故 [applied] 恒为 false,由 [message] 说明原因。 */
data class UndoResult(val applied: Boolean, val message: Msg)

/**
 * 执行撤销(D1:mock)。
 *
 * **为什么不假装撤销成功**:D1 没有真实文件系统,若返回"已撤销",
 * 界面就会显示一个**不存在的**回滚结论 —— 那是在骗用户。
 * 故这里明确返回 `applied = false` 并说明"正式版将基于 UndoRecord 记录逆向操作",
 * 同时把**具体要回滚哪些东西**列出来(`opCount`),让用户知道撤销是有依据的。
 */
fun performUndo(record: UndoRecord?): UndoResult {
    if (record == null || record.ops.isEmpty()) {
        return UndoResult(applied = false, message = Msg(TextKey.UndoNothing))
    }
    return UndoResult(
        applied = false,
        message = Msg(TextKey.UndoRecorded, listOf(record.opCount)),
    )
}
