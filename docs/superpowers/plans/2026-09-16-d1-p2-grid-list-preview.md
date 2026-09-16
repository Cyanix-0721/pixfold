# D1 阶段 P2：缩略图双视图 + 大图预览 实施计划

> **面向 Agent 执行者**：必需子技能：使用 superpower-subagent-driven-development（推荐）或 superpower-executing-plans 按任务逐项执行本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 交付 HANGOFF §9《D1 验收清单》第 1、2 项——缩略图**网格/列表双视图可切换且渲染正确**，以及**大图预览**（自适应窗口、可缩放、可拖动平移、可翻页）。

**架构：** 延续 P1 的两模块结构。缩略图为**程序化确定性绘制**（由 `SourceItem.seed` 派生，不引入任何图片资源或加载库）；视图与预览均为纯 Compose，状态用 `mutableStateOf` 持有；预览的缩放/平移用 `graphicsLayer` + `detectTransformGestures` 自研，**不引入三方手势库**。

**技术栈：** 同 P1（Kotlin 2.2.10 / AGP 9.4.0 / Compose BOM 2026.02.01 / material3 1.4.0 / Robolectric 4.17）。**零三方依赖**。

**规格：** `docs/superpowers/specs/2026-09-16-d1-interaction-prototype-design.md`（§10.1 页面结构、§10.3 UI 约定、§2 约束 13 的 M3 合规、§12.2 第 2 层测试）

**前置：** P1 已完成并合入 `dev`（`docs/superpowers/plans/2026-09-16-d1-p1-scaffold-domain.md`）。

## 全局约束

沿用 P1《全局约束》全部 13 条，另加本阶段要点：

1. **验收判据不可调整**：本阶段覆盖清单第 1、2 项；不通过即记待修项。
2. **修复自证三层**：交互改动必须过「领域单测（如需）→ UI 层断言 → 真机实证」。
3. **零三方依赖**：不引入 Coil/Glide/手势库/缩放库；缩略图自绘，手势自研。
4. **M3 合规**（HANGOFF §6.5）：颜色/排版/形状取 `MaterialTheme` 语义角色；触控 ≥48dp；纯图标按钮给 `contentDescription`；深色模式可用。
5. **信息密度**（换栈动因）：网格列数按窗口宽度自适应，一屏应能看到尽量多缩略图，**不得复现**"元素过大、一屏看不了多少"。
6. **edge-to-edge**：新页面必须消费系统栏 inset（P1 真机教训，§12.5）。
7. **每完成一个任务即提交**（只 commit，push 由用户处理）。
8. **`app` 模块测试的注解用 `org.junit.Test`**（不是 `kotlin.test.Test`——`app` 的测试类路径上
   `kotlin.test` 的注解不可用，只有断言函数 `kotlin.test.assertEquals` 等可用）。
   纯逻辑测试若放 `domain` 模块则用 `kotlin.test.Test`。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `app/.../ui/components/ProceduralThumb.kt` | 程序化确定性缩略图（Canvas 绘制，seed 派生） |
| `app/.../ui/components/ThumbCard.kt` | 网格卡片（缩略图 + 序号 + 名称 + 选中态） |
| `app/.../ui/components/ThumbRow.kt` | 列表行（缩略图 + 名称 + 相对路径 + 大小） |
| `app/.../ui/workflowa/ViewMode.kt` | `enum class ViewMode { Grid, List }` |
| `app/.../ui/workflowa/SortAndPreviewPage.kt` | 工作流 A 步骤 1：视图切换 + 网格/列表渲染 |
| `app/.../ui/preview/ImagePreview.kt` | 大图预览（自适应/缩放/平移/翻页） |
| `app/.../ui/preview/PreviewState.kt` | 预览状态（缩放、偏移、当前页）与纯函数 |
| `app/.../PixFoldApp.kt` | 修改：加入首页 → 工作流 A 步骤 1 的导航 |
| `app/src/test/.../ProceduralThumbTest.kt` | 缩略图确定性断言 |
| `app/src/test/.../SortAndPreviewPageTest.kt` | 双视图切换 + 渲染断言（验收 1） |
| `app/src/test/.../ImagePreviewTest.kt` | 缩放/平移/翻页断言（验收 2） |

**任务顺序**：1 缩略图 → 2 双视图 → 3 预览状态纯函数 → 4 预览 UI → 5 路由接线 → 6 测试补强 → 7 真机实证与收口。

---

### 任务 1：程序化确定性缩略图

**文件：**
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/ProceduralThumb.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/components/ProceduralThumbTest.kt`

**接口：**
- 依赖输入：无（仅用 seed）
- 对外产出：
  - `@Composable fun ProceduralThumb(seed: Int, modifier: Modifier = Modifier, showIndex: Boolean = true)`
  - `fun seedBaseColor(seed: Int): Color`（纯函数，可单测）
  - `fun seedAccentColor(seed: Int): Color`
  - 任务 2/4 依赖 `ProceduralThumb`。

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.pixfold.d1.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProceduralThumbTest {

    @Test
    fun `same seed yields identical color`() {
        assertEquals(seedBaseColor(42), seedBaseColor(42))
        assertEquals(seedAccentColor(42), seedAccentColor(42))
    }

    @Test
    fun `different seeds yield different colors`() {
        assertNotEquals(seedBaseColor(1), seedBaseColor(2))
    }

    @Test
    fun `color is deterministic across many seeds`() {
        for (s in 0..200) {
            assertEquals(seedBaseColor(s), seedBaseColor(s), "seed=$s 应稳定")
        }
    }

    @Test
    fun `accent is darker than base`() {
        for (s in 0..20) {
            val b = seedBaseColor(s)
            val a = seedAccentColor(s)
            val lumB = 0.299 * b.red + 0.587 * b.green + 0.114 * b.blue
            val lumA = 0.299 * a.red + 0.587 * a.green + 0.114 * a.blue
            assertEquals(true, lumA < lumB, "seed=$s 的 accent 应比 base 暗")
        }
    }

    @Test
    fun `colors are fully opaque`() {
        for (s in 0..20) {
            assertEquals(1f, seedBaseColor(s).alpha)
            assertEquals(1f, seedAccentColor(s).alpha)
        }
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"`
预期：编译失败，`Unresolved reference 'seedBaseColor'`。

- [ ] **步骤 3：编写实现**

```kotlin
package com.pixfold.d1.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 程序化占位缩略图 —— 由 seed 确定性派生,**不引入任何图片资源或加载库**(零三方依赖)。
 * 语义沿用归档原型的 ProceduralThumb:HSL 取色 + 角标三角 + 确定性纹样 + 中央序号,
 * 便于肉眼区分不同图片、也便于测试断言。
 */
fun seedHue(seed: Int): Float = (abs(seed) % 360).toFloat()

/** 基础色:HSL(hue, 0.45, 0.55)。 */
fun seedBaseColor(seed: Int): Color = hslToColor(seedHue(seed), 0.45f, 0.55f)

/** 强调色:同色相、明度更低(0.35),用于右下角三角。 */
fun seedAccentColor(seed: Int): Color = hslToColor(seedHue(seed), 0.45f, 0.35f)

/** 纹样线数量:2 + seed % 3(与归档一致)。 */
fun seedLineCount(seed: Int): Int = 2 + (abs(seed) % 3)

/** 中央显示的大序号:1 + seed % 99。 */
fun seedDisplayNumber(seed: Int): Int = 1 + (abs(seed) % 99)

/** HSL → Color(避免依赖 androidx.compose.ui.graphics.toColor 的 HSL 支持差异,自行实现)。 */
internal fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(h % 2f - 1f))
    val (r1, g1, b1) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

@Composable
fun ProceduralThumb(
    seed: Int,
    modifier: Modifier = Modifier,
    showIndex: Boolean = true,
) {
    val base = seedBaseColor(seed)
    val accent = seedAccentColor(seed)
    val measurer = rememberTextMeasurer()
    val lines = seedLineCount(seed)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(color = base, size = Size(w, h))

        // 右下角三角(模拟"照片"占位)
        val tri = Path().apply {
            moveTo(w, h * 0.35f)
            lineTo(w, h)
            lineTo(w * 0.45f, h)
            close()
        }
        drawPath(tri, color = accent.copy(alpha = 0.8f))

        // 确定性纹样,便于肉眼区分
        val stroke = Stroke(width = 1.5f)
        val lineColor = Color.White.copy(alpha = 0.25f)
        for (i in 1..lines) {
            val dx = ((seed shr i) and 0x7fffffff) % (w * 0.8f).toInt().coerceAtLeast(1)
            drawLine(
                color = lineColor,
                start = Offset(dx.toFloat(), 0f),
                end = Offset(dx + 10f, h),
                strokeWidth = stroke.width,
            )
        }

        if (showIndex) {
            val text = seedDisplayNumber(seed).toString()
            val layout = measurer.measure(
                text = text,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = (h * 0.38f).coerceAtLeast(10f).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset((w - layout.size.width) / 2f, (h - layout.size.height) / 2f),
            )
        }
    }
}
```

- [ ] **步骤 4：运行测试并确认其通过**

运行：`cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\run-gradle.bat :app:testDebugUnitTest --no-daemon --console=plain"`
预期：`BUILD SUCCESSFUL`，5 项通过。

- [ ] **步骤 5：提交**

```bash
git add pixfold-d1/app
git commit -m "feat(d1/ui): 程序化确定性缩略图(seed 派生色与纹样,零资源零三方库)"
```

---

### 任务 2：网格/列表双视图

**文件：**
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/ViewMode.kt`
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/ThumbCard.kt`
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/components/ThumbRow.kt`
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/workflowa/SortAndPreviewPage.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/workflowa/SortAndPreviewPageTest.kt`

**接口：**
- 依赖输入：P1 的 `MockData.workspaceA`、`SourceItem`；任务 1 的 `ProceduralThumb`
- 对外产出：
  - `enum class ViewMode { Grid, List }`
  - `@Composable fun SortAndPreviewPage(items: List<SourceItem>, modifier: Modifier = Modifier, initialMode: ViewMode = ViewMode.Grid, onOpenPreview: (Int) -> Unit = {})`
  - 常量 `TAG_MODE_TOGGLE`、`TAG_GRID`、`TAG_LIST`
  - 任务 5 依赖 `SortAndPreviewPage`。

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.pixfold.d1.ui.workflowa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.SourceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortAndPreviewPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val items: List<SourceItem> =
        MockData.workspaceA.collections.first { it.id == "misc" }.images

    @Test
    fun `defaults to grid view`() {
        rule.setContent { SortAndPreviewPage(items) }
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `toggling switches to list and back`() {
        rule.setContent { SortAndPreviewPage(items) }
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()

        rule.onNodeWithTag(TAG_MODE_TOGGLE).performClick()
        rule.onNodeWithTag(TAG_LIST).assertIsDisplayed()

        rule.onNodeWithTag(TAG_MODE_TOGGLE).performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `grid renders one card per item`() {
        rule.setContent { SortAndPreviewPage(items, initialMode = ViewMode.Grid) }
        // 以 testTag 计数:网格卡片数应等于条目数
        assertEquals(items.size, rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().size)
    }

    @Test
    fun `list shows relative path which grid does not`() {
        rule.setContent { SortAndPreviewPage(items, initialMode = ViewMode.List) }
        // 列表行展示相对路径(信息更密);条目 dir 为空时 relPath == fileName
        assertTrue(rule.onAllNodesWithTag(TAG_LIST_ROW).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `clicking a grid card reports its index`() {
        var opened = -1
        rule.setContent {
            SortAndPreviewPage(items, initialMode = ViewMode.Grid, onOpenPreview = { opened = it })
        }
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        assertEquals(0, opened)
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**

运行：`:app:testDebugUnitTest`
预期：编译失败，`Unresolved reference 'SortAndPreviewPage'`。

- [ ] **步骤 3：编写视图模式与卡片**

`ui/workflowa/ViewMode.kt`：

```kotlin
package com.pixfold.d1.ui.workflowa

/** 缩略图呈现方式:网格(看密度) / 列表(看路径等文字信息)。 */
enum class ViewMode { Grid, List }
```

`ui/components/ThumbCard.kt`：

```kotlin
package com.pixfold.d1.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

const val TAG_GRID_CARD = "grid-card"

/** 网格卡片:缩略图 + 名称。整卡可点(≥48dp)。 */
@Composable
fun ThumbCard(
    item: SourceItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_GRID_CARD)
            .clickable(onClick = onClick)
            .semantics { contentDescription = item.fileName },
    ) {
        Column {
            ProceduralThumb(
                seed = item.seed,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                text = item.fileName,
                modifier = Modifier.padding(6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

> 顶部 import 需含 `com.pixfold.d1.domain.model.SourceItem`。
> `ThumbCard`/`ThumbRow`/`SortAndPreviewPage` 一律直接接收领域层的 `SourceItem`
> （`app` 已依赖 `:domain`，UI 只读其字段，不复制一份投影类型）。

- [ ] **步骤 4：编写列表行与页面**

`ui/components/ThumbRow.kt` 与 `ui/workflowa/SortAndPreviewPage.kt` 按上表职责实现，要点：

- `TAG_LIST` / `TAG_GRID` 分别标在最外层容器；`TAG_MODE_TOGGLE` 标在切换控件上。
- **网格**：`LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 96.dp))`——**列数随窗口自适应**（信息密度要求）。
- **列表**：`LazyColumn`，行高 ≥56dp，展示 `fileName`（主）+ `relPath`/`sizeLabel`（副）。
- 切换控件用 M3 `SegmentedButton`（两个图标：网格/列表），**每个图标按钮须给 `contentDescription`**。
- 页面消费 `WindowInsets.safeDrawing`（P1 真机教训）。
- `onOpenPreview(index)` 在网格卡片/列表行点击时回调其下标。

- [ ] **步骤 5：运行测试并确认其通过**

运行：`:app:testDebugUnitTest`
预期：`BUILD SUCCESSFUL`，5 项通过。

- [ ] **步骤 6：提交**

```bash
git add pixfold-d1/app
git commit -m "feat(d1/ui): 缩略图网格/列表双视图可切换(自适应列数,验收第 1 项)"
```

---

### 任务 3：预览状态纯函数

**文件：**
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/preview/PreviewState.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/preview/PreviewStateTest.kt`

**接口：**
- 依赖输入：无（纯数据）
- 对外产出：
  - `data class PreviewState(val index: Int, val count: Int, val scale: Float, val offsetX: Float, val offsetY: Float)`
  - `fun previewStateOf(index: Int, count: Int): PreviewState`
  - `fun PreviewState.zoomed(factor: Float): PreviewState`（夹取到 `MIN_SCALE..MAX_SCALE`）
  - `fun PreviewState.panned(dx: Float, dy: Float): PreviewState`
  - `fun PreviewState.next(): PreviewState` / `previous(): PreviewState`（到边界不环绕）
  - `fun PreviewState.resetZoom(): PreviewState`
  - 常量 `MIN_SCALE = 1f`、`MAX_SCALE = 5f`
  - 任务 4/6 依赖以上。

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.pixfold.d1.ui.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewStateTest {

    @Test
    fun `initial state is unzoomed`() {
        val s = previewStateOf(0, 5)
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
        assertEquals(0, s.index)
    }

    @Test
    fun `zoom is clamped to range`() {
        val s = previewStateOf(0, 5)
        assertEquals(MAX_SCALE, s.zoomed(99f).scale)
        assertEquals(MIN_SCALE, s.zoomed(0.01f).scale)
    }

    @Test
    fun `zoom to minimum resets offset`() {
        val s = previewStateOf(0, 5).zoomed(3f).panned(50f, 60f)
        assertTrue(s.offsetX != 0f)
        val reset = s.zoomed(MIN_SCALE)
        assertEquals(0f, reset.offsetX, "回到最小缩放应复位平移")
        assertEquals(0f, reset.offsetY)
    }

    @Test
    fun `next stops at last page`() {
        val last = previewStateOf(4, 5)
        assertEquals(4, last.next().index, "末页再下一页应停住,不环绕")
    }

    @Test
    fun `previous stops at first page`() {
        val first = previewStateOf(0, 5)
        assertEquals(0, first.previous().index)
    }

    @Test
    fun `page change resets zoom and offset`() {
        val s = previewStateOf(1, 5).zoomed(3f).panned(20f, 20f)
        val next = s.next()
        assertEquals(2, next.index)
        assertEquals(1f, next.scale, "翻页应复位缩放")
        assertEquals(0f, next.offsetX)
    }

    @Test
    fun `resetZoom restores scale and offset`() {
        val s = previewStateOf(0, 3).zoomed(4f).panned(10f, -10f).resetZoom()
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
    }
}
```

- [ ] **步骤 2：运行测试并确认其失败**（`:Unresolved reference 'previewStateOf'`）

- [ ] **步骤 3：编写实现**

要点：`zoomed` 用 `coerceIn(MIN_SCALE, MAX_SCALE)`；缩放回到 `MIN_SCALE` 时清零偏移；
`next`/`previous` 用 `coerceIn(0, count-1)` 且**翻页时复位 scale=1、offset=0**（避免带着放大状态换图导致错位）。
纯函数、不可变（与 P1 领域层风格一致）。

- [ ] **步骤 4：运行测试并确认其通过**（7 项通过）

- [ ] **步骤 5：提交**

```bash
git add pixfold-d1/app
git commit -m "feat(d1/ui): 预览状态纯函数(缩放夹取/平移复位/翻页边界)"
```

---

### 任务 4：大图预览 UI

**文件：**
- 新建：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/ui/preview/ImagePreview.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/ui/preview/ImagePreviewTest.kt`

**接口：**
- 依赖输入：任务 1 的 `ProceduralThumb`；任务 3 的 `PreviewState` 与纯函数
- 对外产出：
  - `@Composable fun ImagePreview(items: List<SourceItem>, initialIndex: Int, onClose: () -> Unit, modifier: Modifier = Modifier)`
  - 常量 `TAG_PREVIEW`、`TAG_PREVIEW_PAGE_LABEL`、`TAG_PREVIEW_NEXT`、`TAG_PREVIEW_PREV`、`TAG_PREVIEW_CLOSE`
- 任务 5 依赖 `ImagePreview`。

- [ ] **步骤 1：编写失败的测试**

覆盖（第 2 层断言，须能捕获"数据变了界面不动"）：
1. 打开时显示 `第 N / M 页` 标签（N 从 1 起）；
2. 点"下一页"后**标签文本真的变化**（不能只断言内部 index）；
3. 首页时"上一页"禁用；末页时"下一页"禁用；
4. 关闭回调被触发；
5. **缩放确实改变了渲染**：双击放大后断言缩放比例状态变化（通过 testTag 暴露语义或断言 `graphicsLayer` 缩放后的节点尺寸变化）。

- [ ] **步骤 2：运行测试并确认其失败**

- [ ] **步骤 3：编写实现**

要点：

- 全屏 `Box`，背景 `Color.Black`（预览底）；消费 `safeDrawing` inset。
- 顶部栏：`第 N / M 页` + 关闭按钮（`contentDescription`）；底部或两侧：上一页/下一页。
- 图片区：`ProceduralThumb(seed, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX=scale, scaleY=scale, translationX=offsetX, translationY=offsetY))`，外层 `pointerInput` 用 `detectTransformGestures` 处理缩放与平移；`detectTapGestures(onDoubleTap)` 切换放大/复位。
- **自适应窗口**：图片按可用空间 `fillMaxSize()` + `ContentScale.Fit` 语义（此处为自绘，按容器尺寸绘制即可自适应）。
- 翻页用 `PreviewState.next()/previous()`。

- [ ] **步骤 4：运行测试并确认其通过**

- [ ] **步骤 5：提交**

```bash
git add pixfold-d1/app
git commit -m "feat(d1/ui): 大图预览(自适应/缩放/平移/翻页,验收第 2 项)"
```

---

### 任务 5：导航接线

**文件：**
- 修改：`pixfold-d1/app/src/main/kotlin/com/pixfold/d1/PixFoldApp.kt`
- 测试：`pixfold-d1/app/src/test/kotlin/com/pixfold/d1/AppNavigationTest.kt`

**接口：**
- 依赖输入：任务 2 的 `SortAndPreviewPage`、任务 4 的 `ImagePreview`、P1 的 `HomeScreen`
- 对外产出：首页 → 工作流 A 步骤 1 的可达路径；工作流 A 内可打开/关闭预览。

- [ ] **步骤 1：编写失败的测试**

断言：从首页点"工作流 A"后**进入工作流 A 页面**（该页出现网格，而不只是"回调被调用"）；点缩略图后打开预览；关闭后回到列表。

- [ ] **步骤 2：运行测试并确认其失败**

- [ ] **步骤 3：实现导航**

用**简单的状态驱动导航**（`var screen by remember { mutableStateOf<Screen>(Screen.Home) }` + 密封类），不引入 Navigation 库（零三方依赖；Navigation Compose 属 AndroidX 但为保持最小面暂不引入）。三条工作流 B 入口在本阶段保持占位（P5 实现）。

- [ ] **步骤 4：运行测试并确认其通过**

- [ ] **步骤 5：提交**

```bash
git add pixfold-d1/app
git commit -m "feat(d1/app): 首页到工作流 A 的导航接线(含预览开关)"
```

---

### 任务 6：变异验证与测试补强

**文件：**
- 修改：各测试文件

- [ ] **步骤 1：对每个新增断言做变异验证**

至少验证以下三处能被测试捕获（改坏 → 测试失败 → 改回）：
1. 视图切换失效（toggle 不再改变 mode）；
2. 预览翻页标签不更新（只改数据不改界面）——这是本项目最核心的缺陷类别；
3. 缩放夹取上限被移除。

- [ ] **步骤 2：确认 `-PincludeNegativeControl` 仍如期失败**

- [ ] **步骤 3：提交**

```bash
git add pixfold-d1
git commit -m "test(d1): P2 变异验证(视图切换/翻页渲染/缩放夹取)"
```

---

### 任务 7：真机实证与阶段收口

- [ ] **步骤 1：全套验证**

`:domain:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全绿；lint 零告警。

- [ ] **步骤 2：真机安装并走查（第 3 层，验收第 1、2 项）**

设备：`192.168.43.1:4444`（PJX110 / Android 16）。步骤：
1. `assembleDebug` + `adb install -r`；
2. 启动，截图确认首页；
3. 进入工作流 A，截图**网格**视图；
4. 点切换，截图**列表**视图（确认列数/密度合理，不重现"一屏看不了多少"）；
5. 点缩略图打开预览，截图（确认自适应窗口、页码标签）；
6. `adb shell input swipe` 或点击"下一页"，截图确认翻页；
7. 深色/浅色两种模式各截一张（保证深色模式无白底）；
8. 检查 `logcat -b crash` 为空。

- [ ] **步骤 3：回写文档**

- `HANGOFF.md` §9.1 阶段表 P2 标记完成 + 验证实况；
- 如有新踩坑 → §12.5；
- 验收清单第 1、2 项在 HANGOFF §9 清单中标注状态（**注：清单最终只能在真机逐条勾选，本阶段只勾 1、2**）。

- [ ] **步骤 4：提交并合回 `dev`**

```bash
git checkout dev
git merge --no-ff d1/p2 -m "merge(d1): P2 缩略图双视图 + 大图预览"
git branch -d d1/p2
```

---

## 自检结果

**1. 规格覆盖度**：覆盖规格 §10.1（工作流 A 步骤 1）、§10.3（UI 约定：M3 语义角色、48dp、contentDescription、密度）、§2 约束 13（M3 合规）、§12.2（第 2 层测试）、验收清单第 1、2 项。规格 §6–§9（命名/元数据/计划）属 P4–P6，不在本阶段。规格 §12.3（真机层）由任务 7 承担。

**2. 占位符扫描**：无 TBD/TODO；任务 2 明确"直接使用领域层 `SourceItem`、不另造投影类型"；任务 4/5 的 UI 代码以"要点 + 必须满足的断言"给出（Compose 布局代码冗长，具体实现由执行者按要点落地并保证断言通过）。

**3. 类型一致性**：`ProceduralThumb(seed, modifier, showIndex)`、`seedBaseColor`/`seedAccentColor`/`seedLineCount`/`seedDisplayNumber`、`ViewMode{Grid,List}`、`SortAndPreviewPage(items, modifier, initialMode, onOpenPreview)`、`PreviewState(index,count,scale,offsetX,offsetY)` 及 `previewStateOf/zoomed/panned/next/previous/resetZoom`、`MIN_SCALE/MAX_SCALE`、`ImagePreview(items, initialIndex, onClose, modifier)` 在各任务间签名一致。
