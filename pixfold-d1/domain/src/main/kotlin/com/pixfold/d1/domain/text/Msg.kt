package com.pixfold.d1.domain.text

/**
 * 一条**尚未解析成字符串**的文案:键 + 参数。
 *
 * ## 为什么不是 String
 *
 * 领域层在建计划时产出文案(冲突警告、确认框正文、计划行…)。若直接产出 String,
 * 语言的解析时刻就等于建计划的时刻 —— 用户之后切换语言,屏幕上已存在的对象仍是旧语言。
 * [Msg] 按构造不含语言,解析推迟到 app 层渲染,结构上排除这一类缺陷。
 *
 * ## 参数
 *
 * [args] 按位置对应资源文件里 `%1$s` / `%2$d` 之类的占位符,**顺序与类型由
 * [TextKey] 的 KDoc 逐条写明**(见 `TextKey.kt` 与 app 侧 `PixFoldText.kt` 的解析表)。
 * 参数本身若是**数值**,优先保留为 `Int`,以便英文用 `<plurals>` 正确变格。
 *
 * ## 可比较 / 可序列化
 *
 * [Msg] 是纯数据,`==` 即"同键同参",因此领域测试可以直接断言键与参数
 * (既比"断言中文措辞"稳 —— 改措辞不会误报,又比"删掉断言"强 —— 真的验证了产出)。
 */
data class Msg(val key: TextKey, val args: List<Any> = emptyList())

/** 便捷构造:无参文案。 */
fun msg(key: TextKey): Msg = Msg(key)

/** 便捷构造:带参文案。 */
fun msg(key: TextKey, vararg args: Any): Msg = Msg(key, args.toList())
