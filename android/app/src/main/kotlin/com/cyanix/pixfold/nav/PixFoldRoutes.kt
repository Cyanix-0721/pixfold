package com.cyanix.pixfold.nav

import androidx.navigation3.runtime.NavKey
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
 * **2026-11-14 起实现 `NavKey`**:Navigation 3 要求每个目的地是 `NavKey`,由
 * `NavBackStack<NavKey>` 承载;不再有字符串 route,`indexOf(key)` / 类型判断都保持编译期安全。
 *
 * 约定:
 * - **无参目的地用 `data object`**;
 * - **带参目的地用 `data class`**;
 * - 每个目的地**单独一个类型**,这样 `indexOf(具体实例)` 才能精确指向它 ——
 *   若把步骤做成 `Step(index: Int)` 这种单一带参 route,所有步骤都是同类实例,无法区分。
 */

// ---------------------------------------------------------------- 根导航(6 个目的地)

/** 首页(导航图起点)。在首页按返回 = 交回系统(退出应用)。 */
@Serializable
data object HomeRoute : NavKey

/** 设置页。 */
@Serializable
data object SettingsRoute : NavKey

/** 开放源代码许可页(从设置页进入)。 */
@Serializable
data object LicensesRoute : NavKey

/** 工作流 A:图片整理与命名。 */
@Serializable
data object OrganizeRoute : NavKey

/** 工作流 B:CBZ 制作。 */
@Serializable
data object LibraryRoute : NavKey

/**
 * 图片预览。
 *
 * @param index 首屏显示的图片下标(来自当前集合)。
 *   迁移前它由 `Screen.Preview(index)` 携带,现改为 route 参数,
 *   由目的地 lambda 直接收到 `PreviewRoute` 实例,无需再 `toRoute()`。
 */
@Serializable
data class PreviewRoute(val index: Int) : NavKey

// ------------------------------------------------- 工作流 A 的步骤(独立 NavDisplay 栈,3 个目的地)

/** 工作流 A · 步骤1 排序与预览。 */
@Serializable
data object OrganizeSortRoute : NavKey

/** 工作流 A · 步骤2 命名结构。 */
@Serializable
data object OrganizeNamingRoute : NavKey

/** 工作流 A · 步骤3 执行计划。 */
@Serializable
data object OrganizePlanRoute : NavKey

// ------------------------------------------------- 工作流 B 的步骤(独立 NavDisplay 栈,3 个目的地)

/** 工作流 B · 步骤1 漫画库。 */
@Serializable
data object LibraryShelfRoute : NavKey

/** 工作流 B · 步骤2 元数据。 */
@Serializable
data object LibraryMetadataRoute : NavKey

/** 工作流 B · 步骤3 打包计划。 */
@Serializable
data object LibraryPackRoute : NavKey
