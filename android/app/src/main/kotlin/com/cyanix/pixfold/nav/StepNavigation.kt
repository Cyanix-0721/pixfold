package com.cyanix.pixfold.nav

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * 工作流 A 的**步骤序号**,由导航栈派生。
 *
 * **为什么不另存一个 `Int`**:迁移前用 `var stepIndex by remember { mutableIntStateOf(0) }`,
 * 于是"当前步骤"和"返回栈"是**两份状态**,Tab 点击只改前者 —— 返回时只能靠
 * `stepIndex -= 1` 猜。改用 NavHost 后,返回栈是唯一事实来源,序号必须从它读出来。
 *
 * **为什么用手势/转场也安全**:返回手势进行中 `NavHost` 的 currentBackStackEntry 不变
 * (只有 commit 时才 pop),所以序号在整段手势里是稳定的 —— 不会出现"卡在 1.5 步"。
 */
fun NavDestination?.organizeStepIndex(): Int = when {
    this?.hasRoute<OrganizeNamingRoute>() == true -> 1
    this?.hasRoute<OrganizePlanRoute>() == true -> 2
    else -> 0 // 含 null(图尚未建立)与 OrganizeSortRoute
}

/** 工作流 B 的步骤序号。理由同 [organizeStepIndex]。 */
fun NavDestination?.libraryStepIndex(): Int = when {
    this?.hasRoute<LibraryMetadataRoute>() == true -> 1
    this?.hasRoute<LibraryPackRoute>() == true -> 2
    else -> 0 // 含 null 与 LibraryShelfRoute
}

/**
 * 步骤底栏的「返回」——**只在嵌套栈里确实还有上一步时才弹**。
 *
 * ## 为什么不能直接 `popBackStack()`(2026-10-14 真机缺陷)
 *
 * 用户在步骤 2 **连点两次**底栏左下角「返回排序」⇒ 步骤区**内容全空**,只剩顶部三个
 * Tab;此后点「步骤 1」也**回不来**(点步骤 2/3 才能恢复)。真机量化:两次点击间隔
 * **≤0.15s 必炸**、≥0.25s 正常 —— 正是退场转场(约 200ms)的窗口;间隔 0.05s 也中,
 * 故这是"双击这个按钮必炸",不是手抖。
 *
 * 根因是**两层叠加**:
 * 1. 嵌套图的**起始目的地(步骤 1)与后续步骤在同一个返回栈里**。第一次 pop 只让
 *    命名页进入**退场转场** —— 退场中的页面仍在组合树上、按钮仍可命中,于是第二次
 *    点击落在同一个按钮上,把**步骤 1 也弹掉了** ⇒ 栈空 ⇒ NavHost 无内容可组合。
 * 2. `organizeStepIndex()` 对 null 目的地返回 0 ⇒ 栈空时"当前步骤"读作 0,点
 *    「步骤 1」算出 `target == current` 被 [goToOrganizeStep] 直接 return ⇒ 卡死。
 *
 * ⚠️ 本组件注释里那句「内层栈深为 1 时自动让位给外层」保护的是**系统返回手势**
 * (NavHost 的 `enabled = currentBackStack.size > 1`),**页面底栏的按钮绕过了那道护栏**
 * —— 两处入口各有一条路,只堵手势那条是不够的。
 *
 * ## 判据为什么用 `previousBackStackEntry`
 *
 * 它读的是**本 NavController 自己**的栈:当前停在起始目的地时为 null。`popBackStack()`
 * 是**同步**更新返回栈的(只有视觉转场在延续),所以第二次点击时判据已经反映"栈里
 * 只剩起始目的地",拦得住 —— 这正是缺陷的触发机制反过来用作护栏。
 *
 * @return 是否真的弹出了一层(未弹出时返回 false,调用方可据此决定是否消费事件)。
 */
fun NavController.popWorkflowStep(): Boolean {
    if (previousBackStackEntry == null) return false
    return popBackStack()
}

/**
 * 点 Tab 跳转到工作流 A 的某一步,**并保持返回栈是规范的线性链**。
 *
 * **为什么不能直接 `navigate(target)`**:步骤容器是线性流程,返回键的语义是"退一步"。
 * 若在步骤 3 直接 `navigate(OrganizeSortRoute)` 跳到步骤 1,栈会变成
 * `[Sort, Naming, Plan, Sort]`,按一次返回回到 **Plan** —— 与用户的"退一步"预期不符
 * (且 `BackNavigationTest` 用例 5/6 断言了线性后退)。
 * 故**往回跳一律用 pop**,只有往前才 push。
 *
 * **为什么不写成 `List<Any>` 泛型循环**:`navigate(route: Any)` 会在运行期走反射
 * (`route::class.serializer()`)解析 route,丢掉 type-safe 的编译期检查 ——
 * 那正是本次迁移要换掉的东西。显式 `when` 虽然啰嗦,但每个分支都是具体类型。
 */
fun NavController.goToOrganizeStep(current: Int, target: Int) {
    if (target == current) return
    if (target < current) {
        // 往回:pop 到目标步(不含目标自身,保留它)
        when (target) {
            0 -> popBackStack(OrganizeSortRoute, inclusive = false)
            1 -> popBackStack(OrganizeNamingRoute, inclusive = false)
        }
    } else {
        // 往前:逐级 push,保证中间步骤都在栈上
        if (current < 1 && target >= 1) navigate(OrganizeNamingRoute)
        if (current < 2 && target >= 2) navigate(OrganizePlanRoute)
    }
}

/** 工作流 B 的点 Tab 跳转。理由同 [goToOrganizeStep]。 */
fun NavController.goToLibraryStep(current: Int, target: Int) {
    if (target == current) return
    if (target < current) {
        when (target) {
            0 -> popBackStack(LibraryShelfRoute, inclusive = false)
            else -> popBackStack(LibraryMetadataRoute, inclusive = false)
        }
    } else {
        if (current < 1 && target >= 1) navigate(LibraryMetadataRoute)
        if (current < 2 && target >= 2) navigate(LibraryPackRoute)
    }
}
