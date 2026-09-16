package com.pixfold.d1.ui.preview

/** 预览缩放下限(1 = 适应窗口原状)。 */
const val MIN_SCALE = 1f

/** 预览缩放上限。 */
const val MAX_SCALE = 5f

/**
 * 大图预览状态(不可变)。缩放平移自研,不引入三方手势/缩放库。
 *
 * 语义要点:
 *  - 缩放夹取到 [MIN_SCALE]..[MAX_SCALE];
 *  - 缩回最小比例时**平移归零**(否则图片会停在偏移位置,看起来"跑偏");
 *  - 翻页**复位缩放与平移**(换图不应带着上一张的放大状态);
 *  - 翻页到边界**停住不环绕**。
 */
data class PreviewState(
    val index: Int,
    val count: Int,
    val scale: Float = MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < count - 1

    /** 1-based 页码标签,如 `第 1 / 7 页`。 */
    val pageLabel: String get() = "第 ${index + 1} / $count 页"
}

fun previewStateOf(index: Int, count: Int): PreviewState =
    PreviewState(index = index.coerceIn(0, (count - 1).coerceAtLeast(0)), count = count)

/** 按倍率缩放并夹取;缩到最小比例时复位平移。 */
fun PreviewState.zoomed(factor: Float): PreviewState {
    val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
    return if (next <= MIN_SCALE) {
        copy(scale = MIN_SCALE, offsetX = 0f, offsetY = 0f)
    } else {
        copy(scale = next)
    }
}

fun PreviewState.panned(dx: Float, dy: Float): PreviewState =
    copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

private fun PreviewState.resetView(): PreviewState = copy(scale = MIN_SCALE, offsetX = 0f, offsetY = 0f)

fun PreviewState.next(): PreviewState =
    if (hasNext) copy(index = index + 1).resetView() else this

fun PreviewState.previous(): PreviewState =
    if (hasPrevious) copy(index = index - 1).resetView() else this

fun PreviewState.resetZoom(): PreviewState = resetView()
