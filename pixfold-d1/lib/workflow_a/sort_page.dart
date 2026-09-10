/// 工作流 A · 步骤 1：目录分组确认 + 排序规则 + 缩略图网格/列表 + 拖拽 + 大图预览。
library;

import 'package:flutter/material.dart';

import '../domain/models.dart';
import '../widgets/common.dart';
import '../widgets/reorderable.dart';
import 'flow.dart';

class SortPage extends StatefulWidget {
  const SortPage({super.key, required this.controller});

  final WorkflowAController controller;

  @override
  State<SortPage> createState() => _SortPageState();
}

class _SortPageState extends State<SortPage> {
  bool gridView = true;
  final Map<String, SortRule> _draftRules = {}; // 编辑中的规则草稿

  SortRule _draft(String collectionId, SortRule effective) =>
      _draftRules[collectionId] ?? effective.copy();

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final collection = c.currentCollection;
    final order = c.currentOrder;
    final draft = _draft(collection.id, c.effectiveRule(collection.id));

    return ResponsiveTwoPane(
      side: _buildSidePanel(context, c, collection, draft),
      main: _buildMain(context, c, collection, order),
    );
  }

  // -------------------------------------------------------------------------
  // 侧栏：集合选择 + 排序规则编辑
  // -------------------------------------------------------------------------

  Widget _buildSidePanel(
    BuildContext context,
    WorkflowAController c,
    ImageCollection collection,
    SortRule draft,
  ) {
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Text('目录分组', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        ...c.workspace.collections.map((col) {
          final manual = c.orders[col.id]!.manualCount;
          return ListTile(
            dense: true,
            selected: col.id == collection.id,
            title: Text(col.name),
            subtitle: Text('${col.images.length} 张'),
            trailing: manual > 0
                ? TagChip('人工 $manual', color: Colors.lightBlue)
                : null,
            onTap: () => c.selectCollection(col.id),
          );
        }),
        const Divider(),
        Row(
          children: [
            Expanded(
              child: Text('排序规则', style: Theme.of(context).textTheme.titleSmall),
            ),
            Tooltip(
              message: '本组使用独立规则（其余组仍用批次默认规则）',
              child: FilterChip(
                label: const Text('本组独立'),
                selected: c.customRuleCollections.contains(collection.id),
                onSelected: (_) => c.toggleCustomRule(collection.id),
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        if (!c.editingBatchRule && !c.customRuleCollections.contains(collection.id))
          const Text('当前编辑：批次默认规则（应用到所有未独立的组）',
              style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic)),
        if (c.customRuleCollections.contains(collection.id))
          const Text('当前编辑：本组独立规则',
              style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic)),
        const SizedBox(height: 4),
        ...[
          for (var i = 0; i < draft.keys.length; i++)
            _ruleRow(c, collection, draft, i),
        ],
        Align(
          alignment: Alignment.centerLeft,
          child: IconButton(
            tooltip: '添加排序级（多级排序）',
            onPressed: draft.keys.length >= 4
                ? null
                : () {
                    setState(() {
                      draft.keys.add(SortKey(SortField.naturalName, true));
                      _draftRules[collection.id] = draft;
                    });
                  },
            icon: const Icon(Icons.add),
          ),
        ),
        const SizedBox(height: 4),
        FilledButton.icon(
          icon: const Icon(Icons.sort),
          label: const Text('应用排序'),
          onPressed: () {
            _draftRules.remove(collection.id);
            c.applyRule(collection.id, draft);
          },
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          icon: const Icon(Icons.restart_alt),
          label: Text('重置人工调整（${order0Manual(c)} 处）'),
          onPressed: c.currentOrder.hasManual ? c.currentOrder.resetToAuto : null,
        ),
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('两层状态', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 4),
                Text(
                  '· 自动排序：按当前规则计算\n'
                  '· 人工调整：拖拽过的图片标蓝点，重置不影响自动层\n'
                  '· 固定位置：图钉标记的图片在重新应用规则时保持原位\n'
                  '· 当前：人工 ${c.currentOrder.manualCount} 处 / 固定 ${c.currentOrder.pinnedCount} 张',
                  style: const TextStyle(fontSize: 12),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  int order0Manual(WorkflowAController c) => c.currentOrder.manualCount;

  Widget _ruleRow(
    WorkflowAController c,
    ImageCollection collection,
    SortRule draft,
    int i,
  ) {
    final key = draft.keys[i];
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        children: [
          if (i > 0)
            const Padding(
              padding: EdgeInsets.only(right: 4),
              child: Text('再按', style: TextStyle(fontSize: 12)),
            ),
          Expanded(
            child: DropdownButtonFormField<SortField>(
              initialValue: key.field,
              isDense: true,
              decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true),
              items: [
                for (final f in SortField.values)
                  DropdownMenuItem(value: f, child: Text(f.label, style: const TextStyle(fontSize: 13))),
              ],
              onChanged: (v) => setState(() {
                key.field = v!;
                _draftRules[collection.id] = draft;
              }),
            ),
          ),
          IconButton(
            visualDensity: VisualDensity.compact,
            icon: Icon(key.ascending ? Icons.arrow_upward : Icons.arrow_downward),
            tooltip: key.ascending ? '升序' : '降序',
            onPressed: () => setState(() {
              key.ascending = !key.ascending;
              _draftRules[collection.id] = draft;
            }),
          ),
          IconButton(
            visualDensity: VisualDensity.compact,
            icon: const Icon(Icons.delete_outline),
            tooltip: '删除此排序级',
            onPressed: draft.keys.length <= 1
                ? null
                : () => setState(() {
                      draft.keys.removeAt(i);
                      _draftRules[collection.id] = draft;
                    }),
          ),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------------
  // 主区：缩略图网格 / 列表
  // -------------------------------------------------------------------------

  Widget _buildMain(
    BuildContext context,
    WorkflowAController c,
    ImageCollection collection,
    PageOrder order,
  ) {
    final images = order.order;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '${collection.name} · ${images.length} 张 · 拖拽调整顺序，点击看大图，长按/右键更多操作',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: true, icon: Icon(Icons.grid_view), label: Text('网格')),
                  ButtonSegment(value: false, icon: Icon(Icons.view_list), label: Text('列表')),
                ],
                selected: {gridView},
                onSelectionChanged: (s) => setState(() => gridView = s.first),
              ),
            ],
          ),
        ),
        Expanded(
          child: gridView
              ? _grid(c, order, images)
              : _list(c, order, images),
        ),
      ],
    );
  }

  Widget _grid(WorkflowAController c, PageOrder order, List<MockImage> images) {
    final wide = MediaQuery.of(context).size.width >= 1200;
    final cross = wide ? 6 : 4;
    return GridView.builder(
      padding: const EdgeInsets.all(8),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: cross,
        mainAxisSpacing: 4,
        crossAxisSpacing: 4,
        childAspectRatio: 0.78,
      ),
      itemCount: images.length,
      itemBuilder: (context, i) {
        final img = images[i];
        return LayoutBuilder(
          builder: (ctx, cons) => DragReorderItem(
            itemId: img.id,
            index: i,
            feedbackSize: Size(cons.maxWidth - 6, cons.maxHeight - 6),
            onMove: (id, t) => order.moveItemTo(id, t),
            child: _gridItem(c, order, img, i),
          ),
        );
      },
    );
  }

  Widget _gridItem(WorkflowAController c, PageOrder order, MockImage img, int i) {
    return ThumbCard(
      key: ValueKey(img.id),
      image: img,
      index: i + 1,
      manual: order.isManual(img.id),
      pinned: order.isPinned(img.id),
      onPin: () => c.currentOrder.togglePin(img.id),
      onTap: () => _showViewer(c, i),
    );
  }

  Widget _list(WorkflowAController c, PageOrder order, List<MockImage> images) {
    return ListView.builder(
      padding: const EdgeInsets.all(8),
      itemCount: images.length,
      itemBuilder: (context, i) {
        final img = images[i];
        return Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: LayoutBuilder(
            builder: (ctx, cons) => DragReorderItem(
              itemId: img.id,
              index: i,
              feedbackSize: Size(cons.maxWidth, 60),
              onMove: (id, t) => order.moveItemTo(id, t),
              child: _listRow(c, order, img, i),
            ),
          ),
        );
      },
    );
  }

  Widget _listRow(WorkflowAController c, PageOrder order, MockImage img, int i) {
    final theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.surfaceContainerLow,
      borderRadius: BorderRadius.circular(8),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _showViewer(c, i),
        child: SizedBox(
          height: 60,
          child: Row(
            children: [
              const SizedBox(width: 10),
              SizedBox(
                width: 44,
                height: 44,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: ProceduralThumb(seed: img.seed),
                ),
              ),
              const SizedBox(width: 10),
              SizedBox(
                width: 34,
                child: Text('${i + 1}',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
              ),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(img.relPath,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 13)),
                    Text(
                      '${img.sizeLabel} · 修改 ${img.modified.toString().substring(0, 16)}',
                      style: const TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                  ],
                ),
              ),
              if (order.isManual(img.id))
                const Icon(Icons.touch_app, size: 14, color: Colors.lightBlueAccent),
              IconButton(
                visualDensity: VisualDensity.compact,
                icon: Icon(
                  order.isPinned(img.id) ? Icons.push_pin : Icons.push_pin_outlined,
                  size: 16,
                  color: order.isPinned(img.id) ? Colors.amber : null,
                ),
                tooltip: '固定当前位置',
                onPressed: () => c.currentOrder.togglePin(img.id),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showViewer(WorkflowAController c, int index) {
    showDialog<void>(
      context: context,
      builder: (_) => ImageViewerDialog(images: c.currentOrder.order, initialIndex: index),
    );
  }
}
