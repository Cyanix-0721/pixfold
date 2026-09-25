package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 命名结构编辑器**折叠时那一行摘要**的文案(纯函数,便于单测)。
 *
 * 形态:`<首个建议名> · <N 个组件启用>[ · <含 N 处手工调整>]`,
 * 例如 `旅行照片_001.jpg · 2 个组件启用`,或用户拖拽过之后
 * `旅行照片_001.jpg · 2 个组件启用 · 含 3 处手工调整`。
 *
 * **为什么用真实建议名而不是结构模板**(如「根目录名_序号.扩展名」):
 * 用户核对结构时想看的是"按这个结构改完,名字长什么样" —— 具体样本可以直接验,
 * 占位符还要在脑子里翻译一遍;且样本与建议表**同源**(都来自 [buildProposals]),
 * 结构上排除了"摘要与表格不一致"这类偏差。
 *
 * **为什么返回 `List<Msg>` 而不是单个 `Msg`**:摘要由若干**段**拼成,段与段之间的
 * 分隔符是**语言相关的**(中文「·」两侧的留白习惯与英文不同),而领域层拿不到资源。
 * 故领域层只负责「有哪些段、每段的键与参数」,由界面层现场连接 ——
 * 与 `PackPreviewPage` 拼接 `pageIssues` 等既有做法一致。
 *
 * **为什么披露"手工调整"(HANGOFF §11 第 10 条,2026-09-25 用户裁决)**:
 * 第 1 步(排序页)已经有同口径的「已人工调整 N 处」,而第 2 步此前**完全沉默**
 * —— 两处对同一件事一个说、一个不说,是操作逻辑上的不一致。更要紧的是
 * **命名序号跟随页序**(不是原始文件名里的序号),所以"上一步拖过几张"这件事
 * 会直接改变这里的建议名;它翻页之后就被藏起来了,需要一行**被动披露**把它带回视野。
 *
 * 只读、不打扰、不加第二入口(约束 3:预览是主工作区;弹窗式提醒已否决)。
 * `manualCount == 0` 时不多出一个段,摘要与改动前**逐字一致**。
 *
 * @param sample 首个建议名(含逐项覆盖,即 `NameProposal.finalName`);
 *   集合为空(没有可命名的图片)时为 null,此时只报组件数。
 * @param manualCount 当前集合被人工拖拽过的项数(`PageOrderState.manualCount`);
 *   为 0(含负数这类不可能值)时不追加手工调整段。
 */
fun namingSummaryLine(scheme: NamingScheme, sample: String?, manualCount: Int = 0): List<Msg> {
    val enabled = scheme.components.count { it.enabled }
    val head = sample?.takeIf { it.isNotBlank() }
    val base = if (head == null) {
        Msg(TextKey.NamingSummaryEnabled, listOf(enabled))
    } else {
        Msg(TextKey.NamingSummaryWithHead, listOf(head, enabled))
    }
    return if (manualCount > 0) {
        listOf(base, Msg(TextKey.NamingSummaryManual, listOf(manualCount)))
    } else {
        listOf(base)
    }
}
