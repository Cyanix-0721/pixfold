/// 自研拖拽单元（网格/列表通用，零三方依赖）。
///
/// - **整项可拖**：桌面端用立即拖拽（`Draggable` 内部是
///   ImmediateMultiDragGestureRecognizer，只有指针移动超过 slop 才接受手势，
///   静止点按不会启动拖拽，因此点击看图/按钮事件不受影响）；
///   移动端用长按拖拽，避免与滚动冲突。
/// - 每项是 DragTarget：拖动中只高亮目标格，**松手（onAccept）才真正重排**。
///   刻意不做"悬停实时重排"——拖拽期间指针微动会反复触发重排，导致顺序
///   来回换位、看起来"拖了没变"（2026-09-10 实测教训）。
/// - 约束透传（StackFit.passthrough）：兼容网格的固定尺寸与列表的内在高度。
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

typedef ReorderHandler = void Function(String itemId, int targetIndex);

bool _isTouchPlatform() =>
    defaultTargetPlatform == TargetPlatform.android ||
    defaultTargetPlatform == TargetPlatform.iOS;

/// 每个可拖拽项。外层把 [itemId]/[index] 映射到数据，[onMove] 里执行
/// `PageOrder.moveItemTo(id, targetIndex)` 即完成重排。
class DragReorderItem extends StatelessWidget {
  const DragReorderItem({
    super.key,
    required this.itemId,
    required this.index,
    required this.child,
    required this.onMove,
    this.feedbackSize,
    this.enabled = true,
    this.draggingDim = 0.35,
  });

  final String itemId;
  final int index;
  final Widget child;
  final ReorderHandler onMove;

  /// 拖拽浮层尺寸（建议与原格一致）；null 用默认 150×190。
  final Size? feedbackSize;
  final bool enabled;
  final double draggingDim;

  void _drop(String data) {
    if (!enabled) return;
    if (data == itemId) return; // 拖到自己格上：不动
    onMove(data, index);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final feedback = Material(
      elevation: 10,
      color: theme.colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(10),
      clipBehavior: Clip.antiAlias,
      child: SizedBox(
        width: feedbackSize?.width ?? 150,
        height: feedbackSize?.height ?? 190,
        child: child,
      ),
    );

    final Widget draggable;
    if (!enabled) {
      draggable = child;
    } else if (_isTouchPlatform()) {
      draggable = LongPressDraggable<String>(
        data: itemId,
        feedback: feedback,
        childWhenDragging: Opacity(opacity: draggingDim, child: child),
        maxSimultaneousDrags: 1,
        child: child,
      );
    } else {
      draggable = Draggable<String>(
        data: itemId,
        feedback: feedback,
        childWhenDragging: Opacity(opacity: draggingDim, child: child),
        maxSimultaneousDrags: 1,
        child: child,
      );
    }

    return DragTarget<String>(
      onWillAcceptWithDetails: (_) => enabled,
      onAcceptWithDetails: (d) => _drop(d.data),
      builder: (context, candidates, _) {
        final over = enabled && candidates.isNotEmpty && candidates.first != itemId;
        return Stack(
          // 约束透传：网格(tight)与列表(内在高度)都能正确布局
          fit: StackFit.passthrough,
          children: [
            draggable,
            if (over)
              Positioned.fill(
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primary.withValues(alpha: 0.14),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: theme.colorScheme.primary,
                        width: 2.5,
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
