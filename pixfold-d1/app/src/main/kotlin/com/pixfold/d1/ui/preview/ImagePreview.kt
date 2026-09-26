package com.pixfold.d1.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.ui.components.ProceduralThumb

const val TAG_PREVIEW = "preview-root"
const val TAG_PREVIEW_IMAGE = "preview-image"
const val TAG_PREVIEW_PAGE_LABEL = "preview-page-label"
const val TAG_PREVIEW_NEXT = "preview-next"
const val TAG_PREVIEW_PREV = "preview-prev"
const val TAG_PREVIEW_CLOSE = "preview-close"

/**
 * 用于可测的状态描述:把当前缩放写成可断言的语义值(避免只测内部数据)。
 *
 * 这里**只有数字,没有文案** —— `缩放` 二字由 `R.string.preview_zoom_cd` 提供。
 * 这样它仍是纯函数(非 `@Composable`),测试可以照旧在组合之外反向调用它做自洽比较,
 * 不因界面语言改变而失配。
 *
 * `Locale.ROOT` 是刻意的:否则德语等区域会格式化成 `1,00`,同一个小数点在不同设备上
 * 表现不同(该值只作参数,不参与任何相等性/文件名判断)。
 */
fun zoomAmount(scale: Float): String = String.format(java.util.Locale.ROOT, "%.2f", scale)

/** 双击放大的目标倍率。 */
private const val DOUBLE_TAP_SCALE = 2f

/**
 * 大图预览(验收第 2 项):自适应窗口、可缩放、可拖动平移、可翻页。
 *
 * 实现要点:
 *  - 缩放/平移**自研**(`detectTransformGestures` / `detectTapGestures`),不引入三方库;
 *  - 图片占满可用空间(`fillMaxSize`)即"自适应窗口";
 *  - 缩放的当前值通过 `stateDescription` 暴露,使 UI 测试能断言**界面真的变了**;
 *  - 消费系统栏 inset(P1 真机教训)。
 *
 * @param rename 重命名接线;**为 null 即本页不提供改名**(默认),
 *   [ImagePreviewTest] 等单独使用场景无需构造它。
 *   传值后顶部出现「重命名」按钮,写回的是**命名页同一份**覆盖表。
 */
@Composable
fun ImagePreview(
    items: List<SourceItem>,
    initialIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    rename: PreviewRename? = null,
) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.preview_no_images), color = Color.White)
        }
        return
    }

    var state by remember { mutableStateOf(previewStateOf(initialIndex, items.size)) }
    val current = items[state.index.coerceIn(0, items.size - 1)]
    var renameOpen by remember { mutableStateOf(false) }

    // 无障碍/语义文案须在 `semantics {}` 之外取好(`stringResource` 不能在非 composable lambda 里调);
    // 同理,页码在 UI 层解析而不放在 `PreviewState` 上 —— 那个类取不到资源。
    val zoomCd = stringResource(R.string.preview_zoom_cd, zoomAmount(state.scale))
    // 语义里带上**当前图片的文件名**:否则"预览显示的到底是哪一张"在测试里无法断言
    // (`ProceduralThumb` 只把文件名画进 Canvas,不产生任何 semantics)。
    // 这是本页能被证伪的前提,不是装饰。
    val imageCd = stringResource(R.string.preview_image_cd, current.fileName)
    val pageLabel = stringResource(R.string.preview_page_label, state.index + 1, state.count)
    val closeCd = stringResource(R.string.preview_close_cd)
    val prevCd = stringResource(R.string.preview_previous)
    val nextCd = stringResource(R.string.preview_next)

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_PREVIEW)
            .background(Color.Black),
    ) {
        // 图片区:占满可用空间 = 自适应窗口;手势自研。
        // 预览显示文件名(去掉扩展名),便于确认"当前看的是哪一张"。
        ProceduralThumb(
            seed = current.seed,
            fileName = current.fileName,
            showIndex = true,
            captionMaxChars = 28,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_PREVIEW_IMAGE)
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offsetX,
                    translationY = state.offsetY,
                )
                .semantics {
                    stateDescription = zoomCd
                    contentDescription = imageCd
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state = if (zoom != 1f) state.zoomed(zoom) else state
                        if (state.scale > MIN_SCALE && (pan.x != 0f || pan.y != 0f)) {
                            state = state.panned(pan.x, pan.y)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            state = if (state.scale > MIN_SCALE) {
                                state.resetZoom()
                            } else {
                                state.copy(scale = DOUBLE_TAP_SCALE)
                            }
                        },
                    )
                },
        )

        // 顶部:页码 + 重命名 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pageLabel,
                modifier = Modifier.testTag(TAG_PREVIEW_PAGE_LABEL),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 改名入口(用户 2026-09-25 裁决):只在传了 `rename` 时出现。
                // 这一层刻意不自己判断"能不能改名" —— 由调用方传入与否表达,
                // 否则本页会有第二种"是否可改名"的真相。
                if (rename != null) {
                    TextButton(
                        onClick = { renameOpen = true },
                        modifier = Modifier.testTag(TAG_PREVIEW_RENAME),
                    ) {
                        Text(stringResource(R.string.preview_rename), color = Color.White)
                    }
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .testTag(TAG_PREVIEW_CLOSE)
                        .semantics { contentDescription = closeCd },
                ) {
                    Text(stringResource(R.string.common_close), color = Color.White)
                }
            }
        }

        // 改名反馈(仅有覆盖时出现):让"改完了没"这件事在界面上可见。
        // 不加提示的话,保存后画面毫无变化 —— 用户无从确认,UI 层也无从断言。
        if (rename != null && rename.isRenamed(current)) {
            Text(
                text = stringResource(R.string.preview_renamed, rename.finalNameOf(current)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                    .testTag(TAG_PREVIEW_RENAMED),
            )
        }

        // 底部:上一页 / 缩放提示 / 下一页
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.preview_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = { state = state.previous() },
                    enabled = state.hasPrevious,
                    modifier = Modifier.testTag(TAG_PREVIEW_PREV).semantics { contentDescription = prevCd },
                ) { Text(stringResource(R.string.preview_previous)) }
                Button(
                    onClick = { state = state.next() },
                    enabled = state.hasNext,
                    modifier = Modifier.testTag(TAG_PREVIEW_NEXT).semantics { contentDescription = nextCd },
                ) { Text(stringResource(R.string.preview_next)) }
            }
        }

        // 改名对话框:放在 `Box` 内是为了让它盖在本页之上(而不是另开一层)。
        // `key = current.id` 把输入框记忆绑定到"正在改哪一张"。
        if (renameOpen && rename != null) {
            PreviewRenameDialog(
                initialName = rename.finalNameOf(current),
                key = current.id,
                onConfirm = { name ->
                    // 领域层约定:**空串即撤销覆盖**(恢复建议名)。
                    // 这里不做 trim/兜底 —— 那是领域层 `setOverride` 的职责,
                    // 在 UI 再解释一遍就会出现两处不一致的清洗规则。
                    rename.onRename(current, name)
                    renameOpen = false
                },
                onDismiss = { renameOpen = false },
            )
        }
    }
}
