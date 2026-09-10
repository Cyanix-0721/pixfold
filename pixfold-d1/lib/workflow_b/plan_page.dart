/// 工作流 B · 步骤 3：ComicInfo.xml 预览 + 页码列表预览；
/// 步骤 4：冲突/源保留策略 + 打包计划 + 确认执行 + 报告 + 撤销。
library;

import 'package:flutter/material.dart';

import '../domain/comic.dart';
import '../widgets/common.dart';
import 'flow.dart';

// ---------------------------------------------------------------------------
// 步骤 3 · 预览
// ---------------------------------------------------------------------------

class PreviewPage extends StatelessWidget {
  const PreviewPage({super.key, required this.controller});

  final WorkflowBController controller;

  @override
  Widget build(BuildContext context) {
    final vols = controller.library.volumes.where((v) => v.included).toList();
    if (vols.isEmpty) {
      return const Center(child: Text('没有已选中的卷（回到第 1 步勾选）'));
    }
    return ResponsiveTwoPane(
      side: _volumeTabs(context, controller, vols),
      main: _previewBody(context, controller),
    );
  }

  Widget _volumeTabs(BuildContext context, WorkflowBController c, List<ComicVolume> vols) {
    return ListView(
      padding: const EdgeInsets.all(8),
      children: [
        for (final v in vols)
          ListTile(
            dense: true,
            selected: v.id == c.currentVolumeId,
            title: Text(v.title.isEmpty ? v.dirPath : v.title, overflow: TextOverflow.ellipsis),
            subtitle: Text('${v.pages.length} 页', style: const TextStyle(fontSize: 12)),
            onTap: () => c.selectVolume(v.id),
          ),
      ],
    );
  }

  Widget _previewBody(BuildContext context, WorkflowBController c) {
    final v = c.currentVolume;
    final renumbered = renumberPages(v);
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Row(
          children: [
            Expanded(
                child: Text('ComicInfo.xml 预览', style: Theme.of(context).textTheme.titleSmall)),
            TagChip('语言：${langIso(v.language, v.otherLangCode) ?? "不写入"}',
                color: v.language == LangChoice.unset ? Colors.orange : Colors.green),
          ],
        ),
        const SizedBox(height: 4),
        Card(
          color: Theme.of(context).colorScheme.surfaceContainerLowest,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              buildComicInfoXml(v),
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12.5, height: 1.4),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Text('页码列表（页序 → CBZ 内文件名）',
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Card(
          child: Column(
            children: [
              for (final e in renumbered)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                  child: Row(
                    children: [
                      Expanded(
                          child: Text(e.key.fileName,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodySmall)),
                      const Icon(Icons.arrow_forward, size: 12),
                      SizedBox(
                        width: 90,
                        child: Text(e.value,
                            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                color: v.pageOrder.isManual(e.key.id) ? Colors.blue : null)),
                      ),
                      if (v.pageOrder.isManual(e.key.id))
                        const Icon(Icons.touch_app, size: 12, color: Colors.lightBlueAccent),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// 步骤 4 · 计划与执行
// ---------------------------------------------------------------------------

class PlanPage extends StatelessWidget {
  const PlanPage({super.key, required this.controller});

  final WorkflowBController controller;

  @override
  Widget build(BuildContext context) {
    if (controller.report != null) {
      return _reportView(context, controller);
    }
    final plans = controller.plans ?? controller.buildPlans();
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('执行策略', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 4),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final p in OutputConflictPolicy.values)
                      ChoiceChip(
                        label: Text(p.label),
                        selected: controller.conflictPolicy == p,
                        onSelected: (_) {
                          controller.conflictPolicy = p;
                          controller.buildPlans();
                          controller.refresh();
                        },
                      ),
                  ],
                ),
                const SizedBox(height: 4),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final p in SourcePolicy.values)
                      ChoiceChip(
                        label: Text(p.label),
                        selected: controller.sourcePolicy == p,
                        onSelected: (_) {
                          controller.sourcePolicy = p;
                          controller.buildPlans();
                          controller.refresh();
                        },
                      ),
                  ],
                ),
                if (controller.sourcePolicy == SourcePolicy.delete)
                  const Padding(
                    padding: EdgeInsets.only(top: 4),
                    child: Text('⚠️ 删除源目录是危险操作，执行前需独立确认；默认保留。',
                        style: TextStyle(color: Colors.red, fontSize: 12)),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        Text('打包计划（预览，尚未执行任何操作）',
            style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        for (final p in plans) ...[
          _planCard(context, p),
          const SizedBox(height: 8),
        ],
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            FilledButton.icon(
              icon: const Icon(Icons.play_arrow),
              label: const Text('确认打包'),
              onPressed: () => _confirm(context, controller),
            ),
          ],
        ),
      ],
    );
  }

  Widget _planCard(BuildContext context, VolumePackPlan p) {
    final v = p.volume;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(p.skipped ? Icons.block : Icons.archive,
                    size: 16, color: p.skipped ? Colors.red : Colors.blue),
                const SizedBox(width: 6),
                Expanded(child: Text(v.dirPath, overflow: TextOverflow.ellipsis)),
                if (p.conflict) TagChip('输出冲突', color: Colors.red),
                for (final i in v.pendingIssues) TagChip(i, color: Colors.orange),
              ],
            ),
            const SizedBox(height: 6),
            for (final line in p.describe())
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 1),
                child: Text(line, style: Theme.of(context).textTheme.bodySmall),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _confirm(BuildContext context, WorkflowBController c) async {
    final deleteSource = c.sourcePolicy == SourcePolicy.delete;
    var go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认打包？'),
        content: Text(
          '即将对 ${c.buildPlans().length} 卷生成 CBZ（mock，原型不写真实文件）：\n\n'
          '· 打包并按页序重命名页码\n'
          '· 写入 ComicInfo.xml（以上一步预览为准）\n'
          '· 源目录：${deleteSource ? "删除（危险）" : "保留"}\n'
          '· 冲突策略：${c.conflictPolicy.label}\n',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('再看看')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('打包')),
        ],
      ),
    );
    if (go != true) return;
    if (deleteSource) {
      if (!context.mounted) return;
      go = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('⚠️ 危险操作独立确认'),
          content: const Text('你选择了「打包后删除源目录」。删除不可恢复，确认继续？'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('确认删除源目录')),
          ],
        ),
      );
      if (go != true) return;
    }
    c.simulateExecute();
  }

  Widget _reportView(BuildContext context, WorkflowBController c) {
    final r = c.report!;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Icon(Icons.check_circle, color: Colors.green.shade600),
            const SizedBox(width: 8),
            Text('打包完成', style: Theme.of(context).textTheme.titleLarge),
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
                  Text('明细', style: Theme.of(context).textTheme.titleSmall),
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
    );
  }
}
