/// 公共组件：程序化占位缩略图、徽章、流程壳（步骤导航）、响应式布局工具。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../domain/models.dart';

// ---------------------------------------------------------------------------
// 程序化占位缩略图（无资源文件，确定性渲染，仅绘制图形不含文字）
// ---------------------------------------------------------------------------

class ProceduralThumb extends StatelessWidget {
  const ProceduralThumb({super.key, required this.seed});

  final int seed;

  Color _color() => HSLColor.fromAHSL(1, (seed % 360).toDouble(), 0.45, 0.55).toColor();

  @override
  Widget build(BuildContext context) {
    final c = _color();
    final c2 = HSLColor.fromColor(c).withLightness(0.35).toColor();
    return CustomPaint(
      painter: _ThumbPainter(base: c, accent: c2, seed: seed),
      child: const SizedBox.expand(),
    );
  }
}

class _ThumbPainter extends CustomPainter {
  _ThumbPainter({required this.base, required this.accent, required this.seed});
  final Color base;
  final Color accent;
  final int seed;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(rect, Paint()..color = base);
    // 右下角三角，模拟"照片"占位样式
    final path = Path()
      ..moveTo(size.width, size.height * 0.35)
      ..lineTo(size.width, size.height)
      ..lineTo(size.width * 0.45, size.height)
      ..close();
    canvas.drawPath(path, Paint()..color = accent.withValues(alpha: 0.8));
    // 确定性线条纹样，便于肉眼区分不同图片
    final line = Paint()
      ..color = Colors.white.withValues(alpha: 0.25)
      ..strokeWidth = 1.5;
    final n = 2 + seed % 3;
    for (var i = 1; i <= n; i++) {
      final dx = (seed >> i) % (size.width * 0.8);
      final dy = (seed >> (i + 2)) % (size.height * 0.8);
      canvas.drawLine(
        Offset(dx.toDouble(), 0),
        Offset(dx.toDouble() + 10, size.height),
        line,
      );
      canvas.drawLine(
        Offset(0, dy.toDouble()),
        Offset(size.width, dy.toDouble() - 8),
        line,
      );
    }
    // 中间大序号
    final tp = TextPainter(
      text: TextSpan(
        text: '${1 + seed % 99}',
        style: TextStyle(
          color: Colors.white.withValues(alpha: 0.9),
          fontSize: size.height * 0.38,
          fontWeight: FontWeight.w900,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, Offset((size.width - tp.width) / 2, (size.height - tp.height) / 2));
  }

  @override
  bool shouldRepaint(covariant _ThumbPainter old) =>
      old.seed != seed || old.base != base || old.accent != accent;
}

// ---------------------------------------------------------------------------
// 缩略图卡片（网格/列表通用）：角标（人工调整 / 固定位置）+ 点击大图
// 说明：所有文字均用普通 Text 渲染（清晰），缩略图本身仅绘制图形。
// ---------------------------------------------------------------------------

class ThumbCard extends StatelessWidget {
  const ThumbCard({
    super.key,
    required this.image,
    required this.index,
    this.manual = false,
    this.pinned = false,
    this.onPin,
    this.onTap,
    this.selected = false,
    this.showLabel = true,
  });

  final MockImage image;
  final int index;
  final bool manual;
  final bool pinned;
  final VoidCallback? onPin;
  final VoidCallback? onTap;
  final bool selected;
  final bool showLabel;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.all(3),
      clipBehavior: Clip.antiAlias,
      color: selected ? Theme.of(context).colorScheme.secondaryContainer : null,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                children: [
                  Positioned.fill(child: ProceduralThumb(seed: image.seed)),
                  Positioned(
                    top: 4,
                    left: 4,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                      decoration: BoxDecoration(
                        // 人工调整过：序号徽标变蓝提示（右上角留给拖拽把手）
                        color: manual ? Colors.lightBlueAccent : Colors.black54,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text('$index',
                          style: const TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                              fontWeight: FontWeight.bold)),
                    ),
                  ),
                  // 图钉：可点击入口（网格模式此前只有角标、没有操作入口）
                  Positioned(
                    bottom: 4,
                    right: 4,
                    child: onPin == null
                        ? (pinned
                            ? Icon(Icons.push_pin,
                                size: 15, color: Colors.amber.shade200)
                            : const SizedBox.shrink())
                        : Tooltip(
                            message: pinned
                                ? '取消固定位置'
                                : '固定位置（重新应用排序时保持不动）',
                            child: InkWell(
                              onTap: onPin,
                              customBorder: const CircleBorder(),
                              child: Container(
                                width: 26,
                                height: 26,
                                decoration: BoxDecoration(
                                  color: pinned
                                      ? Colors.black54
                                      : Colors.black38,
                                  shape: BoxShape.circle,
                                ),
                                child: Icon(
                                  pinned
                                      ? Icons.push_pin
                                      : Icons.push_pin_outlined,
                                  size: 15,
                                  color: pinned
                                      ? Colors.amber.shade200
                                      : Colors.white70,
                                ),
                              ),
                            ),
                          ),
                  ),
                ],
              ),
            ),
            if (showLabel)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                child: Text(
                  image.fileName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 大图预览对话框：自适应窗口、滚轮/手势缩放、拖动平移、按钮/方向键翻页
// ---------------------------------------------------------------------------

class ImageViewerDialog extends StatefulWidget {
  const ImageViewerDialog({
    super.key,
    required this.images,
    required this.initialIndex,
  });

  final List<MockImage> images;
  final int initialIndex;

  @override
  State<ImageViewerDialog> createState() => _ImageViewerDialogState();
}

class _ImageViewerDialogState extends State<ImageViewerDialog> {
  late int index;
  final TransformationController _zoom = TransformationController();

  @override
  void initState() {
    super.initState();
    index = widget.initialIndex;
  }

  @override
  void dispose() {
    _zoom.dispose();
    super.dispose();
  }

  void _go(int delta) {
    final next = (index + delta).clamp(0, widget.images.length - 1);
    if (next != index) {
      setState(() {
        index = next;
        _zoom.value = Matrix4.identity(); // 换页复位缩放
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final img = widget.images[index];
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.arrowLeft): () => _go(-1),
        const SingleActivator(LogicalKeyboardKey.arrowRight): () => _go(1),
      },
      child: Dialog(
        insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: (size.width * 0.86).clamp(320.0, 860.0),
            maxHeight: size.height * 0.88,
          ),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text('第 ${index + 1} / ${widget.images.length} 页',
                          style: Theme.of(context).textTheme.titleMedium),
                    ),
                    IconButton(
                      tooltip: '关闭',
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
                Expanded(
                  child: ClipRect(
                    child: InteractiveViewer(
                      transformationController: _zoom,
                      minScale: 0.5,
                      maxScale: 12,
                      panEnabled: true,
                      scaleEnabled: true,
                      child: Center(
                        child: AspectRatio(
                          aspectRatio: 3 / 4,
                          child: ProceduralThumb(seed: img.seed),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 6),
                Text(img.relPath,
                    style: Theme.of(context).textTheme.bodySmall,
                    overflow: TextOverflow.ellipsis),
                Text('${img.sizeLabel} · 修改 ${img.modified.toString().substring(0, 16)}',
                    style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    IconButton(
                      tooltip: '上一页（←）',
                      onPressed: index > 0 ? () => _go(-1) : null,
                      icon: const Icon(Icons.chevron_left),
                    ),
                    IconButton(
                      tooltip: '复位缩放',
                      onPressed: () => setState(() => _zoom.value = Matrix4.identity()),
                      icon: const Icon(Icons.zoom_out_map),
                    ),
                    IconButton(
                      tooltip: '下一页（→）',
                      onPressed: index < widget.images.length - 1 ? () => _go(1) : null,
                      icon: const Icon(Icons.chevron_right),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 徽章 / 标签
// ---------------------------------------------------------------------------

class TagChip extends StatelessWidget {
  const TagChip(this.text, {super.key, this.color, this.icon});
  final String text;
  final Color? color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final c = color ?? Theme.of(context).colorScheme.surfaceContainerHighest;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: c.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 10, color: color),
            const SizedBox(width: 2),
          ],
          Text(text, style: TextStyle(fontSize: 12, color: color)),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 流程壳：顶部步骤导航 + 内容区
// ---------------------------------------------------------------------------

class FlowShell extends StatelessWidget {
  const FlowShell({
    super.key,
    required this.title,
    required this.steps,
    required this.current,
    required this.onStep,
    required this.child,
    this.trailing,
  });

  final String title;
  final List<String> steps;
  final int current;
  final ValueChanged<int> onStep;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: [?trailing],
      ),
      body: Column(
        children: [
          SizedBox(
            height: 52,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
              children: [
                for (var i = 0; i < steps.length; i++)
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: ChoiceChip(
                      label: Text(i <= current
                          ? '${i + 1}. ${steps[i]}'
                          : steps[i]),
                      selected: i == current,
                      onSelected: i == current ? null : (_) => onStep(i),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(child: child),
        ],
      ),
      bottomNavigationBar: _buildNav(context),
    );
  }

  Widget _buildNav(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
        child: Row(
          children: [
            if (current > 0)
              Expanded(
                child: OutlinedButton(
                  onPressed: () => onStep(current - 1),
                  child: const Text('上一步'),
                ),
              ),
            const SizedBox(width: 8),
            Expanded(
              child: FilledButton(
                onPressed: current < steps.length - 1 ? () => onStep(current + 1) : null,
                child: Text(current < steps.length - 1 ? '下一步' : '完成'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 宽屏时右下角浮动"上一步/下一步"。
class WideStepNav extends StatelessWidget {
  const WideStepNav({
    super.key,
    required this.current,
    required this.total,
    required this.onPrev,
    required this.onNext,
    this.nextLabel = '下一步',
    this.nextEnabled = true,
  });

  final int current;
  final int total;
  final VoidCallback? onPrev;
  final VoidCallback? onNext;
  final String nextLabel;
  final bool nextEnabled;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          OutlinedButton(
            onPressed: current > 0 ? onPrev : null,
            child: const Text('上一步'),
          ),
          FilledButton(
            onPressed: (current < total - 1 && nextEnabled) ? onNext : null,
            child: Text(nextLabel),
          ),
        ],
      ),
    );
  }
}

/// 宽屏双栏 / 窄屏上下堆叠。
class ResponsiveTwoPane extends StatelessWidget {
  const ResponsiveTwoPane({
    super.key,
    required this.side,
    required this.main,
    this.sideWidth = 320,
  });

  final Widget side;
  final Widget main;
  final double sideWidth;

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.of(context).size.width >= 900;
    if (wide) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: sideWidth, child: side),
          const VerticalDivider(width: 1),
          Expanded(child: main),
        ],
      );
    }
    return ListView(
      children: [
        side,
        const Divider(height: 1),
        main,
      ],
    );
  }
}

void showInfoDialog(BuildContext context, String title, String body) {
  showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(body),
      actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了'))],
    ),
  );
}
