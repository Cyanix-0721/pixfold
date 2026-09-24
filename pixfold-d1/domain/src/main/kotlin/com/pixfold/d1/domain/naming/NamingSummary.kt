package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NamingScheme

/**
 * 命名结构编辑器**折叠时那一行摘要**的文案(纯函数,便于单测)。
 *
 * 形态:`<首个建议名> · <N 个组件启用>`,例如 `旅行照片_001.jpg · 2 个组件启用`。
 *
 * **为什么用真实建议名而不是结构模板**(如「根目录名_序号.扩展名」):
 * 用户核对结构时想看的是"按这个结构改完,名字长什么样" —— 具体样本可以直接验,
 * 占位符还要在脑子里翻译一遍;且样本与建议表**同源**(都来自 [buildProposals]),
 * 结构上排除了"摘要与表格不一致"这类偏差。
 *
 * @param sample 首个建议名(含逐项覆盖,即 `NameProposal.finalName`);
 *   集合为空(没有可命名的图片)时为 null,此时只报组件数。
 */
fun namingSummaryLine(scheme: NamingScheme, sample: String?): String {
    val enabled = scheme.components.count { it.enabled }
    val head = sample?.takeIf { it.isNotBlank() }
    return if (head == null) "$enabled 个组件启用" else "$head · $enabled 个组件启用"
}
