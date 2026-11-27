package com.cyanix.pixfold.ui.settings

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DisplayRefreshRateTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fhdModes = listOf(
        DisplayModeSpec(modeId = 1, physicalWidth = 1264, physicalHeight = 2780, refreshRate = 60.0f),
        DisplayModeSpec(modeId = 2, physicalWidth = 1264, physicalHeight = 2780, refreshRate = 90.0f),
        DisplayModeSpec(modeId = 3, physicalWidth = 1264, physicalHeight = 2780, refreshRate = 120.0f),
        DisplayModeSpec(modeId = 4, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 144.0f),
    )

    @Test
    fun `extracts deduplicated sorted refresh rates within active physical resolution`() {
        val active = fhdModes[1]
        val extraDup = fhdModes + listOf(
            DisplayModeSpec(
                modeId = 5,
                physicalWidth = 1264,
                physicalHeight = 2780,
                refreshRate = 120.0f,
                alternativeRefreshRates = listOf(60.0f, 80.0f),
            ),
        )
        assertEquals(listOf(60, 80, 90, 120), extractSupportedRefreshRates(extraDup, active))
    }

    @Test
    fun `resolves follow system and explicit target Hz across primary ARR and resolution guards`() {
        val active = fhdModes[1]

        // null -> 清除窗口锁定，恢复省电平衡默认调度
        val systemReq = resolveRefreshRateRequest(null, fhdModes, active)
        assertEquals(0, systemReq.preferredDisplayModeId)
        assertEquals(0f, systemReq.preferredRefreshRate)
        assertTrue(systemReq.powerSavingsBalanced)

        // 同分辨率主模式精确命中 120Hz -> 应选 modeId=3 且关闭省电限制
        val req120 = resolveRefreshRateRequest(120, fhdModes, active)
        assertEquals(3, req120.preferredDisplayModeId)
        assertEquals(120f, req120.preferredRefreshRate)
        assertFalse(req120.powerSavingsBalanced)

        // 请求的 144Hz 只存在于另一物理分辨率（1080x2400）-> 严禁跨分辨率切换，落到同分辨率最近的 120Hz
        val reqCrossRes = resolveRefreshRateRequest(144, fhdModes, active)
        assertEquals(3, reqCrossRes.preferredDisplayModeId)
        assertEquals(120f, reqCrossRes.preferredRefreshRate)
        assertFalse(reqCrossRes.powerSavingsBalanced)

        // ARR 单模面板仅通过 alternativeRefreshRates 暴露 90/60
        val arrMode = DisplayModeSpec(
            modeId = 10,
            physicalWidth = 1264,
            physicalHeight = 2780,
            refreshRate = 120.0f,
            alternativeRefreshRates = listOf(60.0f, 90.0f),
        )
        val reqArr = resolveRefreshRateRequest(90, listOf(arrMode), arrMode)
        assertEquals(10, reqArr.preferredDisplayModeId)
        assertEquals(90f, reqArr.preferredRefreshRate)
        assertFalse(reqArr.powerSavingsBalanced)
    }

    @Test
    fun `ApplyPreferredRefreshRate updates window attributes on device activity window`() {
        val display = checkNotNull(
            rule.activity.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY),
        )
        val expectedMode = display.supportedModes.firstOrNull()

        var targetHz by mutableStateOf<Int?>(null)
        rule.setContent {
            ApplyPreferredRefreshRate(preferredRefreshRateHz = targetHz)
        }
        rule.waitForIdle()

        // 默认跟随系统：保持默认 0 / balanced=true
        val initialLp = rule.activity.window.attributes
        assertEquals(0, initialLp.preferredDisplayModeId)
        assertEquals(0f, initialLp.preferredRefreshRate)
        assertTrue(initialLp.isFrameRatePowerSavingsBalanced)

        // 切为 120Hz：有可用模式时匹配目标模式，单模/无模式时回落并取消省电降帧限制
        targetHz = 120
        rule.waitForIdle()
        val activeLp = rule.activity.window.attributes
        assertEquals(expectedMode?.modeId ?: 0, activeLp.preferredDisplayModeId)
        assertEquals(expectedMode?.refreshRate ?: 120f, activeLp.preferredRefreshRate)
        assertFalse(activeLp.isFrameRatePowerSavingsBalanced)

        // 切回跟随系统 -> 归零并恢复省电平衡
        targetHz = null
        rule.waitForIdle()
        val resetLp = rule.activity.window.attributes
        assertEquals(0, resetLp.preferredDisplayModeId)
        assertEquals(0f, resetLp.preferredRefreshRate)
        assertTrue(resetLp.isFrameRatePowerSavingsBalanced)
    }
}
