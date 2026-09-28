package com.pixfold.d1.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 列数选项的**上下限与拼装**——排序页下拉菜单与设置页两个选择项共用(用户 2026-09-26
 * 裁决 + 第二轮补充「按方向自适应上限:竖屏 1–5、横屏 2–9」)。
 *
 * **为什么上限按方向分**:竖屏可用宽只有屏宽,能塞下的列数有限;横屏可用宽是屏宽的两倍多
 * (PJX110 竖屏 361dp / 横屏 794dp),给到 9 列才有意义。共用一个上限必然有一边不合适
 * ——上限按竖屏定,横屏永远选不到合适的列数;按横屏定,竖屏列出一堆选不出来的值。
 *
 * 注意上限只是**给用户挑的上限**,不是"最大能画多少列":设备算出更大的自适应值时,
 * 该值会被临时并入列表(见 [columnOptions]),保证当前生效值永远可见可选
 * (用户裁决:「把它临时并入下拉列表,保证当前值永远可见可选」)。
 */

/** 竖屏下可选列数上限。 */
const val PORTRAIT_MAX_COLUMNS = 5

/** 横屏下可选列数上限。 */
const val LANDSCAPE_MAX_COLUMNS = 9

/**
 * 列数选项的**下界**。
 *
 * 用户裁决两处都写的是「竖屏 1–5、横屏 2–9」,但**括号里的显示范围是 2–5 / 2–9**
 * (「竖屏显示 2–5,横屏显示 2–9」)。两处并存时按括号里的显示范围执行 ——
 * 它是更具体的描述,且 1 列在手机上几乎不可用(缩略图会大到只剩一张半)。
 * 1 列仍可通过"自适应算出 1"临时并入(见 [columnOptions]),只是不主动列出。
 */
const val MIN_COLUMNS = 2

/**
 * 拼出下拉列表要显示的列数列表:`MIN_COLUMNS..maxColumns`,并把 [current] **并入**。
 *
 * 并入是刻意的:自适应值可能大于 [maxColumns](例如平板竖屏可用宽 784dp ⇒ 9 列,
 * 而竖屏上限是 5)。此时若只列到 5,当前生效的 9 列就**既看不到也选不中** ——
 * 界面会在说谎("你选了 5 列"而网格其实是 9 列)。并入后当前值永远在列表里。
 *
 * @param maxColumns 该方向的上限([PORTRAIT_MAX_COLUMNS] 或 [LANDSCAPE_MAX_COLUMNS])。
 * @param current 当前生效的列数(自适应算出的值,或用户已选的固定值)。
 */
fun columnOptions(maxColumns: Int, current: Int): List<Int> {
    val base = (MIN_COLUMNS..maxOf(maxColumns, MIN_COLUMNS)).toList()
    // 自适应值可能**落在常规区间之外**的两个方向:大于上限(平板/横屏),或小于下界
    // (极端窄的容器算出 1 列)。两种都并进来。
    return if (current in base) base else (base + current).sorted()
}

/**
 * 当前屏幕方向是否为竖屏(用户裁决:「排序页根据屏幕方向实时切换使用哪一个」)。
 *
 * 读 [LocalConfiguration],故旋转会**重组**本函数 —— 这正是"实时切换"的实现方式:
 * 旋转 → Activity 重建 → `LocalConfiguration` 更新 → 列数默认值随之换成另一个方向的。
 * (仓内 `AndroidManifest.xml` 未声明 `configChanges`,系统走的就是重建路径。)
 *
 * 只有明确的 [Configuration.ORIENTATION_LANDSCAPE] 才算横屏:`ORIENTATION_UNDEFINED`
 * 在方屏/未定场景出现,那时按竖屏处理更保守 —— 上限小、列表短,不会摆出一串用不上的值。
 */
@Composable
fun isPortraitOrientation(): Boolean =
    LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE

/**
 * 该方向的**屏幕宽度**(dp),用于估算自适应列数。
 *
 * 竖屏取 `min(宽, 高)`,横屏取 `max(宽, 高)` —— `Configuration.screenWidthDp`
 * 本身随旋转变化,但这里刻意用 min/max 消掉"当前是哪个方向"的时序问题:
 * 两个方向各自要用的宽度是屏幕的**固有属性**(短边/长边),不随此刻的旋转状态跳变。
 *
 * ⚠️ 这是**估算**,不是网格容器的实测宽度:排序页容器是 `fillMaxWidth` 且竖屏下
 * 左右安全区 inset 为 0,故两者相等;设置页拿不到容器,只能用屏幕宽。
 * 设置页显示的是"跟随自适应会得到几列"的预览值,与排序页的真实值在
 * 有横向系统 inset 的设备上可能差 1 列 —— 这是刻意接受的近似(两边都以同一算式为准)。
 */
fun orientationWidthDp(configuration: Configuration, portrait: Boolean): Int =
    if (portrait) {
        minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    } else {
        maxOf(configuration.screenWidthDp, configuration.screenHeightDp)
    }

/** [orientationWidthDp] 的 `Dp` 版本,便于直接喂给 [adaptiveColumnCount]。 */
fun orientationWidth(configuration: Configuration, portrait: Boolean): Dp =
    orientationWidthDp(configuration, portrait).dp
