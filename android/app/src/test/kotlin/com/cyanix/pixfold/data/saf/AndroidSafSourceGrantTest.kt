package com.cyanix.pixfold.data.saf

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/**
 * [AndroidSafSource.isPersistentGrant] 在**没有授权**时必须返回 false。
 *
 * 这是下分区「只记住了路径」的唯一判据：若它恒返 true，用户会看到一条挂着 ✓
 * 却一点就报「授权已失效」的记录，且永远不会落到下区。
 *
 * Robolectric 下 `persistedUriPermissions` 恒为空，故这里只能断 false 这一侧；
 * true 那一侧需要真实 DocumentsProvider，属真机核对项（spec §10 第 3 条 ⑤）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidSafSourceGrantTest {

    @Test
    fun `没有系统授权时返回 false`() {
        val source = AndroidSafSource(ApplicationProvider.getApplicationContext())
        assertFalse(source.isPersistentGrant("content://tree/never-granted"))
    }
}
