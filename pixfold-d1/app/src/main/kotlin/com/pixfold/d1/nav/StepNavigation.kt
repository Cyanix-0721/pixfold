package com.pixfold.d1.nav

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
fun NavDestination?.workflowAStepIndex(): Int = when {
    this?.hasRoute<WorkflowANamingRoute>() == true -> 1
    this?.hasRoute<WorkflowAPlanRoute>() == true -> 2
    else -> 0 // 含 null(图尚未建立)与 WorkflowASortRoute
}

/** 工作流 B 的步骤序号。理由同 [workflowAStepIndex]。 */
fun NavDestination?.workflowBStepIndex(): Int = when {
    this?.hasRoute<WorkflowBMetadataRoute>() == true -> 1
    this?.hasRoute<WorkflowBPackRoute>() == true -> 2
    else -> 0 // 含 null 与 WorkflowBLibraryRoute
}

/**
 * 点 Tab 跳转到工作流 A 的某一步,**并保持返回栈是规范的线性链**。
 *
 * **为什么不能直接 `navigate(target)`**:步骤容器是线性流程,返回键的语义是"退一步"。
 * 若在步骤 3 直接 `navigate(WorkflowASortRoute)` 跳到步骤 1,栈会变成
 * `[Sort, Naming, Plan, Sort]`,按一次返回回到 **Plan** —— 与用户的"退一步"预期不符
 * (且 `BackNavigationTest` 用例 5/6 断言了线性后退)。
 * 故**往回跳一律用 pop**,只有往前才 push。
 *
 * **为什么不写成 `List<Any>` 泛型循环**:`navigate(route: Any)` 会在运行期走反射
 * (`route::class.serializer()`)解析 route,丢掉 type-safe 的编译期检查 ——
 * 那正是本次迁移要换掉的东西。显式 `when` 虽然啰嗦,但每个分支都是具体类型。
 */
fun NavController.goToWorkflowAStep(current: Int, target: Int) {
    if (target == current) return
    if (target < current) {
        // 往回:pop 到目标步(不含目标自身,保留它)
        when (target) {
            0 -> popBackStack(WorkflowASortRoute, inclusive = false)
            1 -> popBackStack(WorkflowANamingRoute, inclusive = false)
        }
    } else {
        // 往前:逐级 push,保证中间步骤都在栈上
        if (current < 1 && target >= 1) navigate(WorkflowANamingRoute)
        if (current < 2 && target >= 2) navigate(WorkflowAPlanRoute)
    }
}

/** 工作流 B 的点 Tab 跳转。理由同 [goToWorkflowAStep]。 */
fun NavController.goToWorkflowBStep(current: Int, target: Int) {
    if (target == current) return
    if (target < current) {
        when (target) {
            0 -> popBackStack(WorkflowBLibraryRoute, inclusive = false)
            else -> popBackStack(WorkflowBMetadataRoute, inclusive = false)
        }
    } else {
        if (current < 1 && target >= 1) navigate(WorkflowBMetadataRoute)
        if (current < 2 && target >= 2) navigate(WorkflowBPackRoute)
    }
}
