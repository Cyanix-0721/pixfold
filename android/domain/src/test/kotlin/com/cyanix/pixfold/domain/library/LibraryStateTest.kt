package com.cyanix.pixfold.domain.library

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作流 B 批次状态的领域语义(规格 §4.5 / §10.1)。
 *
 * 这一批入口此前**只被 UI 调用、领域层零覆盖**(`activeVolume` / `includedCount` /
 * `volumeCount` / `pendingCount` / `seriesGroups` / `selectVolume` / `setVolumeIncluded` /
 * `updateVolume` / `batchApplyToLibrary` 在 25 个领域测试文件里的引用计数全是 0)。
 * 变异扫荡里这 12 个变异体因此**全部存活** —— 本文件补上后,
 * 「当前卷回退」「勾选计数」「待确认计数」「按系列分组」「批量设置写回」才有可判定的读数。
 *
 * 特别钉住两处**容易看着像重复、其实语义不同**的量:
 *  - `includedCount`(参与打包的卷)与 `volumeCount`(库里的卷)不是一回事;
 *  - `pendingCount`(待确认的卷)与 `includedCount` 也不是一回事。
 */
class LibraryStateTest {

    private val volumes = MockDataB.library.volumes
    private val state = libraryStateOf("E:\\漫画库", volumes)

    private fun withId(id: String) = state.copy(activeVolumeId = id)

    private fun vol(after: LibraryState, id: String) = after.volumes.first { it.id == id }

    @Test
    fun `initial state selects the first volume as the active one`() {
        assertEquals("aot-1", state.activeVolumeId, "初始选中第一卷")
        assertEquals("aot-1", state.activeVolume.id)
        assertEquals(5, state.volumeCount)
    }

    @Test
    fun `a stale active id falls back to the first volume not the last`() {
        // selectVolume 会挡掉库外 id,但 domain 状态的字段是公开的,
        // KDoc 明确承诺"id 失效时退回第一卷(不返回 null)" —— 这里把那句话钉死。
        assertEquals("aot-1", withId("不存在的卷").activeVolume.id, "失效 id 退回第一卷,不是最后一卷")
    }

    @Test
    fun `active volume is looked up by id`() {
        assertEquals("op-102", withId("op-102").activeVolume.id)
        assertEquals("aot-0", withId("aot-0").activeVolume.id)
    }

    @Test
    fun `select volume switches inside the library and ignores unknown ids`() {
        assertEquals("aot-2", selectVolume(state, "aot-2").activeVolumeId)
        assertEquals("aot-1", selectVolume(state, "不存在的卷").activeVolumeId, "库外 id 原样返回")
        assertEquals(5, selectVolume(state, "aot-2").volumes.size, "换选中项不得增删卷")
    }

    @Test
    fun `included count and total count are different things`() {
        assertEquals(5, state.includedCount, "mock 五卷默认全部参与打包")
        assertEquals(5, state.volumeCount)

        val partial = setVolumeIncluded(state, "aot-0", false)
        assertEquals(4, partial.includedCount, "取消勾选后参与打包少一卷")
        assertEquals(5, partial.volumeCount, "但库里的卷数不变 —— 总卷数不是已勾选数")
        assertFalse(vol(partial, "aot-0").included, "写入的就是传入的勾选值")
        assertTrue(vol(partial, "aot-1").included, "其它卷不受影响")
    }

    @Test
    fun `set volume included on an unknown id changes nothing`() {
        val after = setVolumeIncluded(state, "不存在的卷", false)
        assertEquals(state, after, "未知 id 必须原样返回")
        assertTrue(vol(after, "aot-1").included, "不得顺手把第一卷取消掉")
    }

    @Test
    fun `pending count counts unconfirmed volumes not included ones`() {
        assertEquals(5, state.pendingCount, "mock 五卷的语言全都未确认")
        assertEquals(5, state.includedCount, "此时两个计数恰好相等,所以要再走一步才能分辨")

        val confirmed = updateVolume(state, vol(state, "op-102").copy(language = LangChoice.Zh))
        assertEquals(4, confirmed.pendingCount, "确认一卷语言后待确认少一卷")
        assertEquals(5, confirmed.includedCount, "勾选数不变 —— 待确认数不是已勾选数")
    }

    @Test
    fun `update volume replaces in place instead of appending`() {
        val after = updateVolume(state, vol(state, "aot-0").copy(title = "改过的标题"))
        assertEquals(5, after.volumes.size, "写回是替换,不是追加")
        assertEquals("aot-0", after.volumes[2].id, "位置不变")
        assertEquals("改过的标题", after.volumes[2].title)
    }

    @Test
    fun `series groups keep first appearance order`() {
        assertEquals(listOf("进击的巨人", "海贼王"), state.seriesGroups.map { it.first })
        assertEquals(listOf(3, 2), state.seriesGroups.map { it.second.size })
    }

    @Test
    fun `batch apply writes the new library back and reports real counts`() {
        val (next, result) = batchApplyToLibrary(state, MetaField.Series, MetaValue.Text("统一系列"))

        assertEquals(5, result.appliedCount)
        assertEquals(0, result.skippedCount)
        assertEquals("统一系列", vol(next, "aot-1").series, "必须把新库写回状态,否则 UI 看到的是没改过的库")
        assertEquals("进击的巨人", vol(state, "aot-1").series, "原状态不得被就地改动")
    }
}
