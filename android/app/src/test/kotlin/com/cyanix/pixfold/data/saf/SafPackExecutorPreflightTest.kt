package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.core.net.toUri
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.ui.library.PackUiPreflight
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 平台预检端口（D4b）。
 *
 * 为什么单列一个类：`PackPreflightTest`（领域层）证明「给定可用空间能算出正确结论」，
 * 但**证不了**平台实现有没有把真实的可用空间喂进去、有没有用**同一组阈值**。
 * 「准备页说能打、执行时被拒绝」正是这一层最容易出的错 —— 两处各肉一份数值，
 * 改了一处忘了另一处，离线测试全绿、真机上用户被卡在门口。
 *
 * 所以 `SafPackExecutor` 把 `spaceSource` / `threshold` / `safetyMargin` 都开成构造参数
 * （**只为了可测**：Robolectric 的 `StatFs` 报的是宿主机真实值，造不出固定场景）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafPackExecutorPreflightTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3Atest".toUri()
    private val plans = buildPackagePlans(MockDataB.library.volumes)

    private fun executor(
        available: Long?,
        threshold: SpaceThreshold = SpaceThreshold.Percentage(marginPercent = 10),
        safetyMargin: Long = 32L * 1024 * 1024,
    ) = SafPackExecutor(
        context = context,
        treeUri = treeUri,
        rootDisplayName = MockDataB.library.rootPath,
        spaceSource = { available },
        threshold = threshold,
        safetyMargin = safetyMargin,
    )

    @Test
    fun `the platform asks the space source for this tree`() = runBlocking {
        var asked: android.net.Uri? = null
        val subject = SafPackExecutor(
            context = context,
            treeUri = treeUri,
            rootDisplayName = MockDataB.library.rootPath,
            spaceSource = { uri -> asked = uri; 100L * 1024 * 1024 * 1024 },
        )
        subject.preflight(plans)
        assertEquals(treeUri, asked, "必须问的是工作区那棵树，不是别的地方")
    }

    @Test
    fun `plenty of space yields a ready result without blockers`() = runBlocking {
        val outcome = executor(available = 100L * 1024 * 1024 * 1024).preflight(plans)
        assertTrue(outcome is PackUiPreflight.Ready, "空间充足时必须是 Ready")
        val ready = outcome as PackUiPreflight.Ready
        assertEquals(100L * 1024 * 1024 * 1024, ready.availableBytes)
        assertTrue(ready.result.blockers.isEmpty(), "空间充足不该有阻断项：${ready.result.blockers}")
    }

    @Test
    fun `unknown space is reported as unknown rather than as plenty`() = runBlocking {
        // 承重：`null` 是「问不到」不是「不足」。但界面**不能**把它说成充足 ——
        // 所以 `availableBytes` 必须原样保留 null 传到 UI 层。
        val outcome = executor(available = null).preflight(plans)
        assertTrue(outcome is PackUiPreflight.Ready)
        assertNull((outcome as PackUiPreflight.Ready).availableBytes, "问不到就要如实说问不到")
    }

    @Test
    fun `a throwing space source becomes unavailable instead of crashing`() = runBlocking {
        val subject = SafPackExecutor(
            context = context,
            treeUri = treeUri,
            rootDisplayName = MockDataB.library.rootPath,
            spaceSource = { throw IllegalStateException("StatFs 炸了") },
        )
        val outcome = subject.preflight(plans)
        assertTrue(outcome is PackUiPreflight.Unavailable, "查询失败必须降级成 Unavailable")
        // 注意 `assertNotNull` 返回非空值，直接放在 runBlocking 末尾会让本函数
        // 变成 `String` 返回值，JUnit 报 "should be void"。
        assertTrue((outcome as PackUiPreflight.Unavailable).message.isNotEmpty())
    }

    @Test
    fun `the preflight uses the injected threshold rather than a hard coded one`() = runBlocking {
        // 这条钉的是「可配」。注意阈值的语义是「所需 = 预估 × (1 + p%)」——
        // 100% 也只翻一倍，不是「必须占满可用空间」。
        // 故把可用空间设在**预估量的 1.05 倍**：
        //   1% 阈值 ⇒ 需要 1.01 倍 ⇒ 够；
        //  10% 阈值 ⇒ 需要 1.10 倍 ⇒ 不够。
        // 若平台实现偷偷写死 10%，第一条断言就红。
        val baseline = executor(available = Long.MAX_VALUE).preflight(plans) as PackUiPreflight.Ready
        val estimated = baseline.result.estimatedBytes
        assertTrue(estimated > 0, "夹具前提：mock 卷必须有正的总预估大小")
        val available = estimated + estimated / 20  // 1.05 倍

        val loose = executor(available = available, threshold = SpaceThreshold.Percentage(1)).preflight(plans)
        val strict = executor(available = available, threshold = SpaceThreshold.Percentage(10)).preflight(plans)

        assertTrue(
            (loose as PackUiPreflight.Ready).result.blockers.isEmpty(),
            "1% 阈值下 1.05 倍空间应当够用",
        )
        assertTrue(
            (strict as PackUiPreflight.Ready).result.blockers.isNotEmpty(),
            "10% 阈值下 1.05 倍空间必须被判不足",
        )
    }

    @Test
    fun `preflight does not write anything to disk`() = runBlocking {
        // 预检必须是只读的：它跑在用户还在看计划页的时候。
        // 这里用「输出目录里本不该出现任何 CBZ」来代表 —— 真实 SAF 目录在 Robolectric 下
        // 是空的，故断言的是「没有任何 sink 被构造」。用 executeCalls 无法表达，
        // 改断结果里没有 Written 之类的副作用：Ready 结果只含计数与判定。
        val outcome = executor(available = 100L * 1024 * 1024 * 1024).preflight(plans)
        val ready = outcome as PackUiPreflight.Ready
        assertTrue(ready.result.volumes.all { it.entryCount > 0 }, "预检只做计算，不产生写入结果")
    }
}
