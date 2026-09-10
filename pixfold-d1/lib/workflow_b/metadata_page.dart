/// 工作流 B · 步骤 2：逐卷页序（缩略图拖拽）+ 元数据编辑（建议值/来源/逐项覆盖）+ 批量设置。
library;

import 'package:flutter/material.dart';

import '../domain/comic.dart';
import '../widgets/common.dart';
import '../widgets/reorderable.dart';
import 'flow.dart';

class MetadataPage extends StatefulWidget {
  const MetadataPage({super.key, required this.controller});

  final WorkflowBController controller;

  @override
  State<MetadataPage> createState() => _MetadataPageState();
}

class _MetadataPageState extends State<MetadataPage> {
  bool showBatchPanel = false;

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final v = c.currentVolume;

    return ResponsiveTwoPane(
      sideWidth: 340,
      side: _buildSidePanel(context, c),
      main: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          _volumeHeader(context, v),
          const SizedBox(height: 8),
          Text('页序（拖拽调整，序号即 CBZ 内页码）',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          _pageStrip(context, c, v),
          const SizedBox(height: 12),
          _metadataForm(context, c, v),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------------
  // 侧栏：卷选择 + 批量设置
  // -------------------------------------------------------------------------

  Widget _buildSidePanel(BuildContext context, WorkflowBController c) {
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Text('卷（${c.includedCount}/${c.volumeCount} 已选）',
            style: Theme.of(context).textTheme.titleSmall),
        ...c.library.volumes.map((v) {
          final issues = v.pendingIssues;
          return ListTile(
            dense: true,
            selected: v.id == c.currentVolumeId,
            title: Text(v.titleSug.value ?? v.id, overflow: TextOverflow.ellipsis),
            subtitle: Text(
              '${v.pages.length} 页${v.pageOrder.hasManual ? ' · 人工调整' : ''}',
              style: const TextStyle(fontSize: 12),
            ),
            trailing: issues.isNotEmpty
                ? TagChip(issues.first, color: Colors.orange)
                : (v.included ? null : TagChip('未选', color: Colors.grey)),
            onTap: () => c.selectVolume(v.id),
          );
        }),
        const Divider(),
        Row(
          children: [
            Expanded(
                child: Text('批量设置', style: Theme.of(context).textTheme.titleSmall)),
            TextButton.icon(
              icon: Icon(showBatchPanel ? Icons.expand_less : Icons.expand_more),
              label: Text(showBatchPanel ? '收起' : '展开'),
              onPressed: () => setState(() => showBatchPanel = !showBatchPanel),
            ),
          ],
        ),
        if (showBatchPanel) _batchPanel(context, c),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Text(
              '批次统一 + 逐项例外：批量设置不会覆盖你手动改过的字段；'
              '被覆盖字段显示蓝色标记，可一键恢复建议值。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ),
      ],
    );
  }

  Widget _batchPanel(BuildContext context, WorkflowBController c) {
    final overriddenSeries = c.library.volumes
        .where((v) => v.included && v.overridden.contains(MetaField.series))
        .length;
    final overriddenLang = c.library.volumes
        .where((v) => v.included && v.overridden.contains(MetaField.language))
        .length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _batchSeriesField(context, c),
        const SizedBox(height: 6),
        DropdownButtonFormField<LangChoice>(
          initialValue: LangChoice.unset,
          decoration: const InputDecoration(
              labelText: '批量语言', isDense: true, border: OutlineInputBorder()),
          items: [
            for (final l in LangChoice.values)
              DropdownMenuItem(value: l, child: Text(l.label)),
          ],
          onChanged: (l) {
            if (l == null) return;
            final n = c.batchSet(MetaField.language, l);
            _toast(context, '语言已批量设置到 $n 卷（跳过 $overriddenLang 卷逐项例外）');
          },
        ),
        const SizedBox(height: 6),
        TextButton.icon(
          icon: const Icon(Icons.cleaning_services, size: 16),
          label: const Text('全部使用清理后的作者名'),
          onPressed: () {
            var n = 0;
            for (final v in c.library.volumes.where((v) => v.included)) {
              v.setValue(MetaField.writer, v.writerCleanedSug.value ?? v.writer);
              n++;
            }
            c.refresh();
            _toast(context, '已对 $n 卷应用清理后的作者名');
          },
        ),
        if (overriddenSeries + overriddenLang > 0)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              '当前逐项例外：系列 $overriddenSeries 卷 / 语言 $overriddenLang 卷',
              style: const TextStyle(fontSize: 12, color: Colors.blue),
            ),
          ),
      ],
    );
  }

  Widget _batchSeriesField(BuildContext context, WorkflowBController c) {
    final ctrl = TextEditingController();
    return TextField(
      controller: ctrl,
      decoration: InputDecoration(
        labelText: '批量系列名',
        isDense: true,
        border: const OutlineInputBorder(),
        suffixIcon: IconButton(
          icon: const Icon(Icons.done_all, size: 18),
          tooltip: '应用到所有已选卷',
          onPressed: () {
            if (ctrl.text.isEmpty) return;
            final n = c.batchSet(MetaField.series, ctrl.text);
            _toast(context, '系列名已批量设置到 $n 卷');
          },
        ),
      ),
      style: const TextStyle(fontSize: 13),
    );
  }

  // -------------------------------------------------------------------------
  // 主区
  // -------------------------------------------------------------------------

  Widget _volumeHeader(BuildContext context, ComicVolume v) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(v.dirPath, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 4),
            Wrap(
              spacing: 8,
              children: [
                TagChip('${v.pages.length} 页'),
                if (v.pageOrder.hasManual)
                  TagChip('人工调整 ${v.pageOrder.manualCount} 处', color: Colors.lightBlue),
                if (v.pageOrder.pinnedCount > 0) TagChip('固定 ${v.pageOrder.pinnedCount}', color: Colors.amber),
                for (final i in v.pendingIssues) TagChip(i, color: Colors.orange),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _pageStrip(BuildContext context, WorkflowBController c, ComicVolume v) {
    final wide = MediaQuery.of(context).size.width >= 1200;
    final order = v.pageOrder.order;
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: wide ? 8 : 5,
        mainAxisSpacing: 4,
        crossAxisSpacing: 4,
        childAspectRatio: 0.72,
      ),
      itemCount: order.length,
      itemBuilder: (context, i) {
        final img = order[i];
        return LayoutBuilder(
          builder: (ctx, cons) => DragReorderItem(
            itemId: img.id,
            index: i,
            feedbackSize: Size(cons.maxWidth - 6, cons.maxHeight - 6),
            onMove: (id, t) => v.pageOrder.moveItemTo(id, t),
            child: ThumbCard(
              image: img,
              index: i + 1,
              manual: v.pageOrder.isManual(img.id),
              pinned: v.pageOrder.isPinned(img.id),
              onPin: () => v.pageOrder.togglePin(img.id),
              onTap: () => showDialog<void>(
                context: context,
                builder: (_) => ImageViewerDialog(images: order, initialIndex: i),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _metadataForm(BuildContext context, WorkflowBController c, ComicVolume v) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('元数据（建议 ≠ 事实，蓝色 = 人工覆盖）',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            _metaField(
              context, v, MetaField.title, 'Title',
              v.title, v.titleSug,
            ),
            _metaField(
              context, v, MetaField.series, 'Series',
              v.series, v.seriesSug,
            ),
            _writerField(context, v),
            _volumeField(context, v),
            _languageField(context, v),
            _cbzFields(context, v),
          ],
        ),
      ),
    );
  }

  Widget _metaField(
    BuildContext context,
    ComicVolume v,
    MetaField field,
    String label,
    String value,
    Suggestion<String> sug,
  ) {
    final overridden = v.overridden.contains(field);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              SizedBox(width: 70, child: Text(label)),
              TagChip('建议：${sug.value ?? "—"}', color: Colors.teal),
              const SizedBox(width: 4),
              Flexible(child: Text('来源：${sug.source}', overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, color: Colors.teal))),
              if (overridden) ...[
                const SizedBox(width: 4),
                TagChip('人工覆盖', color: Colors.blue),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.restart_alt, size: 14),
                  tooltip: '恢复建议值',
                  onPressed: () => v.useSuggestion(field),
                ),
              ],
            ],
          ),
          TextFormField(
            key: ValueKey('meta-$field-${v.id}-${sug.value}'),
            initialValue: value,
            style: TextStyle(color: overridden ? Colors.blue : null),
            decoration: const InputDecoration(isDense: true, border: OutlineInputBorder()),
            onChanged: (t) => v.setValue(field, t),
          ),
        ],
      ),
    );
  }

  Widget _writerField(BuildContext context, ComicVolume v) {
    final sug = v.writerSug;
    final cleaned = v.writerCleanedSug;
    final overridden = v.overridden.contains(MetaField.writer);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const SizedBox(width: 70, child: Text('Writer')),
              Flexible(
                child: Text('来源：${sug.source}', overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, color: Colors.teal)),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Row(
            children: [
              Expanded(
                child: TextFormField(
                  key: ValueKey('meta-writer-${v.id}-${sug.value}'),
                  initialValue: v.writer,
                  style: TextStyle(color: overridden ? Colors.blue : null),
                  decoration: const InputDecoration(isDense: true, border: OutlineInputBorder()),
                  onChanged: (t) => v.setValue(MetaField.writer, t),
                ),
              ),
              const SizedBox(width: 6),
              Tooltip(
                message: '应用清理：去作者前缀 / 括号原作 / 尾部标签',
                child: OutlinedButton(
                  onPressed: () =>
                      v.setValue(MetaField.writer, cleaned.value ?? v.writer),
                  child: Text('清理 → ${cleaned.value}', style: const TextStyle(fontSize: 12)),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _volumeField(BuildContext context, ComicVolume v) {
    final overridden = v.overridden.contains(MetaField.volume);
    final missing = v.volume == null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          const SizedBox(width: 70, child: Text('Number')),
          Expanded(
            child: TextFormField(
              key: ValueKey('meta-vol-${v.id}-${v.volume}'),
              initialValue: v.volume?.toString() ?? '',
              keyboardType: TextInputType.number,
              style: TextStyle(color: overridden ? Colors.blue : null),
              decoration: InputDecoration(
                isDense: true,
                border: const OutlineInputBorder(),
                hintText: '卷号（建议：${v.volumeSug.value?.toString() ?? "无"}）',
                errorText: missing ? '卷号缺失，请补填或确认' : null,
              ),
              onChanged: (t) => v.setValue(MetaField.volume, int.tryParse(t)),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '来源：${v.volumeSug.source}',
              style: TextStyle(fontSize: 12, color: missing ? Colors.orange : Colors.teal),
            ),
          ),
        ],
      ),
    );
  }

  Widget _languageField(BuildContext context, ComicVolume v) {
    final overridden = v.overridden.contains(MetaField.language);
    final unset = v.language == LangChoice.unset;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const SizedBox(width: 70, child: Text('LanguageISO')),
              TagChip(
                '建议：${v.langSug.value ?? "无线索"}',
                color: v.langSug.hasValue ? Colors.teal : Colors.grey,
              ),
              const SizedBox(width: 4),
              Flexible(
                child: Text('来源：${v.langSug.source}',
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, color: Colors.teal)),
              ),
              if (overridden) TagChip('人工覆盖', color: Colors.blue),
            ],
          ),
          Row(
            children: [
              Expanded(
                child: DropdownButtonFormField<LangChoice>(
                  key: ValueKey('lang-${v.id}-${v.language}'),
                  initialValue: v.language,
                  isExpanded: true,
                  decoration: InputDecoration(
                    isDense: true,
                    border: const OutlineInputBorder(),
                    errorText: unset ? '语言默认未设置，需人工确认' : null,
                  ),
                  items: [
                    for (final l in LangChoice.values)
                      DropdownMenuItem(value: l, child: Text(l.label)),
                  ],
                  onChanged: (l) => v.setValue(MetaField.language, l!),
                ),
              ),
              if (v.language == LangChoice.other)
                SizedBox(
                  width: 180,
                  child: Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: TextField(
                      decoration: const InputDecoration(
                        isDense: true, border: OutlineInputBorder(), labelText: 'ISO 639-1',
                      ),
                      onChanged: (t) {
                        v.otherLangCode = t.trim();
                        v.touch();
                      },
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _cbzFields(BuildContext context, ComicVolume v) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('输出', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 6),
        Row(
          children: [
            Expanded(
              child: TextFormField(
                key: ValueKey('cbz-${v.id}-${v.cbzFileName}'),
                initialValue: v.cbzFileName,
                decoration: const InputDecoration(
                    labelText: 'CBZ 文件名', isDense: true, border: OutlineInputBorder()),
                onChanged: (t) => v.setValue(MetaField.cbzName, t),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              flex: 2,
              child: TextFormField(
                key: ValueKey('outdir-${v.id}-${v.outputDir}'),
                initialValue: v.outputDir,
                decoration: const InputDecoration(
                    labelText: '输出目录', isDense: true, border: OutlineInputBorder()),
                onChanged: (t) => v.setValue(MetaField.outputDir, t),
              ),
            ),
          ],
        ),
        if (v.outputExists)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: TagChip('该目录已存在同名 CBZ（第 4 步选择冲突策略）', color: Colors.red),
          ),
      ],
    );
  }

  void _toast(BuildContext context, String msg) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));
  }
}
