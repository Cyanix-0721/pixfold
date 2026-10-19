package com.cyanix.pixfold.domain.naming

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme

/**
 * 命名结构初值的**唯一**构造入口。
 *
 * ## 为什么根目录名用清洗后的标题
 *
 * `NamingScheme.rootDirName` 是 `RootDir` 组件的取值,而 `RootDir` 组件产出的就是
 * **新文件名**的前缀。按「落盘文件名用清洗后的名字」(2026-10-19 用户裁决),
 * 它该拿 [ImageCollection.derived] 的 `title`,而不是原始目录名。
 *
 * 真机实测(`/storage/emulated/0/test`)暴露过不一致:步骤 2 的标题已清洗成
 * `命名结构 · 彼女がボクの知らないところで（24 张）`,而下面的「新名称」预览仍是
 * `[Cior (Ken-1)] 彼女がボクの知らないところで_001.jpg` —— 同一页同一个名字两个形态。
 *
 * ## 为什么收成一个函数
 *
 * 这个初值曾在 **5 处**被就地构造(`PixFoldApp` 两次 / `WorkflowASteps` 两次 /
 * `NamingPage` 一次)。分散构造意味着「哪一处忘了改」只有真机上才看得出来;
 * 收成单一入口才能被 `NamingSeedTest` 一次钉住。
 *
 * 只取**第一个**集合:命名结构是批次级状态(规格 §6.3),切换集合不该重置它。
 * 空列表给空串 —— 不抛异常,因为「工作区里没有集合」是合法状态。
 *
 * 派生自 [ImageCollection.rootDirName] 而非 [ImageCollection.name]:KDoc 明确两者独立,
 * `rootDirName` 才是「命名组件 rootDir 的取值来源」。当前真实扫描与 mock 两条构造路径
 * 都把两者设成同一个目录名,但顺着契约写,将来若允许单独覆盖根目录名也不会错。
 */
fun namingSchemeSeedOf(collections: List<ImageCollection>): NamingScheme =
    NamingScheme(rootDirName = collections.firstOrNull()?.let { derivedNamesOf(it.rootDirName).title }.orEmpty())
