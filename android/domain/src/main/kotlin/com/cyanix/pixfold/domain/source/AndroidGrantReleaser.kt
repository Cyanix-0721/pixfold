package com.cyanix.pixfold.domain.source

/**
 * 能撤销系统持久授权的能力面。
 *
 * 独立于 [WorkspaceSource] 而不是塞进去：假源（尤其只读的测试替身）不该被迫实现一个
 * 「撤销系统授权」的方法。默认不实现 = 不支持撤销，与「不支持查询授权」的处理方式一致
 * （[WorkspaceSource.watchChanges] 同样是可选能力）。
 *
 * **为什么放在 domain 而不是让控制器直接调平台层**：`WorkspaceController` 在
 * `ui/workspace` 包，必须能在纯 JVM 里测（`AdapterBoundaryTest` 每次闸门都跑
 * 「domain 不得 import android.*」）。控制器只依赖这个接口，由 app 层的
 * `AndroidSafSource` 实现它 —— 撤销授权的 `ContentResolver` 调用因此留在平台层。
 */
interface AndroidGrantReleaser {

    /**
     * 撤销这一条的系统持久授权。
     *
     * 契约：**幂等**，且失败**不得抛出** —— 撤销失败时列表条目已先被移除，
     * 抛出去会让用户看到一次与「删掉一条记录」不相称的崩溃；下次打开列表时
     * `isPersistentGrant` 会把这条如实标成「只记住了路径」。
     */
    fun releasePersistentGrant(treeUri: String)
}
