package com.cyanix.pixfold.data.saf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `outputDirRelOf` 的路径反解（D2b，2026-10-19）。
 *
 * 这个函数只有三行，但它错了的后果**不是报错而是静默打错地方或全量失败**：
 *
 * - 不去前缀 ⇒ SAF 会去找「根下的 `test` 目录」（不存在）⇒ 每卷
 *   `Failed("无法为目标卷打开输出流")`，用户看到「打包失败」却不知为什么；
 * - 剥多了前缀 ⇒ 写到错误的目录，而且**看起来成功了**（报告里全是 Written），
 *   用户回头在期望的位置找不到文件；
 * - 把 `""` 和 `根显示名` 当成不同输入 ⇒ 根下一级的卷会走进「找不到目录」分支。
 *
 * 故三种输入都必须单独钉住，且**不含根名的路径必须原样返回**（不猜）。
 */
class OutputDirRelTest {

    @Test
    fun `根显示名本身表示根目录`() {
        assertEquals("", outputDirRelOf("test", "test"))
    }

    @Test
    fun `空串表示根目录`() {
        assertEquals("", outputDirRelOf("", "test"))
    }

    @Test
    fun `带根名前缀的路径去掉前缀`() {
        assertEquals("系列A", outputDirRelOf("test/系列A", "test"))
    }

    @Test
    fun `多级目录只去根名一层`() {
        assertEquals("系列A/第01卷", outputDirRelOf("test/系列A/第01卷", "test"))
    }

    /**
     * 不含根名前缀时**原样返回** —— 不许按斜杠猜着剥。
     *
     * 用户的目录本身就叫「我的收藏/合集」时，剥掉第一段会把文件写到 `合集` 里。
     * 宁可让它找不到目录（`openFor` 返 null ⇒ 该卷记 Failed，一个字节不写），
     * 也不要写错位置。
     */
    @Test
    fun `不含根名前缀的路径原样返回`() {
        assertEquals("我的收藏/合集", outputDirRelOf("我的收藏/合集", "test"))
    }

    /**
     * 前缀必须**整段**匹配：`test2/系列A` 不能被当成 `test` 之下的 `2/系列A`。
     *
     * 真实场景：用户有两个工作区 `test` 与 `test2`，切换后残留的 `outputDir`
     * 仍指向旧工作区。按字符串前缀硬剥会写出 `2/系列A` 这个不存在的层级。
     */
    @Test
    fun `同前缀的兄弟目录不被误剥`() {
        assertEquals("test2/系列A", outputDirRelOf("test2/系列A", "test"))
    }

    /** 目录名里含斜杠的中间段不被拆开（只削掉根名那一层）。 */
    @Test
    fun `目录名中的斜杠不被特殊处理`() {
        assertEquals("A/B/C", outputDirRelOf("test/A/B/C", "test"))
    }

    /** 根显示名为空串时（尚无工作区）不做任何剥除。 */
    @Test
    fun `根显示名为空时原样返回`() {
        assertEquals("系列A", outputDirRelOf("系列A", ""))
    }
}
