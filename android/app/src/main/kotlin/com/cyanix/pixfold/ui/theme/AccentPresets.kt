package com.cyanix.pixfold.ui.theme

import androidx.compose.ui.graphics.Color
import com.cyanix.pixfold.domain.settings.AccentChoice

/**
 * 强调色预设 —— 每项提供一颗**种子色**,由 [ColorSchemes] 派生出整套配色。
 *
 * **为什么只定义种子色,而不是写死整套配色**:一颗种子经色彩算法即可生成符合
 * M3 规范的全部语义角色(且自动满足对比度要求),手写整套配色既冗长又难保证一致。
 * 这也让"色彩风格""色彩标准"两个开关能对**同一颗种子**生效。
 *
 * [AccentChoice.System] 不在本表中 —— 它的种子来自系统壁纸(动态取色),
 * 由 [PixFoldTheme] 在运行时取得。
 */
object AccentPresets {

    /** 内置默认强调色(靛蓝):与升级前 `LightColors/DarkColors` 的 primary 一脉相承。 */
    val DefaultSeed: Color = Color(0xFF3F51B5)

    /**
     * 预设种子色表。
     *
     * 取值参考 Material 基线的常见色相;`Default` 用内置靛蓝。
     */
    private val seeds: Map<AccentChoice, Color> = mapOf(
        AccentChoice.Default to DefaultSeed,
        AccentChoice.Blue to Color(0xFF0061A4),
        AccentChoice.Green to Color(0xFF00696D),
        AccentChoice.Purple to Color(0xFF6750A4),
        AccentChoice.Orange to Color(0xFF8B5000),
        AccentChoice.Pink to Color(0xFF7D5260),
    )

    /**
     * 取预设种子色。
     *
     * [AccentChoice.System] 传入时回退 [DefaultSeed] —— 调用方在动态取色不可用
     * (API < 31)时也走这里,避免"选了跟随系统却没有任何颜色"。
     */
    fun seedOf(choice: AccentChoice): Color = seeds[choice] ?: DefaultSeed

    /** 所有可直接选择为种子的预设(不含 [AccentChoice.System])。 */
    val selectable: List<AccentChoice> = seeds.keys.toList()
}
