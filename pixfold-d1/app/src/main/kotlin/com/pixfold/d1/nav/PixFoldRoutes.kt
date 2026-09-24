package com.pixfold.d1.nav

import kotlinx.serialization.Serializable

/**
 * 应用全部导航目的地(type-safe route)。
 *
 * **为什么用 type-safe 而非字符串 route**(用户 2026-09-22 裁决,依据见
 * `docs/notes/d1-navigation-compose-migration.md` §4.1 第 1 项):
 * 本次迁移本来就要重写全部导航调用点与 15 条返回相关测试,此刻顺手定 type-safe 的增量
 * 只有一个插件 + 一行依赖;将来再单独换,是把同一批文件再翻一遍。
 * 收益是**编译期**校验 route 类型与参数 —— 字符串 route 的参数名/类型写错只在运行期崩溃。
 *
 * 约定:
 * - **无参目的地用 `data object`**(官方 type-safe 文档:Object = 无参 route);
 * - **带参目的地用 `data class`**;
 * - 每个目的地**单独一个类型**,这样 `popUpTo<T>()` / `hasRoute<T>()` 能精确指向它 ——
 *   若把步骤做成 `Step(index: Int)` 这种单一带参 route,`popUpTo<Step>` 会同时匹配所有步骤,无法区分。
 */

// ---------------------------------------------------------------- 根导航(6 个目的地)

/** 首页(导航图起点)。在首页按返回 = 交回系统(退出应用)。 */
@Serializable
data object HomeRoute

/** 设置页。 */
@Serializable
data object SettingsRoute

/** 开放源代码许可页(从设置页进入)。 */
@Serializable
data object LicensesRoute

/** 工作流 A:图片整理与命名。 */
@Serializable
data object WorkflowARoute

/** 工作流 B:CBZ 制作。 */
@Serializable
data object WorkflowBRoute

/**
 * 图片预览。
 *
 * @param index 首屏显示的图片下标(来自当前集合)。
 *   迁移前它由 `Screen.Preview(index)` 携带,现改为 route 参数,
 *   在目的地内经 `NavBackStackEntry.toRoute<PreviewRoute>()` 取回。
 */
@Serializable
data class PreviewRoute(val index: Int)

// ------------------------------------------------- 工作流 A 的步骤(嵌套导航图,3 个目的地)

/** 工作流 A · 步骤1 排序与预览。 */
@Serializable
data object WorkflowASortRoute

/** 工作流 A · 步骤2 命名结构。 */
@Serializable
data object WorkflowANamingRoute

/** 工作流 A · 步骤3 执行计划。 */
@Serializable
data object WorkflowAPlanRoute

// ------------------------------------------------- 工作流 B 的步骤(嵌套导航图,3 个目的地)

/** 工作流 B · 步骤1 漫画库。 */
@Serializable
data object WorkflowBLibraryRoute

/** 工作流 B · 步骤2 元数据。 */
@Serializable
data object WorkflowBMetadataRoute

/** 工作流 B · 步骤3 打包计划。 */
@Serializable
data object WorkflowBPackRoute
