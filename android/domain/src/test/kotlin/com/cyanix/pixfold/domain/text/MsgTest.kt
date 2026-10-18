package com.cyanix.pixfold.domain.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 文案对象与它的两个便捷构造。
 *
 * `msg(...)` 这两个助手目前**没有任何生产调用点**(各层都直接写 `Msg(key, listOf(...))`),
 * 因此在变异扫荡里"丢掉全部参数"与"参数顺序颠倒"两个变异体都存活了。
 * 它们仍是 `Msg.kt` 公开契约的一部分(KDoc 明写"args 按位置对应资源文件的占位符"),
 * 故此处把契约钉住 —— 将来谁改成用助手构造,位置语义不会静默错位。
 */
class MsgTest {

    @Test
    fun `a message without arguments carries an empty list`() {
        assertEquals(Msg(TextKey.PendingVolume), msg(TextKey.PendingVolume))
        assertEquals(emptyList(), msg(TextKey.PendingVolume).args)
    }

    @Test
    fun `vararg arguments keep their order and types`() {
        // 位置即语义:英文用 <plurals> 变格时,args[0] 必须还是那个计数
        val m = msg(TextKey.WarnDuplicate, 2, "same.jpg")
        assertEquals(TextKey.WarnDuplicate, m.key)
        assertEquals(listOf<Any>(2, "same.jpg"), m.args, "参数必须按位置原样保留")
        assertEquals(2, m.args[0])
    }

    @Test
    fun `equality is key plus arguments`() {
        assertEquals(msg(TextKey.WarnTooLong, 1), msg(TextKey.WarnTooLong, 1))
        assertEquals(Msg(TextKey.WarnTooLong, listOf(1)), msg(TextKey.WarnTooLong, 1))
    }
}
