package com.cyanix.pixfold.data.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三段式替换的**全部分支**，用假磁盘离线断言。
 *
 * ## 为什么要单独测这个
 *
 * 真机上唯一走不通、Robolectric 里又完全测不到的就是这段（没有真实
 * `DocumentsProvider`）。把它抽成纯函数之后，六条路径都能在这里钉死 ——
 * 尤其「只读包」那条：Kuro Reader 之类工具产出的 CBZ 是 `-r--r--r--`，
 * 原包改不了名，只能删掉它再让新建的临时包占原名。
 *
 * 测试里的假磁盘**不是**在模拟 SAF 的实现细节，只是在验证分支逻辑：
 * 哪个操作返回 null / false 时，磁盘该停在什么状态。
 */
class SafReplaceTargetTest {

    /** 模拟一个「项」：只有名字，够用来断言磁盘上还剩什么。 */
    private data class FakeFile(val name: String)

    /**
     * 假磁盘。
     *
     * @param renameBlocked 返回 true 表示这一步改名会失败（provider 拒绝 ⇒ null）
     * @param deleteBlocked 返回 true 表示这一步删除会失败（⇒ false）
     * @param throwsInsteadOfReturning 模拟 provider 直接抛 SecurityException（只读卷常见）
     */
    private class FakeDisk(
        var renameBlocked: (FakeFile, String) -> Boolean = { _, _ -> false },
        var deleteBlocked: (FakeFile) -> Boolean = { false },
        var throwsInsteadOfReturning: Boolean = false,
    ) {
        val files = mutableListOf<FakeFile>()
        val ops = mutableListOf<String>()

        fun rename(item: FakeFile, newName: String): FakeFile? {
            ops += "rename:${item.name}->$newName"
            if (throwsInsteadOfReturning) throw SecurityException("read-only")
            if (renameBlocked(item, newName)) return null
            files.remove(item)
            val moved = FakeFile(newName)
            files += moved
            return moved
        }

        fun delete(item: FakeFile): Boolean {
            ops += "delete:${item.name}"
            if (throwsInsteadOfReturning) throw SecurityException("read-only")
            if (deleteBlocked(item)) return false
            return files.remove(item)
        }

        fun names(): List<String> = files.map { it.name }.sorted()
    }

    private val targetName = "a.cbz"
    private val backupName = "a.cbz.updating.bak"
    private val tempName = "a.cbz.updating"

    private fun run(disk: FakeDisk): ReplaceOutcome<FakeFile> {
        val original = FakeFile(targetName)
        val temp = FakeFile(tempName)
        disk.files += original
        disk.files += temp
        return replaceTarget(
            original = original,
            temp = temp,
            targetName = targetName,
            backupName = backupName,
            tempName = tempName,
            rename = disk::rename,
            delete = disk::delete,
        )
    }

    @Test
    fun `a writable target is replaced through a backup`() {
        val disk = FakeDisk()
        val outcome = run(disk)

        val promoted = outcome as ReplaceOutcome.Promoted
        assertEquals("不该退化到乙路径", false, promoted.viaFallback)
        assertNull("备份应当被删掉", promoted.leftover)
        assertEquals("磁盘上只该剩新包", listOf(targetName), disk.names())
        assertEquals(
            "顺序必须是 原包改名 → 新包改名 → 删备份",
            listOf(
                "rename:$targetName->$backupName",
                "rename:$tempName->$targetName",
                "delete:$backupName",
            ),
            disk.ops,
        )
    }

    @Test
    fun `a backup that cannot be deleted still counts as success but is reported`() {
        // 甲 ① ② 都成，只有删备份失败 —— 包已经是完整的新包了，不该报失败；
        // 但「备份还在」必须交回调用方（它要打日志，将来可能要提示用户）。
        val disk = FakeDisk(deleteBlocked = { file -> file.name == backupName })
        val outcome = run(disk)

        val promoted = outcome as ReplaceOutcome.Promoted
        assertEquals("不该退化到乙路径", false, promoted.viaFallback)
        assertEquals("备份没删掉，必须交回调用方", backupName, promoted.leftover?.name)
        assertEquals("原名下是新包，备份还留着", listOf(targetName, backupName), disk.names())
    }

    @Test
    fun `a read-only target falls back to delete then rename`() {
        // 只读包：改名被拒，删除可以（provider 允许删，只是不让 rename）
        val disk = FakeDisk(renameBlocked = { file, _ -> file.name == targetName })
        val outcome = run(disk)

        val promoted = outcome as ReplaceOutcome.Promoted
        assertTrue("必须走乙路径", promoted.viaFallback)
        assertNull(promoted.leftover)
        assertEquals("原包已删、新包占了原名", listOf(targetName), disk.names())
        assertEquals(
            listOf(
                "rename:$targetName->$backupName",
                "delete:$targetName",
                "rename:$tempName->$targetName",
            ),
            disk.ops,
        )
    }

    @Test
    fun `when even deleting is refused the original is untouched`() {
        // 彻底不可写：改名被拒、删除也被拒 ⇒ 一个字节都不能动
        val disk = FakeDisk(
            renameBlocked = { _, _ -> true },
            deleteBlocked = { file -> file.name == targetName },
        )
        val outcome = run(disk)

        val failed = outcome as ReplaceOutcome.Failed
        assertTrue("磁盘必须回到起点", failed.rolledBack)
        assertEquals("临时包由调用方清理，这里不该保留", false, failed.keepTemp)
        assertTrue("原包必须还在", targetName in disk.names())
        assertTrue(
            "文案要告诉用户原包没动：${failed.detail}",
            failed.detail.contains("原包未动"),
        )
    }

    @Test
    fun `a provider that throws is treated as a refusal`() {
        // 有些 provider 对只读文件不是返回 null 而是抛 SecurityException
        val disk = FakeDisk(throwsInsteadOfReturning = true)
        val outcome = run(disk)

        val failed = outcome as ReplaceOutcome.Failed
        assertTrue("磁盘必须回到起点", failed.rolledBack)
        assertTrue("原包必须还在", targetName in disk.names())
    }

    @Test
    fun `a failed promotion rolls the backup back to the original name`() {
        // 甲 ①成功、②失败、回滚成功 ⇒ 磁盘回到起点
        val disk = FakeDisk(renameBlocked = { file, newName -> file.name == tempName && newName == targetName })
        val outcome = run(disk)

        val failed = outcome as ReplaceOutcome.Failed
        assertTrue("回滚必须成功", failed.rolledBack)
        assertEquals("临时包由调用方清理，这里不保留", false, failed.keepTemp)
        assertTrue("原包必须回到原名下", targetName in disk.names())
        assertTrue(
            "文案要说明原包已恢复：${failed.detail}",
            failed.detail.contains("原包已恢复"),
        )
    }

    @Test
    fun `a failed rollback tells the user where the backup is`() {
        // 甲 ①成功、②失败、回滚也失败 ⇒ 原包留在 .updating.bak，必须说清位置
        val disk = FakeDisk(
            renameBlocked = { file, newName ->
                // 新包占原名失败，且备份改回原名也失败
                newName == targetName
            },
        )
        val outcome = run(disk)

        val failed = outcome as ReplaceOutcome.Failed
        assertEquals("磁盘没回到起点", false, failed.rolledBack)
        assertTrue("原包留在备份名下", backupName in disk.names())
        assertTrue("原名下不该有新包", targetName !in disk.names())
        assertTrue(
            "文案必须给出备份名：${failed.detail}",
            failed.detail.contains(backupName),
        )
    }

    @Test
    fun `the temp archive is kept when it is the only copy left`() {
        // 乙：原包已删，新包改名又失败 ⇒ 临时包是唯一的数据，绝不能删
        val disk = FakeDisk(
            renameBlocked = { file, newName ->
                // 原包改不了名（走乙），新包也改不了名
                file.name == targetName || newName == targetName
            },
        )
        val outcome = run(disk)

        val failed = outcome as ReplaceOutcome.Failed
        assertEquals("原包已经删了，回不去", false, failed.rolledBack)
        assertTrue("临时包必须保留", failed.keepTemp)
        assertEquals("磁盘上只剩临时包", listOf(tempName), disk.names())
        assertTrue(
            "文案必须告诉用户新包在哪：${failed.detail}",
            failed.detail.contains(tempName),
        )
    }
}
