package com.cyanix.pixfold

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.ui.organize.TAG_NEXT_STEP
import com.cyanix.pixfold.ui.organize.TAG_PROPOSAL_LIST
import com.cyanix.pixfold.ui.organize.collectionTag
import com.cyanix.pixfold.ui.organize.proposalFieldTag
import com.cyanix.pixfold.ui.library.TAG_B_STEP_METADATA
import com.cyanix.pixfold.ui.library.metaFieldTag
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P1 #7：`PixFoldSessionViewModel` / `PixFoldSession` 配置变更保态与 `onCleared()` 资源清理测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1000dp")
class PixFoldSessionViewModelTest {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `ViewModel retains same session across provider lookups and shuts down on onCleared`() {
        val store = ViewModelStore()
        val shutdownCalls = AtomicInteger(0)
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)

        val vm1 = ViewModelProvider(store, factory)[PixFoldSessionViewModel::class.java]
        vm1.sessionFactory = { ctx, dispatcher ->
            createDefaultPixFoldSession(
                context = ctx,
                probeDispatcher = dispatcher,
                workspaceSourceOverride = ReadyWorkspaceSource(),
                onShutdownImageLoader = { shutdownCalls.incrementAndGet() },
            )
        }

        val session1 = vm1.session
        // 模拟配置变更重建 Activity 后，通过同一 ViewModelStore 重新取 ViewModel
        val vm2 = ViewModelProvider(store, factory)[PixFoldSessionViewModel::class.java]
        val session2 = vm2.session

        assertSame(vm1, vm2, "配置变更后应复用同一 ViewModel 实例")
        assertSame(session1, session2, "配置变更后应复用同一 PixFoldSession")
        assertSame(session1.workspaceController, session2.workspaceController)
        assertSame(session1.thumbnailSource, session2.thumbnailSource)
        assertSame(session1.volumeLoader, session2.volumeLoader)
        assertFalse(session1.isClosed, "活跃期间不应提前关闭会话")
        assertEquals(0, shutdownCalls.get())

        // 模拟 Activity 彻底销毁 → ViewModelStore.clear() → ViewModel.onCleared()
        store.clear()

        assertTrue(session1.isClosed, "onCleared() 必须关闭 PixFoldSession")
        assertEquals(1, shutdownCalls.get(), "onCleared() 必须调用 coilImageLoader.shutdown() 恰好一次")
    }

    @Test
    fun `PixFoldApp preserves unsaved naming edits across recreation when hosted by PixFoldSession`() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vm = ViewModelProvider(store, factory)[PixFoldSessionViewModel::class.java]
        vm.sessionFactory = { ctx, dispatcher ->
            createDefaultPixFoldSession(
                context = ctx,
                probeDispatcher = dispatcher,
                workspaceSourceOverride = ReadyWorkspaceSource(),
            )
        }
        val session = vm.session

        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            PixFoldApp(
                session = session,
                probeDispatcher = Dispatchers.Unconfined,
            )
        }
        rule.waitForIdle()

        // 进入整理图片 → 切到 cn 集合 → 下一步到命名页 → 编辑第一张图覆盖名
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(collectionTag("cn")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_NEXT_STEP).performClick()
        rule.waitForIdle()

        val id = "cn-3"
        rule.onNodeWithTag(TAG_PROPOSAL_LIST).performScrollToNode(hasTestTag(proposalFieldTag(id)))
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("旋转保留名.jpg")
        rule.waitForIdle()

        // 模拟真实配置变更重建（保存/恢复 NavBackStack 等 rememberSaveable 状态，并销毁重建整棵组合树）
        restorationTester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertEquals(
            "旋转保留名.jpg",
            session.namingState.overrides[id],
            "PixFoldSession 必须在配置变更重建后保留未保存的命名覆盖",
        )
        assertEquals("cn", session.activeCollectionId, "PixFoldSession 必须在配置变更后保留当前选中的集合")
        rule.onNodeWithTag(TAG_PROPOSAL_LIST).performScrollToNode(hasTestTag(proposalFieldTag(id)))
        val restoredField = rule.onNodeWithTag(proposalFieldTag(id))
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("旋转保留名.jpg", restoredField, "重建后的命名页输入框仍应显示保留的自定义名")

        store.clear()
    }

    @Test
    fun `PixFoldApp preserves library metadata edits in session and resets when returning home`() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vm = ViewModelProvider(store, factory)[PixFoldSessionViewModel::class.java]
        vm.sessionFactory = { ctx, dispatcher ->
            createDefaultPixFoldSession(
                context = ctx,
                probeDispatcher = dispatcher,
                workspaceSourceOverride = ReadyWorkspaceSource(),
            )
        }
        val session = vm.session

        rule.setContent {
            PixFoldApp(
                session = session,
                probeDispatcher = Dispatchers.Unconfined,
            )
        }
        rule.waitForIdle()

        // 进入打包漫画 → 元数据页 → 修改第一卷标题
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_B_STEP_METADATA).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("自定义旋转标题")
        rule.waitForIdle()

        assertEquals(
            "自定义旋转标题",
            session.volumeLoader.volumes.first().title,
            "元数据编辑应回写到 session.volumeLoader 以跨配置变更存活",
        )

        store.clear()
    }

    @Test
    fun `returning to HomeRoute resets workflow session state`() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vm = ViewModelProvider(store, factory)[PixFoldSessionViewModel::class.java]
        vm.sessionFactory = { ctx, dispatcher ->
            createDefaultPixFoldSession(
                context = ctx,
                probeDispatcher = dispatcher,
                workspaceSourceOverride = ReadyWorkspaceSource(),
            )
        }
        val session = vm.session

        var hostKey by mutableIntStateOf(0)
        rule.setContent {
            key(hostKey) {
                PixFoldApp(
                    session = session,
                    probeDispatcher = Dispatchers.Unconfined,
                )
            }
        }
        rule.waitForIdle()

        // 进入整理图片 → 改名
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(collectionTag("cn")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_NEXT_STEP).performClick()
        rule.waitForIdle()
        val id = "cn-3"
        rule.onNodeWithTag(TAG_PROPOSAL_LIST).performScrollToNode(hasTestTag(proposalFieldTag(id)))
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("将被重置.jpg")
        rule.waitForIdle()
        assertEquals("将被重置.jpg", session.namingState.overrides[id])

        // 切换 key 重新挂载 PixFoldApp（新 NavBackStack 落在 HomeRoute），触发 HomeRoute 的 LaunchedEffect(Unit)
        hostKey++
        rule.waitForIdle()

        assertTrue(
            session.namingState.overrides.isEmpty(),
            "回到 HomeRoute 时必须清空工作流 A 的命名覆盖，维持既定返回首页复位语义",
        )

        store.clear()
    }
}
