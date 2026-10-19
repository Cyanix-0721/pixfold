package com.cyanix.pixfold.domain.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PackTaskSnapshotCodec] 的往返与容错。
 *
 * 两条承重：
 * 1. **任何用户内容都能原样往返** —— 目录名里出现换行、冒号、`=`、引号、日文都不该坏掉。
 * 2. **读不懂的数据一律回 null，绝不抛** —— 这段在冷启动路径上，崩溃的代价是应用起不来。
 */
class PackTaskSnapshotCodecTest {

    private fun snapshot(
        taskId: String = "task-1",
        phase: PackTaskPhase = PackTaskPhase.Running,
        progress: TaskProgress = TaskProgress(total = 3, done = 1, failed = 0, skipped = 0),
        currentVolume: String? = null,
        outputTargets: List<String> = emptyList(),
        failureDetail: String? = null,
    ) = PackTaskSnapshot(
        taskId = taskId,
        phase = phase,
        startedAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
        progress = progress,
        currentVolume = currentVolume,
        outputTargets = outputTargets,
        failureDetail = failureDetail,
    )

    @Test
    fun `往返后每个字段都相同`() {
        val original = snapshot(
            currentVolume = "系列A/第01卷",
            outputTargets = listOf("系列A/第01卷.cbz", "系列B/第02卷.cbz"),
            failureDetail = "打开输出流失败",
        )

        val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `可空字段缺省时往返后仍为 null 与空列表`() {
        val original = snapshot(currentVolume = null, outputTargets = emptyList(), failureDetail = null)

        val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))

        assertEquals(original, restored)
        assertNull(restored?.currentVolume)
        assertEquals(emptyList(), restored?.outputTargets)
        assertNull(restored?.failureDetail)
    }

    @Test
    fun `目录名里有换行与冒号也能往返`() {
        // SAF 下 displayName 可以带任何字符 —— 目录名里的换行、冒号、等号都是合法的。
        // 若编码不做保护，这些字符会把行格式撕成两半。
        val nasty = "A\nB:C=D \"引号\" 日本語"
        val original = snapshot(currentVolume = nasty, outputTargets = listOf(nasty))

        val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))

        assertEquals(nasty, restored?.currentVolume)
        assertEquals(listOf(nasty), restored?.outputTargets)
    }

    @Test
    fun `进度四个计数逐一往返`() {
        val original = snapshot(progress = TaskProgress(total = 9, done = 4, failed = 2, skipped = 3))

        val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))

        assertEquals(9, restored?.progress?.total)
        assertEquals(4, restored?.progress?.done)
        assertEquals(2, restored?.progress?.failed)
        assertEquals(3, restored?.progress?.skipped)
    }

    @Test
    fun `空输出目标列表往返后仍为空`() {
        val original = snapshot(outputTargets = emptyList())

        val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))

        assertEquals(0, restored?.outputTargets?.size)
    }

    @Test
    fun `多卷路径的先后顺序被保留`() {
        // 顺序不能靠 HashSet 之类打乱:恢复提示要按用户看到的顺序列出待核查文件。
        val targets = listOf("c.cbz", "a.cbz", "b.cbz")
        val original = snapshot(outputTargets = targets)

        val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))

        assertEquals(targets, restored?.outputTargets)
    }

    @Test
    fun `五种阶段都能往返`() {
        for (phase in PackTaskPhase.entries) {
            val original = snapshot(phase = phase)
            val restored = PackTaskSnapshotCodec.decode(PackTaskSnapshotCodec.encode(original))
            assertEquals(phase, restored?.phase, "阶段 $phase 未正确往返")
        }
    }

    @Test
    fun `null 与空串都解成 null`() {
        assertNull(PackTaskSnapshotCodec.decode(null))
        assertNull(PackTaskSnapshotCodec.decode(""))
    }

    @Test
    fun `版本号不符时解成 null`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        val bumped = encoded.replaceFirst("v:1", "v:99")

        assertNull(PackTaskSnapshotCodec.decode(bumped))
    }

    @Test
    fun `缺关键字段时解成 null`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        // 去掉 taskId 那一行
        val missing = encoded.split('\n').filterNot { it.startsWith("id:") }.joinToString("\n")

        assertNull(PackTaskSnapshotCodec.decode(missing))
    }

    @Test
    fun `数字字段不是数字时解成 null`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        val broken = encoded.replaceFirst("st:1000", "st:abc")

        assertNull(PackTaskSnapshotCodec.decode(broken))
    }

    @Test
    fun `进度行数字个数不对时解成 null`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        val broken = encoded.replaceFirst("t:3 1 0 0", "t:3 1 0")

        assertNull(PackTaskSnapshotCodec.decode(broken))
    }

    @Test
    fun `进度行里有非数字时解成 null 而不是当成 0`() {
        // 为什么这条必须单独存在：把非数字当 0 是**看起来能跑**的错误实现
        // （`it.toIntOrNull() ?: 0`），个数判断依然通过，于是解出一份
        // `total=0` 的快照；而 `TaskProgress.fraction` 在 total==0 时返回 1f、
        // `isComplete` 也自然为真 ⇒ 恢复逻辑会把「磁盘上可能有半成品」误判成
        // 「已了结」，不再提示用户。这条断言钉的正是那个误判。
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        val broken = encoded.replaceFirst("t:3 1 0 0", "t:3 x 0 0")

        assertNull(PackTaskSnapshotCodec.decode(broken))
    }

    @Test
    fun `base64 坏掉时解成 null 而不是抛异常`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot(taskId = "任务一"))
        val broken = encoded.replaceFirst("id:任务一".let { "id:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("任务一".toByteArray()) }, "id:!!!!")

        assertNull(PackTaskSnapshotCodec.decode(broken))
    }

    @Test
    fun `出现未知键时解成 null`() {
        // 未知键意味着写入方版本比读取方新 —— 静默忽略会让「新字段丢失」变成看不见的缺陷。
        val encoded = PackTaskSnapshotCodec.encode(snapshot()) + "\nzz:whatever"

        assertNull(PackTaskSnapshotCodec.decode(encoded))
    }

    @Test
    fun `没有分隔符的怪行解成 null`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot()) + "\n没有冒号的一行"

        assertNull(PackTaskSnapshotCodec.decode(encoded))
    }

    @Test
    fun `阶段名不认识时解成 null`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        val broken = encoded.replaceFirst("ph:Running", "ph:Exploded")

        assertNull(PackTaskSnapshotCodec.decode(broken))
    }

    @Test
    fun `编码结果里不含用户原文`() {
        // base64 不只是转义手段:把目录名以明文塞进 SharedPreferences，
        // 将来若换存储介质(备份/导出)就会顺带泄露用户的书名。
        val encoded = PackTaskSnapshotCodec.encode(
            snapshot(currentVolume = "秘密的书名", outputTargets = listOf("秘密/第01卷.cbz")),
        )

        assertTrue("秘密" !in encoded, "编码结果里出现了用户原文: $encoded")
    }

    @Test
    fun `空行被忽略`() {
        val encoded = PackTaskSnapshotCodec.encode(snapshot())
        val padded = "\n\n" + encoded + "\n\n"

        // 末尾换行不该让数据读不出来(写入方可能在拼接时留下空行)。
        assertEquals(
            PackTaskSnapshotCodec.decode(encoded)?.taskId,
            PackTaskSnapshotCodec.decode(padded)?.taskId,
        )
    }
}
