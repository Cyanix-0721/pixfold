/// 工作流 A · 步骤 2：命名结构编辑器（组件编排）+ 建议名称表（原路径 → 新名称，可逐项覆盖）。
library;

import 'package:flutter/material.dart';

import '../domain/models.dart';
import '../widgets/common.dart';
import 'flow.dart';

class NamingPage extends StatefulWidget {
  const NamingPage({super.key, required this.controller});

  final WorkflowAController controller;

  @override
  State<NamingPage> createState() => _NamingPageState();
}

class _NamingPageState extends State<NamingPage> {
  _ProposalFilter filter = _ProposalFilter.all;
  final Map<String, TextEditingController> _editCtrls = {};

  @override
  void dispose() {
    for (final c in _editCtrls.values) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return ResponsiveTwoPane(
      sideWidth: 360,
      side: _buildSchemeEditor(context, c),
      main: _buildPreviewTable(context, c),
    );
  }

  // -------------------------------------------------------------------------
  // 命名结构编辑器
  // -------------------------------------------------------------------------

  Widget _buildSchemeEditor(BuildContext context, WorkflowAController c) {
    final s = c.scheme;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Text('命名组件（拖动调整顺序）', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        ReorderableListView(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          onReorderItem: (old, neu) => c.updateScheme((s) {
            final comp = s.components.removeAt(old);
            s.components.insert(neu.clamp(0, s.components.length), comp);
          }),
          children: [
            for (var i = 0; i < s.components.length; i++)
              _componentCard(context, c, s, i),
          ],
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: PopupMenuButton<NameComponentKind>(
            tooltip: '添加组件',
            icon: const Icon(Icons.add),
            onSelected: (kind) => c.updateScheme(
                (s) => s.components.add(NameComponent(kind: kind))),
            itemBuilder: (_) => [
              for (final k in NameComponentKind.values)
                PopupMenuItem(value: k, child: Text(k.label)),
            ],
          ),
        ),
        const Divider(),
        _numField(context, c, '序号起始', s.indexStart, 1,
            (v) => c.updateScheme((s) => s.indexStart = v)),
        _numField(context, c, '序号补零位数', s.indexPadding, 1,
            (v) => c.updateScheme((s) => s.indexPadding = v)),
        const SizedBox(height: 4),
        DropdownButtonFormField<ExtPolicy>(
          initialValue: s.extPolicy,
          decoration: const InputDecoration(
              labelText: '扩展名策略', border: OutlineInputBorder()),
          items: [
            for (final p in ExtPolicy.values)
              DropdownMenuItem(value: p, child: Text(p.label)),
          ],
          onChanged: (v) => c.updateScheme((s) => s.extPolicy = v!),
        ),
        SwitchListTile(
          dense: true,
          title: const Text('自动清洗非法字符'),
          subtitle: const Text(r'将 / \ : * ? " < > | 替换为下划线'),
          value: s.sanitize,
          onChanged: (v) => c.updateScheme((s) => s.sanitize = v),
        ),
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Text(
              '图片序号取自第 1 步的当前顺序（含人工调整）。\n'
              '下方表格可对单个文件覆盖名称；覆盖不会因修改结构而丢失。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ),
      ],
    );
  }

  Widget _componentCard(
      BuildContext context, WorkflowAController c, NamingScheme s, int i) {
    final comp = s.components[i];
    final needsText = comp.kind == NameComponentKind.prefix ||
        comp.kind == NameComponentKind.customText;
    return Card(
      key: ValueKey('comp-$i-${comp.kind}'),
      margin: const EdgeInsets.symmetric(vertical: 3),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(4, 4, 8, 4),
        child: Column(
          children: [
            Row(
              children: [
                ReorderableDragStartListener(
                  index: i,
                  child: const Icon(Icons.drag_indicator, size: 18),
                ),
                Expanded(child: Text(comp.kind.label)),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.delete_outline, size: 18),
                  tooltip: '移除组件',
                  onPressed: s.components.length <= 1
                      ? null
                      : () => c.updateScheme((s) => s.components.removeAt(i)),
                ),
              ],
            ),
            Row(
              children: [
                SizedBox(
                  width: 60,
                  child: TextFormField(
                    initialValue: comp.separatorBefore,
                    decoration: const InputDecoration(
                        labelText: '分隔符', isDense: true, border: OutlineInputBorder()),
                    style: const TextStyle(fontSize: 13),
                    onChanged: (v) => c.updateScheme((s) => comp.separatorBefore = v),
                  ),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: DropdownButtonFormField<CasePolicy>(
                    initialValue: comp.casePolicy,
                    isExpanded: true,
                    decoration: const InputDecoration(
                        labelText: '大小写', isDense: true, border: OutlineInputBorder()),
                    items: [
                      for (final p in CasePolicy.values)
                        DropdownMenuItem(value: p, child: Text(p.label)),
                    ],
                    onChanged: (v) => c.updateScheme((s) => comp.casePolicy = v!),
                  ),
                ),
              ],
            ),
            if (needsText)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: TextFormField(
                  initialValue: comp.text,
                  decoration: InputDecoration(
                    labelText: comp.kind == NameComponentKind.prefix ? '前缀文本' : '文本内容',
                    isDense: true,
                    border: const OutlineInputBorder(),
                  ),
                  style: const TextStyle(fontSize: 13),
                  onChanged: (v) => c.updateScheme((s) => comp.text = v),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _numField(BuildContext context, WorkflowAController c, String label,
      int value, int min, void Function(int) onChange) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: TextFormField(
        keyboardType: TextInputType.number,
        initialValue: value.toString(),
        decoration: InputDecoration(
            labelText: label, isDense: true, border: const OutlineInputBorder()),
        style: const TextStyle(fontSize: 13),
        onChanged: (v) {
          final n = int.tryParse(v);
          if (n != null && n >= min) onChange(n);
        },
      ),
    );
  }

  // -------------------------------------------------------------------------
  // 建议名称表
  // -------------------------------------------------------------------------

  Widget _buildPreviewTable(BuildContext context, WorkflowAController c) {
    final collection = c.currentCollection;
    final proposals = c.proposalsFor(collection.id);
    final shown = proposals.where((p) => _match(p)).toList();
    final conflictCount =
        proposals.where((p) => p.hasConflict).length;
    final overrideCount = proposals.where((p) => p.isOverridden).length;

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Wrap(
            spacing: 8,
            children: [
              SegmentedButton<_ProposalFilter>(
                segments: const [
                  ButtonSegment(value: _ProposalFilter.all, label: Text('全部')),
                  ButtonSegment(value: _ProposalFilter.conflict, label: Text('仅冲突')),
                  ButtonSegment(value: _ProposalFilter.overridden, label: Text('仅覆盖')),
                ],
                selected: {filter},
                onSelectionChanged: (s) => setState(() => filter = s.first),
              ),
              TagChip('冲突 $conflictCount', color: Colors.red),
              TagChip('人工覆盖 $overrideCount', color: Colors.blue),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
            itemCount: shown.length,
            itemBuilder: (context, i) {
              final p = shown[i];
              return _proposalRow(context, c, p);
            },
          ),
        ),
      ],
    );
  }

  bool _match(NameProposal p) => switch (filter) {
        _ProposalFilter.all => true,
        _ProposalFilter.conflict => p.hasConflict,
        _ProposalFilter.overridden => p.isOverridden,
      };

  Widget _proposalRow(BuildContext context, WorkflowAController c, NameProposal p) {
    final ctrl = _editCtrls.putIfAbsent(
      p.image.id,
      () => TextEditingController(text: p.finalName),
    );
    // 结构变化后同步显示（未被人工覆盖的项）
    if (!p.isOverridden && ctrl.text != p.finalName) {
      ctrl.text = p.finalName;
    }
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 2),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Row(
          children: [
            Expanded(
              flex: 5,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(p.image.relPath,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            const Icon(Icons.arrow_forward, size: 14),
            Expanded(
              flex: 4,
              child: TextField(
                controller: ctrl,
                style: TextStyle(
                  fontSize: 13,
                  color: p.hasConflict
                      ? Colors.red
                      : (p.isOverridden ? Colors.blue : null),
                ),
                decoration: InputDecoration(
                  isDense: true,
                  border: const OutlineInputBorder(),
                  suffixIcon: p.isOverridden
                      ? IconButton(
                          icon: const Icon(Icons.restart_alt, size: 16),
                          tooltip: '恢复建议名称',
                          onPressed: () {
                            ctrl.text = p.proposedName;
                            c.setOverride(p.image.id, null);
                          },
                        )
                      : null,
                ),
                onChanged: (v) => c.setOverride(p.image.id, v),
              ),
            ),
            SizedBox(
              width: 130,
              child: Wrap(
                spacing: 4,
                runSpacing: 2,
                children: [
                  for (final w in p.warnings)
                    TagChip(
                      switch (w.type) {
                        ProposalWarningType.duplicate => '重名',
                        ProposalWarningType.illegalChar => '非法字符',
                        ProposalWarningType.caseCollision => '大小写冲突',
                        ProposalWarningType.tooLong => '超长',
                        ProposalWarningType.overridden => '人工覆盖',
                      },
                      color: switch (w.type) {
                        ProposalWarningType.duplicate => Colors.red,
                        ProposalWarningType.illegalChar => Colors.red,
                        ProposalWarningType.caseCollision => Colors.orange,
                        ProposalWarningType.tooLong => Colors.orange,
                        ProposalWarningType.overridden => Colors.blue,
                      },
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

enum _ProposalFilter { all, conflict, overridden }
