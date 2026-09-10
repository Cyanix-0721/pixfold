// D1 原型冒烟测试：入口可渲染两条工作流入口卡片；领域层核心行为回归。
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:pixfold_d1/domain/comic.dart';
import 'package:pixfold_d1/domain/models.dart';
import 'package:pixfold_d1/domain/naming.dart';
import 'package:pixfold_d1/main.dart';
import 'package:pixfold_d1/mock/mock_data.dart';
import 'package:pixfold_d1/widgets/common.dart';
import 'package:pixfold_d1/widgets/reorderable.dart';
import 'package:pixfold_d1/workflow_a/flow.dart';

/// 构造一个用 DragReorderItem 包裹的网格。
Widget _gridHarness(PageOrder order, {int count = 6}) {
  return MaterialApp(
    home: Scaffold(
      body: SizedBox(
        width: 600,
        height: 400,
        child: GridView.builder(
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 3,
            childAspectRatio: 1,
          ),
          itemCount: count,
          itemBuilder: (context, i) {
            final id = order.order[i].id;
            return DragReorderItem(
              itemId: id,
              index: i,
              onMove: (itemId, target) => order.moveItemTo(itemId, target),
              child: ColoredBox(
                key: ValueKey('cell-$id'),
                color: Colors.blue,
              ),
            );
          },
        ),
      ),
    ),
  );
}

/// **真机同款**结构：真实 ThumbCard（含 InkWell/Card）+ LayoutBuilder +
/// 可滚动网格（内容超视口）+ 可选经 Navigator.push 的第二层路由。
Widget _realGridHarness(
  PageOrder order, {
  int count = 6,
  bool pushRoute = false,
  bool useThumbCard = true,
  bool useLayoutBuilder = true,
}) {
  Widget grid = GridView.builder(
    padding: const EdgeInsets.all(8),
    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
      crossAxisCount: 3,
      mainAxisSpacing: 4,
      crossAxisSpacing: 4,
      childAspectRatio: 0.78,
    ),
    itemCount: count,
    itemBuilder: (context, i) {
      final img = order.order[i];
      Widget item = DragReorderItem(
        itemId: img.id,
        index: i,
        onMove: (id, t) => order.moveItemTo(id, t),
        child: useThumbCard
            ? ThumbCard(
                key: ValueKey('cell-${img.id}'),
                image: img,
                index: i + 1,
                onTap: () {},
              )
            : ColoredBox(
                key: ValueKey('cell-${img.id}'),
                color: Colors.blue,
              ),
      );
      if (useLayoutBuilder) {
        item = LayoutBuilder(
          builder: (ctx, cons) => DragReorderItem(
            itemId: img.id,
            index: i,
            feedbackSize: Size(cons.maxWidth - 6, cons.maxHeight - 6),
            onMove: (id, t) => order.moveItemTo(id, t),
            child: useThumbCard
                ? ThumbCard(
                    key: ValueKey('cell-${img.id}'),
                    image: img,
                    index: i + 1,
                    onTap: () {},
                  )
                : ColoredBox(
                    key: ValueKey('cell-${img.id}'),
                    color: Colors.blue,
                  ),
          ),
        );
      }
      return item;
    },
  );

  return MaterialApp(
    theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
    home: pushRoute
        ? Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => Scaffold(
                        appBar: AppBar(title: const Text('flow')),
                        body: grid,
                      ),
                    ),
                  ),
                  child: const Text('go'),
                ),
              ),
            ),
          )
        : Scaffold(body: grid),
  );
}

/// 模拟一次完整拖拽：按下 → 移动超 slop 启动 → 移到目标 → 松手。
/// [kind] 用于区分鼠标（真机桌面）与触摸（真机移动端）。
Future<void> _drag(
  WidgetTester tester,
  String fromId,
  String toId, {
  bool longPress = false,
  PointerDeviceKind kind = PointerDeviceKind.touch,
}) async {
  final gesture = await tester.startGesture(
    tester.getCenter(find.byKey(ValueKey('cell-$fromId'))),
    kind: kind,
  );
  await tester.pump(
      longPress ? kLongPressTimeout + const Duration(milliseconds: 50)
                : const Duration(milliseconds: 60));
  await gesture.moveBy(const Offset(30, 30));
  await tester.pump();
  await gesture.moveTo(tester.getCenter(find.byKey(ValueKey('cell-$toId'))));
  await tester.pump();
  await gesture.up();
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('首页渲染两条工作流入口', (WidgetTester tester) async {
    await tester.pumpWidget(const PixFoldD1App());
    expect(find.text('工作流 A · 图片整理与命名'), findsOneWidget);
    expect(find.text('工作流 B · CBZ 制作'), findsOneWidget);
  });

  // ---------------------------------------------------------------------
  // 真机同款结构复现（2026-09-10：简化 harness 曾漏掉真机问题）
  // ---------------------------------------------------------------------
  testWidgets('真机同款：ThumbCard + LayoutBuilder + 可滚动网格', (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    final order = PageOrder(buildWorkspaceA().collections.first.images);
    final ids = order.order.map((m) => m.id).toList();

    await tester.pumpWidget(_realGridHarness(order));
    await _drag(tester, ids[0], ids[2]);

    expect(order.order[2].id, ids[0], reason: '真机结构下拖拽也必须改变顺序');
    debugDefaultTargetPlatformOverride = null;
  });

  testWidgets('真机同款：经 Navigator.push 的第二层路由', (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    final order = PageOrder(buildWorkspaceA().collections.first.images);
    final ids = order.order.map((m) => m.id).toList();

    await tester.pumpWidget(_realGridHarness(order, pushRoute: true));
    await tester.tap(find.text('go'));
    await tester.pumpAndSettle();

    await _drag(tester, ids[0], ids[2]);

    expect(order.order[2].id, ids[0], reason: '第二层路由里拖拽也必须改变顺序');
    debugDefaultTargetPlatformOverride = null;
  });

  testWidgets('真机同款：鼠标拖动（真机桌面实际输入设备）', (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    final order = PageOrder(buildWorkspaceA().collections.first.images);
    final ids = order.order.map((m) => m.id).toList();

    await tester.pumpWidget(_realGridHarness(order));
    await _drag(tester, ids[0], ids[2], kind: PointerDeviceKind.mouse);

    expect(order.order[2].id, ids[0], reason: '鼠标拖动也必须改变顺序');
    debugDefaultTargetPlatformOverride = null;
  });

  // ---------------------------------------------------------------------
  // 拖拽回归：必须"真的改变顺序"（此前三方包与首版自研都栽在这里）
  // 语义锁定：拖动过程中不重排，**松手落地**才改变顺序（避免实时重排抖动）
  // ---------------------------------------------------------------------
  testWidgets('桌面端：网格拖拽改变顺序（松手落地）', (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;

    final ws = buildWorkspaceA();
    final order = PageOrder(ws.collections.first.images);
    final ids = order.order.map((m) => m.id).toList();

    await tester.pumpWidget(_gridHarness(order));

    final targetCenter = tester.getCenter(find.byKey(ValueKey('cell-${ids[2]}')));
    final gesture = await tester.startGesture(
        tester.getCenter(find.byKey(ValueKey('cell-${ids[0]}'))));
    await tester.pump(const Duration(milliseconds: 60));
    // 移动超过 slop 启动拖拽，再移动到第 3 格
    await gesture.moveBy(const Offset(24, 24));
    await tester.pump();
    await gesture.moveTo(targetCenter);
    await tester.pump();

    // 拖动中：顺序不动
    expect(order.order[0].id, ids[0], reason: '拖动过程中不应实时重排（防抖动）');

    await gesture.up();
    await tester.pumpAndSettle();

    // 松手后：落地到第 3 格
    expect(order.order[2].id, ids[0],
        reason: '把第 1 张拖到第 3 格后，它应落在 index 2');
    expect(order.isManual(ids[0]), isTrue, reason: '应记录人工调整标记');
    debugDefaultTargetPlatformOverride = null;
  });

  testWidgets('移动端：长按后网格拖拽改变顺序', (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;

    final ws = buildWorkspaceA();
    final order = PageOrder(ws.collections.first.images);
    final ids = order.order.map((m) => m.id).toList();

    await tester.pumpWidget(_gridHarness(order));

    final targetCenter = tester.getCenter(find.byKey(ValueKey('cell-${ids[1]}')));
    final gesture = await tester.startGesture(
        tester.getCenter(find.byKey(ValueKey('cell-${ids[0]}'))));
    await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
    await gesture.moveTo(targetCenter);
    await tester.pump();
    await gesture.up();
    await tester.pumpAndSettle();

    expect(order.order[1].id, ids[0], reason: '长按拖到第 2 格应落在 index 1');
    debugDefaultTargetPlatformOverride = null;
  });

  testWidgets('列表项在无界高度下正常布局（回归：StackFit 会压成 0 高度）',
      (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;

    final ws = buildWorkspaceA();
    final order = PageOrder(ws.collections.first.images);

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ListView.builder(
          itemCount: 5,
          itemBuilder: (context, i) => DragReorderItem(
            itemId: order.order[i].id,
            index: i,
            onMove: (id, t) => order.moveItemTo(id, t),
            child: SizedBox(
              key: ValueKey('row-$i'),
              height: 60,
              child: const Text('row'),
            ),
          ),
        ),
      ),
    ));

    expect(find.byKey(const ValueKey('row-0')), findsOneWidget);
    expect(tester.getSize(find.byKey(const ValueKey('row-0'))).height, 60,
        reason: '列表行必须保留内在高度，不能被压成 0');
    debugDefaultTargetPlatformOverride = null;
  });

  // ---------------------------------------------------------------------
  // UI 回归：拖拽后界面必须真的重绘（2026-09-10 根因：PageOrder 的通知
  // 没有转发给 page controller，数据变了但 AnimatedBuilder 收不到 → 界面不动）
  // ---------------------------------------------------------------------
  testWidgets('UI 回归：网格拖拽后首格显示内容必须更新', (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(MaterialApp(
      home: WorkflowAScreen(workspace: buildWorkspaceA()),
    ));
    await tester.pumpAndSettle();

    String firstCellText() {
      final texts = tester.widgetList<Text>(
        find.descendant(
            of: find.byType(ThumbCard).first, matching: find.byType(Text)),
      );
      return texts.map((t) => t.data ?? '').join('|');
    }

    final before = firstCellText();
    expect(before, isNotEmpty, reason: '首格应渲染出文件名/序号文本');

    final t0 = tester.getCenter(find.byType(ThumbCard).at(0));
    final t2 = tester.getCenter(find.byType(ThumbCard).at(2));
    final gesture =
        await tester.startGesture(t0, kind: PointerDeviceKind.mouse);
    await tester.pump(const Duration(milliseconds: 60));
    await gesture.moveBy(const Offset(30, 30));
    await tester.pump();
    await gesture.moveTo(t2);
    await tester.pump();
    await gesture.up();
    await tester.pumpAndSettle();

    expect(firstCellText(), isNot(before),
        reason: '拖拽后首格显示内容必须变化 —— 数据变化必须反映到界面');
    debugDefaultTargetPlatformOverride = null;
  });

  test('togglePin：固定项在重新应用排序后保持原位', () {
    final ws = buildWorkspaceA();
    final order = PageOrder(ws.collections.first.images);
    final ids = order.order.map((m) => m.id).toList();

    // 手工把最后一张拖到第 1 位，再固定它
    final lastId = ids.last;
    order.moveItemTo(lastId, 0);
    expect(order.order[0].id, lastId);
    expect(order.isManual(lastId), isTrue);
    order.togglePin(lastId);
    expect(order.isPinned(lastId), isTrue);
    expect(order.pinnedCount, 1);

    // 换成"默认升序"规则重新排序：按规则 lastId 应排到最后，但它被固定 → 仍在 index 0
    order.applyRule(PageOrder.defaultRule());
    expect(order.order[0].id, lastId, reason: '固定项在重新应用排序后保持原位');
    expect(order.hasManual, isFalse, reason: '应用自动规则后人工标记应清空');
    expect(order.isPinned(lastId), isTrue, reason: '固定标记本身保留');

    // 解除固定后再应用规则：它应回到规则决定的位置（升序 → 末尾）
    order.togglePin(lastId);
    expect(order.isPinned(lastId), isFalse);
    order.applyRule(PageOrder.defaultRule());
    expect(order.order.last.id, lastId, reason: '解除固定后回到规则决定的位置');
    expect(order.order[0].id, isNot(lastId), reason: '解除固定后不再保持原位');
  });

  testWidgets('UI 回归：网格可点图钉固定位置（此前只有角标无入口）',
      (WidgetTester tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(MaterialApp(
      home: WorkflowAScreen(workspace: buildWorkspaceA()),
    ));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.push_pin_outlined), findsWidgets,
        reason: '每张卡片应有可点击的空心图钉');
    expect(find.byIcon(Icons.push_pin), findsNothing, reason: '初始无固定项');

    await tester.tap(find.byIcon(Icons.push_pin_outlined).first);
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.push_pin), findsWidgets,
        reason: '点击后应变为实心图钉（固定生效）');
    debugDefaultTargetPlatformOverride = null;
  });

  test('自然排序：img2 < img10', () {
    expect(naturalCompare('img2', 'img10'), lessThan(0));
    expect(naturalCompare('img10', 'img1'), greaterThan(0));
  });

  test('命名结构：根目录名 + 图片序号 + 补零 + 小写扩展名', () {
    final ws = buildWorkspaceA();
    final col = ws.collections.first;
    final order = PageOrder(col.images);
    final scheme = NamingScheme(rootDirName: '旅行照片')
      ..indexStart = 1
      ..indexPadding = 3
      ..extPolicy = ExtPolicy.lower;
    final proposals =
        buildProposals(order: order.order, scheme: scheme);
    expect(proposals.length, col.images.length);
    expect(proposals.first.finalName, startsWith('旅行照片_001.'));
    expect(proposals.first.finalName.endsWith('.jpg'), isTrue);
  });

  test('moveItemTo：拖拽重排语义（remove + insert + 人工标记）', () {
    final ws = buildWorkspaceA();
    final col = ws.collections.first; // 旅行照片
    final order = PageOrder(col.images);
    final ids = order.order.map((m) => m.id).toList();
    expect(order.hasManual, isFalse);

    // 第 1 个拖到 index 5：其余顺移，被拖项落到第 5 格
    order.moveItemTo(ids[0], 5);
    final after = order.order.map((m) => m.id).toList();
    final expected = [...ids]..removeAt(0)..insert(5, ids[0]);
    expect(after, expected);
    expect(order.isManual(ids[0]), isTrue);

    // 拖到最后
    order.moveItemTo(ids[0], order.order.length - 1);
    expect(order.order.last.id, ids[0]);
    // 拖到自己格：不变
    final before = order.order.map((m) => m.id).toList();
    final ownIdx = order.order.indexWhere((m) => m.id == ids[1]);
    order.moveItemTo(ids[1], ownIdx);
    expect(order.order.map((m) => m.id).toList(), before);
  });

  test('重名冲突被检出', () {
    final ws = buildWorkspaceA();
    final scan = ws.collections.firstWhere((c) => c.id == 'scan');
    final order = PageOrder(scan.images);
    // 只保留原文件名组件 → ch01/ch02 同名 page_XX 互相冲突
    final scheme = NamingScheme(rootDirName: '扫描件');
    scheme.components
      ..clear()
      ..add(NameComponent(kind: NameComponentKind.origName, separatorBefore: ''));
    final proposals =
        buildProposals(order: order.order, scheme: scheme);
    final dups =
        proposals.where((p) => p.warnings.any((w) => w.type == ProposalWarningType.duplicate));
    expect(dups, isNotEmpty);
  });

  test('ComicInfo.xml：语言未写入与 zh 写入', () {
    final lib = buildLibraryB();
    final v = lib.volumes.first;
    v.language = LangChoice.skip;
    expect(buildComicInfoXml(v).contains('<LanguageISO />'), isTrue);
    v.language = LangChoice.zh;
    expect(buildComicInfoXml(v).contains('<LanguageISO>zh</LanguageISO>'), isTrue);
  });

  test('页码重编号与打包计划', () {
    final lib = buildLibraryB();
    final v = lib.volumes.firstWhere((x) => x.id == 'aot-2');
    final renumbered = renumberPages(v);
    expect(renumbered.length, v.pages.length);
    expect(renumbered.first.value, '001.jpg');
    final plans = buildPackagePlans(lib.volumes);
    expect(plans.length, lib.volumes.length);
    // op-102 输出冲突 → rename 策略下文件名带 (1)
    final conflictPlan = plans.firstWhere((p) => p.volume.id == 'op-102');
    expect(conflictPlan.outputDisplay.contains('(1)'), isTrue);
  });
}
