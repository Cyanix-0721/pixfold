/// 工作流 B · 步骤 1：识别漫画/系列/卷候选，确认分组；每卷展示建议值 + 来源 + 待确认项。
library;

import 'package:flutter/material.dart';

import '../domain/comic.dart';
import '../widgets/common.dart';
import 'flow.dart';

class LibraryPage extends StatelessWidget {
  const LibraryPage({super.key, required this.controller});

  final WorkflowBController controller;

  @override
  Widget build(BuildContext context) {
    final groups = controller.grouped;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Text('漫画库：${controller.library.rootPath}',
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Text(
          '识别到 ${groups.length} 个系列 / ${controller.volumeCount} 卷，'
          '勾选要处理的卷；点卷卡片进入下一步前可先查看建议值与来源。',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 8),
        for (final e in groups.entries) ...[
          _seriesHeader(context, e.key, e.value),
          ...e.value.map((v) => _volumeCard(context, v)),
          const SizedBox(height: 8),
        ],
      ],
    );
  }

  Widget _seriesHeader(BuildContext context, String series, List<ComicVolume> vols) {
    final pending = vols.where((v) => v.pendingIssues.isNotEmpty).length;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        children: [
          const Icon(Icons.collections_bookmark, size: 16),
          const SizedBox(width: 6),
          Expanded(child: Text(series, style: const TextStyle(fontWeight: FontWeight.bold))),
          TagChip('${vols.length} 卷'),
          if (pending > 0) TagChip('$pending 卷待确认', color: Colors.orange),
        ],
      ),
    );
  }

  Widget _volumeCard(BuildContext context, ComicVolume v) {
    final pending = v.pendingIssues;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 3),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        leading: Checkbox(
          value: v.included,
          onChanged: (_) => controller.toggleIncluded(v),
        ),
        title: Row(
          children: [
            Expanded(child: Text(v.dirPath, overflow: TextOverflow.ellipsis)),
            if (v.pageOrder.hasManual) const Icon(Icons.touch_app, size: 14, color: Colors.lightBlueAccent),
            if (v.outputExists) TagChip('输出冲突', color: Colors.red),
          ],
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 2),
            Text('${v.pages.length} 页 · 建议 Title「${v.titleSug.value}」（${v.titleSug.source}）',
                style: const TextStyle(fontSize: 12)),
            Text(
              '建议卷号 ${v.volumeSug.value?.toString() ?? '—'}（${v.volumeSug.source}） · '
              '建议语言 ${v.langSug.value ?? '—'}（${v.langSug.source}）',
              style: const TextStyle(fontSize: 12),
            ),
            if (pending.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Wrap(
                  spacing: 4,
                  children: [for (final p in pending) TagChip(p, color: Colors.orange)],
                ),
              ),
          ],
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () {
          controller.selectVolume(v.id);
        },
      ),
    );
  }
}
