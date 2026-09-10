/// 工作流 A · 步骤 3：执行计划预览（操作清单 + 冲突处理 + 确认）+ 结果报告 + 撤销。
library;

import 'package:flutter/material.dart';

import '../domain/models.dart';
import '../widgets/common.dart';
import 'flow.dart';

class PlanPage extends StatefulWidget {
  const PlanPage({super.key, required this.controller});

  final WorkflowAController controller;

  @override
  State<PlanPage> createState() => _PlanPageState();
}

class _PlanPageState extends State<PlanPage> {
  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    if (c.report != null) {
      return _reportView(context, c);
    }
    final plan = c.plan ?? c.buildPlan();
    final okCount = plan.where((o) => !o.skipped).length;
    final skipCount = plan.length - okCount;
    final warnCount = plan
        .where((o) =>
            o.proposal.warnings.isNotEmpty &&
            !o.proposal.hasConflict)
        .length;

    return Column(
      children: [
        Card(
          margin: const EdgeInsets.all(12),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('执行计划（预览，尚未执行任何操作）',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 12,
                  children: [
                    TagChip('重命名 ${plan.length}', color: Colors.blue),
                    TagChip('冲突跳过 $skipCount', color: Colors.red),
                    TagChip('警告 $warnCount', color: Colors.orange),
                    TagChip('源文件保留', color: Colors.green),
                  ],
                ),
                const SizedBox(height: 8),
                CheckboxListTile(
                  dense: true,
                  title: const Text('移动后清理空目录（危险）'),
                  subtitle: const Text('默认关闭；勾选后仍需在确认对话框中再次确认'),
                  value: c.cleanEmptyDirs,
                  onChanged: (v) => setState(() => c.cleanEmptyDirs = v ?? false),
                ),
              ],
            ),
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            itemCount: plan.length,
            itemBuilder: (context, i) => _opRow(context, plan[i]),
          ),
        ),
        SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                FilledButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('确认执行'),
                  onPressed: () => _confirmExecute(context, c),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _opRow(BuildContext context, RenameOp op) {
    final p = op.proposal;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Icon(
            op.skipped ? Icons.block : Icons.drive_file_rename_outline,
            size: 16,
            color: op.skipped ? Colors.red : Colors.blue,
          ),
          const SizedBox(width: 6),
          Expanded(
            flex: 5,
            child: Text(p.image.relPath,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall),
          ),
          const Icon(Icons.arrow_forward, size: 12),
          Expanded(
            flex: 4,
            child: Text(p.finalName,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: op.skipped ? Colors.red : null)),
          ),
          SizedBox(
            width: 150,
            child: Text(
              op.skipped ? (op.reason ?? '跳过') : (p.warnings.isNotEmpty ? p.warnings.map((w) => w.message).join('；') : '—'),
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: op.skipped ? Colors.red : Colors.orange),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmExecute(BuildContext context, WorkflowAController c) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认执行？'),
        content: Text(
          '即将执行 ${c.plan?.length ?? 0} 项重命名操作：\n\n'
          '· 仅重命名（mock 数据，原型不写真实文件）\n'
          '· 源文件保留，不删除任何文件\n'
          '${c.cleanEmptyDirs ? '· ⚠️ 勾选了「清理空目录」，执行后将删除空目录（危险，独立确认）' : ''}\n'
          '· 冲突项将跳过并列出原因\n',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('再看看')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('执行')),
        ],
      ),
    );
    if (confirmed == true) {
      if (c.cleanEmptyDirs) {
        if (!context.mounted) return;
        final doubleOk = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('⚠️ 危险操作独立确认'),
            content: const Text('你勾选了「移动后清理空目录」。删除不可恢复，确认继续？'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
              FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('确认删除空目录')),
            ],
          ),
        );
        if (doubleOk != true) return;
      }
      c.simulateExecute();
    }
  }

  Widget _reportView(BuildContext context, WorkflowAController c) {
    final r = c.report!;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Icon(Icons.check_circle, color: Colors.green.shade600),
            const SizedBox(width: 8),
            Text('执行完成', style: Theme.of(context).textTheme.titleLarge),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 12,
          children: [
            TagChip('成功 ${r.okCount}', color: Colors.green),
            TagChip('跳过 ${r.skipCount}', color: Colors.red),
            TagChip('失败 ${r.failCount}', color: Colors.red),
            TagChip('时间 ${r.time.toString().substring(0, 19)}'),
          ],
        ),
        const SizedBox(height: 12),
        if (r.messages.isNotEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('跳过与警告明细', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  for (final m in r.messages)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 1),
                      child: Text(m,
                          style: Theme.of(context)
                              .textTheme
                              .bodySmall
                              ?.copyWith(color: Colors.orange)),
                    ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 16),
        Row(
          children: [
            OutlinedButton.icon(
              icon: const Icon(Icons.undo),
              label: const Text('撤销本次执行'),
              onPressed: () {
                c.undo();
                showInfoDialog(context, '已撤销',
                    '原型中的撤销为 mock：恢复到执行前状态。\n正式版将基于 UndoRecord 记录逆向操作。');
              },
            ),
          ],
        ),
      ],
    );
  }
}
