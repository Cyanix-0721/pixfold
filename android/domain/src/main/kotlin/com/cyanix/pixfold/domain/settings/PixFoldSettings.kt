package com.cyanix.pixfold.domain.settings

/**
 * 主题模式(用户可选的"三大方案")。
 *
 * [System] 跟随系统深浅色 —— Android 惯例,也是默认。
 */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/**
 * 色彩风格 —— 同一颗种子色可派生出气质不同的整套配色。
 *
 * 取值与 Material Color Utilities 的 `DynamicScheme` 变体一一对应
 * (TonalSpot / Vibrant / Expressive / …),由 `app` 层负责映射到具体算法类。
 *
 * **为什么把它放领域层**:它是"用户选了什么"的事实,与"用什么库算"无关;
 * 将来若更换算法库,这里的取值语义不变。
 */
enum class ColorStyle {
    TonalSpot,
    Vibrant,
    Expressive,
    Neutral,
    Monochrome,
    Fidelity,
    Content,
    Rainbow,
    FruitSalad,
}

/**
 * 色彩标准 —— Material 3 色彩规范的世代。
 *
 * [V2021] 最初的 M3 规范;[V2025] 更新的规范(色调映射与容器策略不同)。
 * 对应 Material Color Utilities 的 `ColorSpec.SpecVersion`。
 */
enum class ColorSpecVersion {
    V2021,
    V2025,
}

/**
 * 强调色来源。
 *
 * [System] 的语义是**跟随系统取色**(即 Monet / Material You):
 * 从系统壁纸派生种子色。其余取值是固定的预设种子色。
 *
 * > **设计说明**:没有单独的"Monet 开关"字段 ——
 * > "开 Monet"与"强调色=跟随系统"是**同一件事**,
 * > 用两个字段表达会产生默认值自相矛盾、以及"开着 Monet 又选了蓝色"这类非法组合。
 * > 故 [PixFoldSettings.monetEnabled] 由本字段**派生**,单一事实来源。
 */
enum class AccentChoice {
    System,
    Default,
    Blue,
    Green,
    Purple,
    Orange,
    Pink,
}

/**
 * 应用语言。
 *
 * [System] 跟随系统语言设置。
 */
enum class AppLanguage {
    System,
    Chinese,
    English,
}

/**
 * 应用设置(纯数据,可持久化)。
 *
 * **本类不含任何面向用户的文案** —— 枚举取值 → 显示文案的映射在 `app` 层
 * 通过字符串资源完成,以支持中英双语(见设置规格 §9)。
 *
 * 默认值意图见各字段注释;整体原则是"**默认值让首次运行的行为可预期**"。
 */
data class PixFoldSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    /**
     * 强调色来源。
     *
     * 默认 [AccentChoice.System] = 跟随系统取色,与升级前的行为一致
     * (旧的 `PixFoldTheme(dynamicColor = true)`)—— **首次运行外观不变**。
     */
    val accent: AccentChoice = AccentChoice.System,
    val colorStyle: ColorStyle = ColorStyle.TonalSpot,
    val colorSpec: ColorSpecVersion = ColorSpecVersion.V2021,
    /** 深色模式下是否使用纯黑(`#000000`)背景。默认关。 */
    val pureBlack: Boolean = false,
    /**
     * 预测性返回动画。
     *
     * 开启时,返回手势进行中会**跟随手势进度**做缩放/淡出预览(Android 13+);
     * 关闭时退化为普通返回(立刻切走,无手势跟随动画)。
     *
     * 默认开 —— 这是系统本来的行为,关掉只是"去掉动画",不应作为默认。
     *
     * > **实现说明**:`android:enableOnBackInvokedCallback` 是 manifest 静态属性、
     * > 无法运行时改;但 androidx 的 `PredictiveBackHandler` 让应用能**消费手势进度**,
     * > 于是"是否做手势跟随动画"是可以运行时切换的。本开关控制的就是后者。
     */
    val predictiveBackAnimation: Boolean = true,
    /**
     * 网格视图的**竖屏**默认列数(用户 2026-09-26 裁决;第二轮补充「跟随自适应」)。
     *
     * `null` = **跟随自适应** —— 由屏幕可用宽与最小卡宽实时算出(见
     * `com.cyanix.pixfold.ui.components.adaptiveColumnCount`);非 null = 用户固定选的列数。
     *
     * **为什么默认 null 而不是写死一个数**:默认列数必须随设备分辨率走,
     * 而本类在纯 JVM 模块里、编译期不知道屏幕宽度。`null` 是"还没选过"的忠实表示,
     * 于是"恢复默认"也自然回到跟随自适应。
     *
     * 竖屏与横屏**分开两个字段**:同一台设备在两个方向上的合适列数差很远
     * (PJX110 竖屏 361dp 算出 4 列,横屏 794dp 算出 9 列),共用一个值必然有一边难看。
     */
    val portraitDefaultColumns: Int? = null,
    /** 网格视图的**横屏**默认列数。语义同 [portraitDefaultColumns]。 */
    val landscapeDefaultColumns: Int? = null,
    val language: AppLanguage = AppLanguage.System,
) {
    /**
     * 是否使用系统动态取色(Monet)。
     *
     * **由 [accent] 派生,不单独存储** —— 见 [AccentChoice] 的设计说明。
     */
    val monetEnabled: Boolean get() = accent == AccentChoice.System

    /** 选择强调色。选 [AccentChoice.System] 即等于开启 Monet。 */
    fun withAccent(next: AccentChoice): PixFoldSettings = copy(accent = next)

    /**
     * Monet 开关。
     *
     * 开启 -> 强调色回到 [AccentChoice.System];
     * 关闭 -> 回到 [AccentChoice.Default] 预设(而不是保留 System 造成"关了却仍跟随系统")。
     */
    fun withMonet(enabled: Boolean): PixFoldSettings = copy(
        accent = if (enabled) AccentChoice.System else AccentChoice.Default,
    )
}
