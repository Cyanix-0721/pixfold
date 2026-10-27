package com.cyanix.pixfold.data.saf

/**
 * 三段式替换的**纯逻辑部分**：把「原包换成新包」这件事从 SAF 调用里剥出来。
 *
 * ## 为什么单独成文件
 *
 * 真机上唯一走不通、且离线完全测不到的就是这段 —— Robolectric 没有真实的
 * `DocumentsProvider`，`renameDocument` / `deleteDocument` 只能回放固定值。
 * 抽成泛型纯函数后，三条路径（甲 / 乙 / 都失败）都能用假 rename/delete 断言，
 * 平台层只剩「把 Uri 接进来」这一层胶水。
 *
 * ## 两条路径（对应 spec Q3 甲的甲、乙）
 *
 * - **甲**：原包改名 `.updating.bak` → 临时包改名原名 → 删备份。原包全程在磁盘上，
 *   任何一步失败都能回滚到起点。
 * - **乙**（甲的第 ① 步走不通时退化）：删原包 → 临时包改名原名。
 *   **Kuro Reader 之类工具产出的 CBZ 常常是 `-r--r--r--`**，某些 provider 会因此拒绝
 *   `renameDocument`（改名被当成写操作）。乙的代价是中间有一段「原包已删、新包还没改名」
 *   的窗口，所以只在甲确实走不通时才用，且失败时必须保住临时包（那是唯一的数据）。
 *
 * ## 为什么新包一定是可写的
 *
 * 两条路径最终占据原名的都是 `createDocument` 新建出来的临时包，**权限由 provider 新建时决定**，
 * 不继承原包的只读位。这正是「只读 CBZ 更新后变可读写」的落地方式 —— 绝不原地覆写原文件
 * （SAF 没有 chmod，写进只读文件既不可能、也不能让它变可写）。
 *
 * @param leftover 替换成功后仍留在磁盘上的备份；null = 已删干净
 * @param viaFallback 是否走了乙（原包是被删掉的，不是被改名的）
 */
internal sealed interface ReplaceOutcome<out T> {

    data class Promoted<T>(
        val leftover: T?,
        val viaFallback: Boolean,
    ) : ReplaceOutcome<T>

    /**
     * @param rolledBack 磁盘是否已回到起点（原名下仍是原包）
     * @param keepTemp   是否**必须保留**临时包（乙路径删了原包又改名失败时，它是唯一的数据）
     */
    data class Failed<T>(
        val rolledBack: Boolean,
        val keepTemp: Boolean,
        val detail: String,
    ) : ReplaceOutcome<T>
}

/**
 * 执行替换。不抛异常：rename / delete 抛出的任何异常都按「这一步失败」处理。
 *
 * @param original   原包
 * @param temp       已经写好新内容的临时包
 * @param targetName 用户看到的包名（`volume.cbzFileName`）
 * @param backupName 备份名（`targetName + ".updating.bak"`）
 * @param tempName   临时包名（`targetName + ".updating"`），失败文案里要告诉用户去找它
 * @param rename     (项, 新名) -> 改名后的项；null / 抛异常 = 失败
 * @param delete     (项) -> 是否删掉
 */
internal fun <T> replaceTarget(
    original: T,
    temp: T,
    targetName: String,
    backupName: String,
    tempName: String,
    rename: (T, String) -> T?,
    delete: (T) -> Boolean,
): ReplaceOutcome<T> {
    // ---- 甲 ①：原包 → 备份 ----
    val backup = attemptRename(rename, original, backupName)
    if (backup != null) {
        // ---- 甲 ②：临时包 → 原名 ----
        if (attemptRename(rename, temp, targetName) != null) {
            // ---- 甲 ③：删备份。删不掉不算失败（包已经是完整的新包了）----
            val deleted = attemptDelete(delete, backup)
            return ReplaceOutcome.Promoted(leftover = if (deleted) null else backup, viaFallback = false)
        }
        // 回滚：把备份改回原名
        val rolledBack = attemptRename(rename, backup, targetName) != null
        return ReplaceOutcome.Failed(
            rolledBack = rolledBack,
            keepTemp = false,
            detail = if (rolledBack) {
                "无法把新包改名为 $targetName（原包已恢复）"
            } else {
                "无法把新包改名为 $targetName，且原包留在 $backupName —— 请手动把它改回原名"
            },
        )
    }

    // ---- 乙：甲走不通（只读包最常撞这里）⇒ 删原包 → 临时包改名 ----
    if (!attemptDelete(delete, original)) {
        return ReplaceOutcome.Failed(
            rolledBack = true,
            keepTemp = false,
            detail = "无法把原包改名为 $backupName，也无法删除它（原包未动，临时包已清理）",
        )
    }
    if (attemptRename(rename, temp, targetName) == null) {
        return ReplaceOutcome.Failed(
            rolledBack = false,
            keepTemp = true,
            detail = "原包已删除，但新包无法改名为 $targetName —— 新包留在 $tempName，请手动改名",
        )
    }
    return ReplaceOutcome.Promoted(leftover = null, viaFallback = true)
}

/** 改名，失败（null 或异常）一律折算成 null —— 调用方只需要「成没成」。 */
private fun <T> attemptRename(rename: (T, String) -> T?, item: T, name: String): T? =
    try {
        rename(item, name)
    } catch (_: Exception) {
        null
    }

/** 删除，异常折算成 false。 */
private fun <T> attemptDelete(delete: (T) -> Boolean, item: T): Boolean =
    try {
        delete(item)
    } catch (_: Exception) {
        false
    }
