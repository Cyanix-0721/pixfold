package com.cyanix.pixfold.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SharedPrefsSettingsStore] 的持久化断言 —— **本仓库第一个直接测这个类的用例**。
 *
 * 为什么单独测持久化:其余所有设置相关测试(设置页 UI、领域默认值)都只走内存中的
 * [PixFoldSettings],**存盘这一段此前完全无人守护**。而"存了没读回来"这类缺陷的
 * 典型症状正是用户抱怨过的**"设置改完重启就没了"** —— 界面全绿、单测全绿,但重启丢。
 *
 * 本轮新加的 `portraitDefaultColumns` / `landscapeDefaultColumns` 是**仓库里第一次
 * 存 `Int?`**(此前只有 enum 名与 Boolean),它的 `null` 语义(跟随自适应)必须
 * **删除 key** 而不是写哨兵数字,否则"跟随自适应"会被读成一个具体列数。这里把
 * 三条边界都钉住:非空往返、null 往返(含"key 真的没了")、非法值回退。
 *
 * key 名在这里**写死字符串**:[SharedPrefsSettingsStore] 的 companion 是 `private`,
 * 测试无法引用。这其实是好事 —— 它同时锁住了存储格式,防止有人悄悄改 key 名
 * (改了会让所有老用户的设置一次性丢失,而其它测试不会发现)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPrefsSettingsStoreTest {

    /** 与 [SharedPrefsSettingsStore] companion 中的常量逐字一致(见类注释的说明)。 */
    private companion object {
        const val PREFS_NAME = "pixfold_settings"
        const val KEY_COLUMNS_PORTRAIT = "columns_portrait"
        const val KEY_COLUMNS_LANDSCAPE = "columns_landscape"
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例都从干净状态开始:Robolectric 在同一 JVM 里复用 Application,
        // 不清的话前一个用例写下的偏好会被后一个读到。
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun store() = SharedPrefsSettingsStore(context)

    /** 一个"每个字段都不是默认值"的实例,用来证明不是靠默认值蒙对的。 */
    private val custom = PixFoldSettings(
        themeMode = ThemeMode.Dark,
        accent = AccentChoice.Purple,
        colorStyle = ColorStyle.Vibrant,
        pureBlack = true,
        predictiveBackAnimation = false,
        portraitDefaultColumns = 3,
        landscapeDefaultColumns = 7,
        language = AppLanguage.English,
    )

    /**
     * 全部字段的存→读往返。
     *
     * 断言**整个对象相等**而不是逐字段:漏存任何一项都会红,新增字段忘了接线也会红
     * (新字段一旦在 `custom` 里给了非默认值,回到默认就说明没存)。
     */
    @Test
    fun `save then load round trips every field`() {
        store().save(custom)
        assertEquals(custom, store().load(), "存盘后读回应与写入完全一致")
    }

    /**
     * 固定列数真的落到**它自己的 key** 上。
     *
     * 只断言 `load()` 相等还不够 —— 若两个字段的 key 写反(竖屏读横屏的 key),
     * 而测试里两者恰好给同一个数字,往返断言会假通过。这里故意让两值不同,
     * 并直接读偏好文件确认落点。
     */
    @Test
    fun `each orientation is stored under its own key`() {
        store().save(custom)
        assertEquals(3, prefs().getInt(KEY_COLUMNS_PORTRAIT, 0), "竖屏默认列数应落在本人 key 上")
        assertEquals(7, prefs().getInt(KEY_COLUMNS_LANDSCAPE, 0), "横屏默认列数应落在本人 key 上")
    }

    /**
     * `null`(跟随自适应)**必须是"key 不存在"**,而不是某个哨兵数字。
     *
     * 这条是 [SharedPrefsSettingsStore] KDoc 里写明的设计决定,也是最容易被
     * "顺手简化"成 `putInt(key, value ?: 0)` 的地方 —— 那样读回来 `takeIf { it > 0 }`
     * 虽然也能退回 null,但偏好文件里会留下一个 `0`,将来若有合法取值撞上就静默出错。
     * 故这里断言的是 `containsKey == false` 这个**存储层事实**。
     */
    @Test
    fun `follow adaptive is stored as an absent key`() {
        store().save(PixFoldSettings(portraitDefaultColumns = 4, landscapeDefaultColumns = 6))
        assertEquals(4, prefs().getInt(KEY_COLUMNS_PORTRAIT, 0))

        // 再存一次"跟随自适应" ⇒ key 应被删除
        store().save(PixFoldSettings(portraitDefaultColumns = null, landscapeDefaultColumns = null))
        assertNull(store().load().portraitDefaultColumns, "存了 null 应读回 null")
        assertNull(store().load().landscapeDefaultColumns, "存了 null 应读回 null")
        assertEquals(
            false,
            prefs().contains(KEY_COLUMNS_PORTRAIT),
            "null 应删除 key,而不是写哨兵数字",
        )
        assertEquals(
            false,
            prefs().contains(KEY_COLUMNS_LANDSCAPE),
            "null 应删除 key,而不是写哨兵数字",
        )
    }

    /**
     * 从未存过(全新安装)⇒ 两个字段都是 `null`,即跟随自适应。
     *
     * 这与领域层的默认值一致,也是用户要的行为:新机器上默认按分辨率自适应。
     */
    @Test
    fun `missing keys mean follow adaptive`() {
        val loaded = store().load()
        assertNull(loaded.portraitDefaultColumns, "全新安装应是跟随自适应")
        assertNull(loaded.landscapeDefaultColumns, "全新安装应是跟随自适应")
    }

    /**
     * 非法值(手改偏好文件、或将来被删掉的取值)回退到 `null`,**绝不抛异常**。
     *
     * 设置读取发生在应用启动路径上:抛异常 = 启动即崩。0 与负数在界面上无意义
     * (渲染出空网格),故一并按"没选过"处理。
     */
    @Test
    fun `illegal stored value falls back to follow adaptive`() {
        listOf(0, -1, -9).forEach { illegal ->
            prefs().edit().putInt(KEY_COLUMNS_PORTRAIT, illegal).commit()
            assertNull(
                store().load().portraitDefaultColumns,
                "偏好文件里的非法值 $illegal 应回退为 null(跟随自适应),而不是抛异常或渲染空网格",
            )
        }
    }

    /**
     * 覆盖写:先固定再改自适应,读回必须是后者(不是"两者都在")。
     *
     * 单独测 `remove` 路径,是因为"删 key"写错成"不写"时,旧值会残留 ——
     * 用户会发现"我明明选了跟随自适应,列数却还卡在 3"。
     */
    @Test
    fun `saving follow adaptive clears a previously fixed value`() {
        store().save(PixFoldSettings(portraitDefaultColumns = 3))

        store().save(PixFoldSettings(portraitDefaultColumns = null))

        assertNull(store().load().portraitDefaultColumns, "改成跟随自适应后旧值不得残留")
    }
}
