package com.pixfold.d1

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U0 负向对照:**本测试预期失败**。
 *
 * 用一个普通 var(而非 mutableStateOf)承载状态 —— 点击真的改了数据,但 Compose 不会重组,
 * 即归档原型"数据对了但界面不动"的缺陷类别(拖拽 bug 连修 4 轮的根因)。
 * 若本测试**通过**了,说明 UI 断言无法捕获该类别,第 2 层自证失效,必须排查测试基建。
 *
 * 默认测试任务会排除本类(见 app/build.gradle.kts 的 testOptions);
 * 需显式验证时运行:gradlew :app:testDebugUnitTest -PincludeNegativeControl
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NegativeControlTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `UI assertion catches non-observable state - EXPECTED TO FAIL`() {
        rule.setContent { SilentStateScreen() }
        rule.onNodeWithTag("silent-count").assertTextEquals("count=0")
        rule.onNodeWithText("silent-inc").performClick()
        // 数据其实已变成 1,但界面不会重组 -> 这里必须失败
        rule.onNodeWithTag("silent-count").assertTextEquals("count=1")
    }
}

@Composable
private fun SilentStateScreen() {
    var count = 0
    Column {
        Text(text = "count=$count", modifier = Modifier.testTag("silent-count"))
        Button(onClick = { count++ }) { Text("silent-inc") }
    }
}
