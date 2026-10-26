package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 工作流 C 的两个入口（库页卷行 / 元数据页底栏）。
 *
 * ## 为什么两处各写一遍
 *
 * 两个入口是同一个动作的两道门,装配点却是两条完全独立的代码路径
 * （LibraryPage 的 VolumeRow 分支 vs MetadataPage 底栏的 extraContent）.
 * 历史上有过「领域做完 + 单测全绿,但界面根本没接上」的死代码事故（见 MetadataPageTest 文件头）,
 * 所以这里对两处各设断言,而不是测完一处就推定另一处也对.
 *
 * ## 为什么「行本身仍选中」也要断言
 *
 * 卷行整体是 clickable(onSelect),新加的图标按钮是它的子节点.
 * 子按钮不自带点击拦截时,点它会被整行的 clickable 吞掉 ——
 * 表现是「点更新按钮,卷被选中,什么也没发生」.这条断言就是那个失效模式的探针.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class CbzUpdateEntryTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /** mock 里只有 op-102 带归档（outputExists = true）. */
    private val withArchive: ComicVolume = library.volumes.first { it.outputExists }
    private val withoutArchive: ComicVolume = library.volumes.first { !it.outputExists }

    private fun showLibrary(
        volumes: List<ComicVolume> = library.volumes,
        onUpdate: (ComicVolume) -> Unit = {},
        onStateChange: (LibraryState) -> Unit = {},
    ) {
        rule.setContent {
            LibraryPage(
                state = libraryStateOf(library.rootPath, volumes),
                onStateChange = onStateChange,
                contentInsets = WindowInsets(0, 0, 0, 0),
                onUpdateMeta = onUpdate,
            )
        }
    }

    private fun scrollToVolume(id: String) {
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag(id)))
        rule.waitForIdle()
    }

    // ================= 入口一：库页卷行 =================

    @Test
    fun `no update button when the volume has no archive on disk`() {
        showLibrary(volumes = listOf(withoutArchive))
        rule.onNodeWithTag(volumeUpdateTag(withoutArchive.id)).assertDoesNotExist()
    }

    @Test
    fun `the update button appears and reports the right volume`() {
        var picked: String? = null
        showLibrary(onUpdate = { picked = it.id })
        scrollToVolume(withArchive.id)
        rule.onNodeWithTag(volumeUpdateTag(withArchive.id)).assertIsDisplayed()
        rule.onNodeWithTag(volumeUpdateTag(withArchive.id)).performClick()
        assertEquals(withArchive.id, picked)
    }

    @Test
    fun `clicking the row itself still selects instead of updating`() {
        var selected: String? = null
        var updated = false
        showLibrary(
            onUpdate = { updated = true },
            onStateChange = { selected = it.activeVolumeId },
        )
        // 先选另一个卷,避免「本来就已经是活动卷」让断言恒真.
        rule.onNodeWithTag(volumeCardTag(withoutArchive.id)).performClick()
        assertEquals(withoutArchive.id, selected)
        scrollToVolume(withArchive.id)
        rule.onNodeWithTag(volumeCardTag(withArchive.id)).performClick()
        assertEquals(withArchive.id, selected)
        assertFalse(updated, "点整行不该触发更新")
    }

    // ================= 入口二：元数据页底栏 =================

    private fun showMetadata(
        volumes: List<ComicVolume>,
        onUpdate: (ComicVolume) -> Unit = {},
    ) {
        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, volumes)) }
            MetadataPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
                onNext = {},
                onUpdateMeta = onUpdate,
            )
        }
    }

    @Test
    fun `metadata footer shows update when the active volume has an archive`() {
        var picked: String? = null
        showMetadata(volumes = listOf(withArchive), onUpdate = { picked = it.id })
        rule.onNodeWithTag(TAG_METADATA_UPDATE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_METADATA_UPDATE).performClick()
        assertEquals(withArchive.id, picked)
    }

    @Test
    fun `metadata footer hides update when the active volume has no archive`() {
        showMetadata(volumes = listOf(withoutArchive))
        rule.onNodeWithTag(TAG_METADATA_UPDATE).assertDoesNotExist()
    }

    @Test
    fun `empty library has no update entry at all`() {
        showMetadata(volumes = emptyList())
        rule.onNodeWithTag(TAG_METADATA_UPDATE).assertDoesNotExist()
    }
}
