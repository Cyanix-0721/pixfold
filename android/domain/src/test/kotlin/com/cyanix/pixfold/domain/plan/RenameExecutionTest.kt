package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.model.NameProposal
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.naming.OpStatus
import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.naming.buildRenamePlan
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D4b 真实改名/撤销的执行逻辑。
 *
 * 这里**不碰文件系统** —— [RenameActions] / [UndoActions] 都是端口,测试传内存实现。
 * 平台层实现(`SafRenameActions` / `SafUndoActions`)由真机核对覆盖。
 */
class RenameExecutionTest {

    // ---------- 夹具 ----------

    private fun item(
        id: String,
        name: String,
        uri: String? = "content://doc/$id",
        collectionId: String = "c1",
    ): SourceItem = SourceItem(
        id = id,
        collectionId = collectionId,
        dir = "",
        baseName = name.substringBeforeLast('.'),
        ext = name.substringAfterLast('.'),
        sizeBytes = 1024,
        modifiedEpochMillis = 0,
        createdEpochMillis = null,
        seed = id.hashCode(),
        uri = uri,
    )

    private fun op(
        id: String,
        current: String,
        target: String,
        uri: String? = "content://doc/$id",
        status: OpStatus = OpStatus.Ok,
    ): RenameOp = RenameOp(
        proposal = NameProposal(
            image = item(id, current, uri),
            proposedName = target,
        ),
        status = status,
        reason = if (status == OpStatus.Skipped) com.cyanix.pixfold.domain.text.Msg(TextKey.WarnDuplicate) else null,
    )

    /** 记录调用并按脚本回答的内存实现。 */
    private class FakeRenameActions(
        private val answer: (String, String) -> String? = { uri, _ -> "$uri#renamed" },
    ) : RenameActions {
        val calls = mutableListOf<Pair<String, String>>()
        override fun rename(uri: String, newName: String): String? {
            calls += uri to newName
            return answer(uri, newName)
        }
    }

    // ---------- executeRenamePlanReal ----------

    @Test
    fun `a successful rename records both paths and the real uri`() {
        val actions = FakeRenameActions { uri, _ -> "$uri#after" }
        val report = executeRenamePlanReal(
            listOf(op("a", "001.jpg", "新_001.jpg")),
            actions,
        )

        val entry = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, entry.outcome)
        assertEquals("新_001.jpg", entry.createdPath)
        assertEquals("001.jpg", entry.originalPath)
        // 撤销要靠这个 uri 落到真实文件上 —— 必须是平台返回的新 uri,不是旧的。
        assertEquals("content://doc/a#after", entry.uri)
        assertEquals(listOf("content://doc/a" to "新_001.jpg"), actions.calls)
    }

    @Test
    fun `a platform failure reports io failure and keeps the original name as label`() {
        val actions = FakeRenameActions { _, _ -> null }
        val report = executeRenamePlanReal(listOf(op("a", "001.jpg", "新_001.jpg")), actions)

        val entry = report.entries.single()
        assertEquals(ExecOutcome.Failed, entry.outcome)
        // 失败时磁盘上还是旧名字,标签必须是旧名 —— 用户按它去文件管理器里找。
        assertEquals("001.jpg", entry.label)
        assertEquals(TextKey.RenameFailIo, entry.reason?.key)
        assertNull(entry.uri, "失败项不得带 uri,否则撤销会去动一个没改过的文件")
    }

    @Test
    fun `a skipped op never reaches the platform`() {
        val actions = FakeRenameActions()
        val report = executeRenamePlanReal(
            listOf(op("a", "001.jpg", "新_001.jpg", status = OpStatus.Skipped)),
            actions,
        )

        assertEquals(ExecOutcome.Skipped, report.entries.single().outcome)
        assertTrue(actions.calls.isEmpty(), "计划说跳过就不该去动文件")
    }

    @Test
    fun `a blank target name fails without touching the platform`() {
        val actions = FakeRenameActions()
        val report = executeRenamePlanReal(listOf(op("a", "001.jpg", "  ")), actions)

        val entry = report.entries.single()
        assertEquals(ExecOutcome.Failed, entry.outcome)
        assertEquals(TextKey.RenameFailEmptyName, entry.reason?.key)
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `an item without a uri is reported as a failure rather than a skip`() {
        val actions = FakeRenameActions()
        val report = executeRenamePlanReal(
            listOf(op("a", "001.jpg", "新_001.jpg", uri = null)),
            actions,
        )

        val entry = report.entries.single()
        // 跳过 = 计划定的(用户看得到原因);缺 uri = 执行环境缺信息,用户无从预期。
        assertEquals(ExecOutcome.Failed, entry.outcome)
        assertEquals(TextKey.RenameFailNoUri, entry.reason?.key)
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a throwing platform implementation is contained per item`() {
        val actions = FakeRenameActions { _, _ -> throw IllegalStateException("provider 炸了") }
        val report = executeRenamePlanReal(
            listOf(op("a", "001.jpg", "x.jpg"), op("b", "002.jpg", "y.jpg")),
            actions,
        )

        // 一项抛异常不该让整个执行崩掉 —— 用户仍要看到逐项结果。
        assertEquals(2, report.failedCount)
        assertEquals(2, actions.calls.size)
    }

    @Test
    fun `the report shape matches the simulated execution`() {
        // 同一条计划走模拟与真实两条路,条目数、顺序、每次的 id 必须一致 ——
        // 否则报告页的「第 N 行 ↔ 计划第 N 行」契约会在切到真实路径时失配。
        val ops = listOf(
            op("a", "001.jpg", "x.jpg"),
            op("b", "002.jpg", "y.jpg", status = OpStatus.Skipped),
            op("c", "003.jpg", "  "),
        )
        val simulated = executeRenamePlan(ops)
        val real = executeRenamePlanReal(ops, FakeRenameActions())

        assertEquals(simulated.entries.map { it.id }, real.entries.map { it.id })
        assertEquals(simulated.entries.map { it.outcome }, real.entries.map { it.outcome })
        assertEquals(simulated.kind, real.kind)
    }

    // ---------- buildRenamePlan 与真实执行衔接 ----------

    @Test
    fun `rename plan ops carry the image uri through to the report`() {
        val proposals = listOf(
            NameProposal(image = item("a", "001.jpg", uri = "content://doc/real-a"), proposedName = "新_001.jpg"),
        )
        val ops = buildRenamePlan(proposals)
        val actions = FakeRenameActions { _, _ -> "content://doc/renamed" }
        val report = executeRenamePlanReal(ops, actions)

        assertEquals(listOf("content://doc/real-a" to "新_001.jpg"), actions.calls)
        assertNotNull(report.entries.single().uri)
    }

    // ---------- performUndoReal ----------

    private class FakeUndoActions(
        private val deleteAnswer: (String) -> Boolean = { true },
        private val renameAnswer: (String, String) -> Boolean = { _, _ -> true },
    ) : UndoActions {
        val deleted = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        override fun delete(uri: String): Boolean {
            deleted += uri
            return deleteAnswer(uri)
        }

        override fun rename(uri: String, newName: String): Boolean {
            renamed += uri to newName
            return renameAnswer(uri, newName)
        }
    }

    @Test
    fun `undo deletes the files this run created`() {
        val actions = FakeUndoActions()
        val record = UndoRecord(
            ops = listOf(
                UndoOp.DeleteCreated("a.cbz", uri = "content://doc/a"),
                UndoOp.DeleteCreated("b.cbz", uri = "content://doc/b"),
            ),
            kind = ExecKind.Package,
        )

        val result = performUndoReal(record, actions)

        assertTrue(result.applied)
        assertEquals(listOf("content://doc/a", "content://doc/b"), actions.deleted)
        assertEquals(TextKey.UndoApplied, result.message.key)
        assertEquals(listOf(2), result.message.args)
    }

    @Test
    fun `undo renames restored files back to their original names`() {
        val actions = FakeUndoActions()
        val record = UndoRecord(
            ops = listOf(
                UndoOp.RestoreRenamed(from = "新_001.jpg", to = "001.jpg", uri = "content://doc/a"),
            ),
            kind = ExecKind.Rename,
        )

        val result = performUndoReal(record, actions)

        assertTrue(result.applied)
        // 终点必须是原名 —— 早先的 R1 缺陷就是这里写成了新名,推出空操作。
        assertEquals(listOf("content://doc/a" to "001.jpg"), actions.renamed)
    }

    @Test
    fun `undo deletes overwritten files only when the scope asked for it`() {
        val actions = FakeUndoActions()
        val record = UndoRecord(
            ops = listOf(UndoOp.DeleteOverwritten("same.cbz", uri = "content://doc/x")),
            kind = ExecKind.Package,
        )

        performUndoReal(record, actions)

        assertEquals(listOf("content://doc/x"), actions.deleted, "危险档的记录本来就该删")
    }

    @Test
    fun `a partial undo reports both counts`() {
        val actions = FakeUndoActions(deleteAnswer = { it.endsWith("/b") })
        val record = UndoRecord(
            ops = listOf(
                UndoOp.DeleteCreated("a.cbz", uri = "content://doc/a"),
                UndoOp.DeleteCreated("b.cbz", uri = "content://doc/b"),
            ),
            kind = ExecKind.Package,
        )

        val result = performUndoReal(record, actions)

        assertFalse(result.applied, "有失败项就不算完成")
        assertEquals(TextKey.UndoAppliedPartly, result.message.key)
        // 只报"撤销了 1 项"会让用户以为撤销本身有问题;必须同时给出总数。
        assertEquals(listOf(1, 2), result.message.args)
    }

    @Test
    fun `an op without a uri counts as failed instead of silently skipped`() {
        val actions = FakeUndoActions()
        val record = UndoRecord(
            ops = listOf(
                UndoOp.DeleteCreated("a.cbz", uri = null),
                UndoOp.DeleteCreated("b.cbz", uri = "content://doc/b"),
            ),
            kind = ExecKind.Package,
        )

        val result = performUndoReal(record, actions)

        // 静默跳过会让「撤销 N 项」虚高 —— 与 UndoRecord 只收成功项同一取舍。
        assertEquals(listOf(1, 2), result.message.args)
        assertEquals(listOf("content://doc/b"), actions.deleted)
    }

    @Test
    fun `an empty record reports nothing to undo and touches nothing`() {
        val actions = FakeUndoActions()

        val result = performUndoReal(null, actions)

        assertFalse(result.applied)
        assertEquals(TextKey.UndoNothing, result.message.key)
        assertTrue(actions.deleted.isEmpty())
        assertTrue(actions.renamed.isEmpty())
    }

    @Test
    fun `a throwing undo implementation is contained per op`() {
        val actions = object : UndoActions {
            override fun delete(uri: String): Boolean = throw IllegalStateException("炸")
            override fun rename(uri: String, newName: String): Boolean = true
        }
        val record = UndoRecord(
            ops = listOf(
                UndoOp.DeleteCreated("a.cbz", uri = "content://doc/a"),
                UndoOp.DeleteCreated("b.cbz", uri = "content://doc/b"),
            ),
            kind = ExecKind.Package,
        )

        val result = performUndoReal(record, actions)

        // 一项抛异常不能带走其余项 —— 已经删掉的文件不会因为后面失败而回来。
        assertFalse(result.applied)
        assertEquals(listOf(0, 2), result.message.args)
    }

    // ---------- 与模拟路径的一致性 ----------

    @Test
    fun `undo built from a real rename report carries the uri`() {
        val actions = FakeRenameActions { _, _ -> "content://doc/renamed" }
        val report = executeRenamePlanReal(listOf(op("a", "001.jpg", "新_001.jpg")), actions)

        val record = undoRecordOf(report)

        assertNotNull(record)
        val op0 = record.ops.single()
        assertTrue(op0 is UndoOp.RestoreRenamed)
        assertEquals("content://doc/renamed", op0.uri)
        assertEquals("001.jpg", op0.to)
    }

    @Test
    fun `undo built from a simulated report has no uri`() {
        val report = executeRenamePlan(listOf(op("a", "001.jpg", "新_001.jpg")))

        val record = undoRecordOf(report)

        // D1 的模拟报告产不出 uri —— 这是显式事实,不是靠路径字符串去猜。
        assertNotNull(record)
        assertNull(record.ops.single().uri)
    }
    // ---------- 进度与中断(D4b 真机 ANR 修复的领域侧) ----------

    @Test
    fun `progress is reported once per item and ends at the total`() {
        val seen = mutableListOf<TaskProgress>()
        executeRenamePlanReal(
            listOf(
                op("a", "001.jpg", "新_001.jpg"),
                op("b", "002.jpg", "新_002.jpg"),
                op("c", "003.jpg", "新_003.jpg"),
            ),
            FakeRenameActions(),
            onProgress = { seen += it },
        )

        assertEquals(listOf(1, 2, 3), seen.map { it.processed }, "每处理一项就该上报一次")
        assertEquals(listOf(3, 3, 3), seen.map { it.total })
        assertEquals(3, seen.last().done)
    }

    @Test
    fun `progress counts failures and skips as processed`() {
        val seen = mutableListOf<TaskProgress>()
        executeRenamePlanReal(
            listOf(
                op("ok", "001.jpg", "新_001.jpg"),
                op("bad", "002.jpg", "新_002.jpg", uri = null),
                op("skip", "003.jpg", "新_003.jpg", status = OpStatus.Skipped),
            ),
            FakeRenameActions(),
            onProgress = { seen += it },
        )

        val last = seen.last()
        // 三条都被「处理过了」—— 若失败/跳过不推进,进度条会卡在 33% 永远不动。
        assertEquals(3, last.processed)
        assertEquals(1, last.done)
        assertEquals(1, last.failed)
        assertEquals(1, last.skipped)
    }

    @Test
    fun `cancelling between items stops the platform port and fills the rest as not started`() {
        val actions = FakeRenameActions()
        val token = CancellationToken()
        val ops = listOf(
            op("a", "001.jpg", "新_001.jpg"),
            op("b", "002.jpg", "新_002.jpg"),
            op("c", "003.jpg", "新_003.jpg"),
        )

        val report = executeRenamePlanReal(
            ops,
            actions,
            token = token,
            onProgress = { _ ->
                // 第一项处理完就请求中断 —— 模拟用户点了取消。
                token.cancel()
            },
        )

        assertEquals(1, actions.calls.size, "中断后不该再碰平台")
        assertEquals(3, report.entries.size, "未处理的项也要出现在报告里")
        assertTrue(report.entries[0].outcome == ExecOutcome.Succeeded)
        val rest = report.entries.drop(1)
        assertTrue(rest.all { it.outcome == ExecOutcome.Skipped }, "未处理项应为跳过")
        assertTrue(
            rest.all { it.reason?.key == TextKey.PackFailNotStarted },
            "未处理项的原因必须是「尚未开始」,与计划里的跳过区分开",
        )
    }

    @Test
    fun `no item touches the platform when the token is already cancelled`() {
        val actions = FakeRenameActions()
        val token = CancellationToken()
        token.cancel()

        val report = executeRenamePlanReal(
            listOf(op("a", "001.jpg", "新_001.jpg"), op("b", "002.jpg", "新_002.jpg")),
            actions,
            token = token,
        )

        assertTrue(actions.calls.isEmpty())
        assertTrue(report.entries.all { it.outcome == ExecOutcome.Skipped })
    }
}
