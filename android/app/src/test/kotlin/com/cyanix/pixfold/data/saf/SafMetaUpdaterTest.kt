package com.cyanix.pixfold.data.saf

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.pack.CbzMetaUpdatePlan
import com.cyanix.pixfold.domain.pack.UpdatePreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `SafMetaUpdater` 的**可离线部分**：空间预检三态。
 *
 * ## 为什么只测预检
 *
 * 三段式替换（原包 → 备份 → 临时 → 原名 → 删备份）的每一步都依赖真实的
 * `DocumentsProvider` —— Robolectric 没有真 provider，`renameDocument` 之类的调用
 * 只会拿到 shadow 的回放值。在这种环境里断言「替换成功」等于自欺。
 *
 * 该路径登记在 spec §9.5 的「只能真机断」清单里（Task 8c 回写进 🔌 待办）。
 *
 * 预检则完全可测：它是纯算术 + 两个可注入的取数函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafMetaUpdaterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val treeUri: Uri = "content://com.android.externalstorage.documents/tree/primary%3Atest".toUri()

    private fun volume() = ComicVolume(
        id = "v",
        dirPath = "a",
        pages = emptyList(),
        titleSug = Suggestion("标题", "目录"),
        seriesSug = Suggestion("系列", "目录"),
        writerSug = Suggestion("作者", "目录"),
        volumeSug = Suggestion(1, "目录"),
        langSug = Suggestion<String>(null, "目录"),
        outputDirSug = "",
        outputExists = true,
        title = "标题",
        series = "系列",
        writer = "作者",
        volume = 1,
        language = LangChoice.Unset,
        cbzFileName = "a.cbz",
        outputDir = "",
    )

    /** 预检要走「先找到目标包」这一步，而真实查找依赖 SAF provider ⇒ 注入固定 uri。 */
    private val targetUri: Uri = "content://com.android.externalstorage.documents/document/primary%3Atest%2Fa.cbz".toUri()

    private fun plan() = CbzMetaUpdatePlan(
        volumeId = "v",
        comicInfoXml = "<ComicInfo><Title>T</Title></ComicInfo>",
        targetDisplayName = "a.cbz",
        entryCount = 0,
    )

    @Test
    fun `everything unknown when the archive size cannot be read`() {
        val updater = SafMetaUpdater(
            context = context,
            treeUri = treeUri,
            spaceBytesOf = { 10L * 1024 * 1024 * 1024 },
            dirOfVolume = { "" },
            documentSizeOf = { _, _ -> null },
            renameDocument = { _, _, _ -> null },
            lookupTarget = { targetUri },
        )
        assertEquals(UpdatePreflight.Unknown, updater.preflight(plan(), volume()))
    }

    @Test
    fun `everything unknown when the free space cannot be read`() {
        val updater = SafMetaUpdater(
            context = context,
            treeUri = treeUri,
            spaceBytesOf = { null },
            dirOfVolume = { "" },
            documentSizeOf = { _, _ -> 1024L },
            renameDocument = { _, _, _ -> null },
            lookupTarget = { targetUri },
        )
        assertEquals(UpdatePreflight.Unknown, updater.preflight(plan(), volume()))
    }

    @Test
    fun `ready carries the input size and input plus margin as the write estimate`() {
        val input = 100L * 1024 * 1024
        val updater = SafMetaUpdater(
            context = context,
            treeUri = treeUri,
            spaceBytesOf = { 10L * 1024 * 1024 * 1024 },
            dirOfVolume = { "" },
            documentSizeOf = { _, _ -> input },
            renameDocument = { _, _, _ -> null },
            lookupTarget = { targetUri },
        )
        val ready = updater.preflight(plan(), volume()) as UpdatePreflight.Ready
        assertEquals(input, ready.estimatedReadBytes)
        assertEquals(input + 32L * 1024 * 1024, ready.estimatedWriteBytes)
    }

    @Test
    fun `no space when the free space is below input plus the margin`() {
        val input = 100L * 1024 * 1024
        val available = input + 1024  // 只多 1 KiB，远小于 32 MiB 余量
        val updater = SafMetaUpdater(
            context = context,
            treeUri = treeUri,
            spaceBytesOf = { available },
            dirOfVolume = { "" },
            documentSizeOf = { _, _ -> input },
            renameDocument = { _, _, _ -> null },
            lookupTarget = { targetUri },
        )
        val no = updater.preflight(plan(), volume()) as UpdatePreflight.NoSpace
        assertEquals(input + 32L * 1024 * 1024, no.requiredBytes)
        assertEquals(available, no.availableBytes)
    }

    @Test
    fun `the margin is a real boundary, not an order of magnitude`() {
        val input = 100L * 1024 * 1024
        val margin = 32L * 1024 * 1024
        // 恰好等于 required ⇒ 够用（判定是 available < required 才阻断）
        val exact = SafMetaUpdater(
            context = context,
            treeUri = treeUri,
            spaceBytesOf = { input + margin },
            dirOfVolume = { "" },
            documentSizeOf = { _, _ -> input },
            renameDocument = { _, _, _ -> null },
            lookupTarget = { targetUri },
        )
        assertTrue(exact.preflight(plan(), volume()) is UpdatePreflight.Ready)
        // 少 1 字节 ⇒ 不够
        val short = SafMetaUpdater(
            context = context,
            treeUri = treeUri,
            spaceBytesOf = { input + margin - 1 },
            dirOfVolume = { "" },
            documentSizeOf = { _, _ -> input },
            renameDocument = { _, _, _ -> null },
            lookupTarget = { targetUri },
        )
        assertTrue(short.preflight(plan(), volume()) is UpdatePreflight.NoSpace)
    }
}
