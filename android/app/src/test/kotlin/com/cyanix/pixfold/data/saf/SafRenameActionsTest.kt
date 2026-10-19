package com.cyanix.pixfold.data.saf

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SAF 改名端口的**契约**（不碰真 provider：`renameDocument` 由参数注入）。
 *
 * 这一层存在的理由：`renameDocument` 的返回值语义在真机上被 ROM 大幅修改
 * （有的返回新 uri、有的返 null、有的抛私有异常），而这些差异必须**由这一层吸收**，
 * 不能让领域层去处理。所以这里逐条钉住接口承诺。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafRenameActionsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a provider that reports the new uri returns it`() {
        val renamed = "content://doc/renamed".let(Uri::parse)
        val actions = SafRenameActions(context) { _, _, _ -> renamed }

        assertEquals(
            "content://doc/renamed",
            actions.rename("content://doc/old", "新名.jpg"),
        )
    }

    /**
     * provider 返回 null 时必须**如实报失败**。
     *
     * 若写成乐观成功（返回原 uri），上行会记「成功」，而撤销拿着那个旧 uri 去改名 ——
     * 那个 document 已经不存在了（改名后 id 变了），撤销逐项静默失败。
     * 变异实测（2026-10-19，M8）：把这里改成 `(renamed ?: documentUri).toString()`
     * 后本条即红。
     */
    @Test
    fun `a provider that reports no uri is a failure not a silent success`() {
        val actions = SafRenameActions(context) { _, _, _ -> null }

        assertNull(
            actions.rename("content://doc/old", "新名.jpg"),
            "provider 没报告新 uri 就不是成功 —— 谎报成功会让撤销打在一个已不存在的 document 上",
        )
    }

    @Test
    fun `a throwing provider is a failure not a crash`() {
        val actions = SafRenameActions(context) { _, _, _ -> error("ROM 私有异常") }

        assertNull(actions.rename("content://doc/old", "新名.jpg"))
    }

    @Test
    fun `the new name is passed through unchanged`() {
        val seen = mutableListOf<String>()
        val actions = SafRenameActions(context) { _, _, name ->
            seen += name
            Uri.parse("content://doc/x")
        }

        actions.rename("content://doc/old", "彼女がボクの知らないところで_001.jpg")

        // 平台层不得清洗 / 截断 / 补扩展名 —— 名字的规则全在领域层（`NameCleaning`）。
        // 这里擅自再处理一次，会让「界面预览的名字」与「磁盘上的名字」不一致。
        assertEquals(listOf("彼女がボクの知らないところで_001.jpg"), seen)
    }

    @Test
    fun `an unrecognized uri still reaches the provider`() {
        // 我们自己不解析 uri 的语义：provider 认不认是它的事。
        // 在这里提前拒绝会让「provider 其实支持」的情形也被判失败。
        var called = false
        val actions = SafRenameActions(context) { _, _, _ ->
            called = true
            Uri.parse("content://doc/x")
        }

        actions.rename("not-a-content-uri", "x.jpg")

        assertEquals(true, called)
    }
}
