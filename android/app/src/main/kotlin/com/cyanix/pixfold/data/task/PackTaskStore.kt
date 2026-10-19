package com.cyanix.pixfold.data.task

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cyanix.pixfold.domain.task.PackTaskSnapshot
import com.cyanix.pixfold.domain.task.PackTaskSnapshotCodec

/**
 * 打包任务快照的落盘（D2b「切后台恢复」）。
 *
 * 用 `SharedPreferences` 而不是文件/DataStore：这份数据是**几个标量加一个短列表**，
 * 而且是**冷启动路径上要同步读**的（决定首页/打包页第一帧显示什么）。DataStore 是
 * 协程 API，读它会把「应用启动后第一帧」变成异步等待；单独开一个文件则要自己管原子
 * 写入。SharedPreferences 的 `commit`/`apply` 语义在这里够用。
 *
 * 与 [com.cyanix.pixfold.data.saf.SafTreeStore] 同一范式：缺失、空值、读不懂一律
 * 返回 `null`（= 没有快照），绝不抛 —— 这段代码在冷启动路径上。
 *
 * ## 谁写谁读
 * - **写**：`PackTaskService` 在任务开始、每次进度推进（节流后）、以及结束时。
 * - **读**：应用启动时的恢复判定（`recoverPackTask`），以及 `PackTaskService`
 *   被系统重启后（那时内存里的请求已经没了，只能靠快照知道「上次跑到哪」）。
 *
 * ## 为什么不在这里判断「要不要恢复」
 * 快照只陈述**事实**（上次停在哪个阶段、处理了几卷）。「该不该提示用户」
 * 是 `recoverPackTask(snapshot, serviceAlive)` 的职责 —— 它需要知道服务是否还活着，
 * 而那是运行期信息，不是落盘数据。
 */
class PackTaskStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读上次的快照。没有、为空、或读不懂都返回 `null`。 */
    fun load(): PackTaskSnapshot? = PackTaskSnapshotCodec.decode(prefs.getString(KEY_SNAPSHOT, null))

    /**
     * 写入快照。
     *
     * 用 `apply()`（`edit { }` 的默认行为）而不是 `commit()`：进度推进时每次
     * 都在主线程上同步落盘会掉帧。代价是「刚写完就断电」可能丢最后一次进度 ——
     * 对恢复提示来说可以接受（最多显示得比实际落后一点，而恢复本来就不自动续跑）。
     * 任务**开始**与**结束**这两个关键时刻由调用方决定是否要更强的保证。
     */
    fun save(snapshot: PackTaskSnapshot) {
        prefs.edit { putString(KEY_SNAPSHOT, PackTaskSnapshotCodec.encode(snapshot)) }
    }

    /** 清掉快照（用户已确认处理完上次中断，或任务正常收尾后不再需要恢复提示时）。 */
    fun clear() {
        prefs.edit { remove(KEY_SNAPSHOT) }
    }

    private companion object {
        const val PREFS_NAME = "pixfold_pack_task"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
