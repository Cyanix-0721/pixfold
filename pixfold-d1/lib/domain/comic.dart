/// 工作流 B 领域：漫画库分组、逐卷元数据（建议 + 人工覆盖）、ComicInfo.xml、打包计划。
library;

import 'package:flutter/foundation.dart';

import 'models.dart';

// ---------------------------------------------------------------------------
// 建议值（自动建议 ≠ 事实，必须带来源）
// ---------------------------------------------------------------------------

class Suggestion<T> {
  const Suggestion(this.value, this.source);
  final T? value;
  final String source;

  bool get hasValue => value != null && (value is! String || (value as String).isNotEmpty);
}

// ---------------------------------------------------------------------------
// 语言选项（HANGOFF §4：默认"未设置"，必须人工确认；逐卷可不同）
// ---------------------------------------------------------------------------

enum LangChoice {
  unset('未设置（需确认）'),
  zh('zh · 中文'),
  ja('ja · 日文'),
  other('其他 ISO 639-1'),
  unknown('未知'),
  skip('不写入标签');

  const LangChoice(this.label);
  final String label;
}

/// 语言 → ComicInfo.xml 的 LanguageISO 值；返回 null 表示不写入。
String? langIso(LangChoice choice, String? otherCode) {
  switch (choice) {
    case LangChoice.zh:
      return 'zh';
    case LangChoice.ja:
      return 'ja';
    case LangChoice.other:
      return (otherCode == null || otherCode.isEmpty) ? null : otherCode;
    case LangChoice.unset:
    case LangChoice.unknown:
    case LangChoice.skip:
      return null;
  }
}

// ---------------------------------------------------------------------------
// ComicVolume / ComicSeries
// ---------------------------------------------------------------------------

/// 可人工覆盖的元数据字段。
enum MetaField { title, series, writer, volume, language, cbzName, outputDir }

class ComicVolume extends ChangeNotifier {
  ComicVolume({
    required this.id,
    required this.dirPath,
    required this.pages,
    required this.titleSug,
    required this.seriesSug,
    required this.writerSug,
    required this.writerCleanedSug,
    required this.volumeSug,
    required this.langSug,
    required this.outputDirSug,
    required this.outputExists,
  }) {
    pageOrder = PageOrder(pages);
    title = titleSug.value ?? '';
    series = seriesSug.value ?? '';
    writer = writerSug.value ?? '';
    volume = volumeSug.value;
    language = LangChoice.unset;
    otherLangCode = '';
    cbzFileName = _defaultCbzName();
    outputDir = outputDirSug;
  }

  final String id;
  final String dirPath;
  final List<MockImage> pages;

  // 建议 + 来源
  final Suggestion<String> titleSug;
  final Suggestion<String> seriesSug;
  final Suggestion<String> writerSug;
  final Suggestion<String> writerCleanedSug; // 清理后的作者（去前缀/括号原作/尾部标签）
  final Suggestion<int?> volumeSug;
  final Suggestion<String> langSug;
  final String outputDirSug;

  /// mock：输出目录已存在同名 CBZ（演示冲突策略）。
  final bool outputExists;

  // 当前值（人工可改）
  late String title;
  late String series;
  late String writer;
  int? volume;
  LangChoice language = LangChoice.unset;
  String otherLangCode = '';
  late String cbzFileName;
  late String outputDir;

  /// 外部触发刷新（notifyListeners 是 protected）。
  void touch() => notifyListeners();

  /// 逐项例外：用户手动改过的字段，批量设置时不覆盖。
  final Set<MetaField> overridden = {};

  late final PageOrder pageOrder;

  bool included = true;

  String get seriesKey => series; // 分组键（用户可改，改后 UI 按 key 重新分组展示）

  String _defaultCbzName() {
    final t = (titleSug.value ?? id).replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
    final v = volumeSug.value;
    return v == null ? '$t.cbz' : '$t 第${v.toString().padLeft(2, '0')}卷.cbz';
  }

  /// 使用建议值（清掉该项的人工覆盖标记）。
  void useSuggestion(MetaField f) {
    switch (f) {
      case MetaField.title:
        title = titleSug.value ?? '';
      case MetaField.series:
        series = seriesSug.value ?? '';
      case MetaField.writer:
        writer = writerSug.value ?? '';
      case MetaField.volume:
        volume = volumeSug.value;
      case MetaField.language:
        language = LangChoice.unset;
        otherLangCode = '';
      case MetaField.cbzName:
        cbzFileName = _defaultCbzName();
      case MetaField.outputDir:
        outputDir = outputDirSug;
    }
    overridden.remove(f);
    notifyListeners();
  }

  /// 人工覆盖某字段。
  void setValue(MetaField f, Object? v) {
    switch (f) {
      case MetaField.title:
        title = v as String;
      case MetaField.series:
        series = v as String;
      case MetaField.writer:
        writer = v as String;
      case MetaField.volume:
        volume = v as int?;
      case MetaField.language:
        language = v as LangChoice;
      case MetaField.cbzName:
        cbzFileName = v as String;
      case MetaField.outputDir:
        outputDir = v as String;
    }
    overridden.add(f);
    notifyListeners();
  }

  /// 批量设置（批次统一 + 逐项例外：被人工覆盖的字段跳过）。
  /// 返回实际生效的卷数。
  static int batchApply(
    List<ComicVolume> volumes,
    MetaField f,
    Object? v, {
    bool force = false,
  }) {
    var n = 0;
    for (final vol in volumes) {
      if (!vol.included) continue;
      if (!force && vol.overridden.contains(f)) continue;
      vol.setValue(f, v);
      n++;
    }
    return n;
  }

  /// 未确认项：语言未设置 / 卷号缺失。
  List<String> get pendingIssues {
    final issues = <String>[];
    if (language == LangChoice.unset) issues.add('语言未确认');
    if (volume == null) issues.add('卷号缺失');
    return issues;
  }
}

// ---------------------------------------------------------------------------
// ComicInfo.xml 生成
// ---------------------------------------------------------------------------

String _xmlEscape(String s) => s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');

/// 生成 ComicInfo.xml 预览文本（与 scripts/batch_pack_cbz.py 行为对齐的字段子集）。
String buildComicInfoXml(ComicVolume vol) {
  final buf = StringBuffer();
  buf.writeln('<?xml version="1.0" encoding="utf-8"?>');
  buf.writeln(
      '<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">');
  void field(String tag, String? value) {
    if (value == null || value.isEmpty) {
      buf.writeln('  <$tag />');
    } else {
      buf.writeln('  <$tag>${_xmlEscape(value)}</$tag>');
    }
  }

  field('Title', vol.title);
  field('Series', vol.series);
  field('Number', vol.volume?.toString());
  field('Writer', vol.writer);
  field('LanguageISO', langIso(vol.language, vol.otherLangCode));
  buf.writeln('</ComicInfo>');
  return buf.toString();
}

// ---------------------------------------------------------------------------
// 页码重编号与打包计划
// ---------------------------------------------------------------------------

/// 页码重编号：页序 → 0001.ext, 0002.ext ...
List<MapEntry<MockImage, String>> renumberPages(ComicVolume vol) {
  final order = vol.pageOrder.order;
  final padding = order.length >= 100 ? 4 : 3;
  return [
    for (var i = 0; i < order.length; i++)
      MapEntry(order[i], '${(i + 1).toString().padLeft(padding, '0')}.${order[i].ext.toLowerCase()}'),
  ];
}

enum OutputConflictPolicy {
  rename('自动重命名（加序号）'),
  skip('跳过该卷'),
  overwrite('覆盖已有文件');

  const OutputConflictPolicy(this.label);
  final String label;
}

enum SourcePolicy {
  keep('保留源目录（默认）'),
  delete('打包后删除源目录（危险）');

  const SourcePolicy(this.label);
  final String label;
}

/// 打包计划中单个卷的操作组。
class VolumePackPlan {
  VolumePackPlan({
    required this.volume,
    required this.renumbered,
    required this.conflict,
    required this.conflictPolicy,
    required this.sourcePolicy,
  });

  final ComicVolume volume;
  final List<MapEntry<MockImage, String>> renumbered;
  final bool conflict; // 输出已存在
  final OutputConflictPolicy conflictPolicy;
  final SourcePolicy sourcePolicy;

  String get outputDisplay {
    var name = cbzName(volume);
    if (conflict && conflictPolicy == OutputConflictPolicy.rename) {
      final dot = name.lastIndexOf('.');
      name = '${name.substring(0, dot)} (1)${name.substring(dot)}';
    }
    return '${volume.outputDir}/$name';
  }

  static String cbzName(ComicVolume v) => v.cbzFileName;

  bool get skipped =>
      conflict && conflictPolicy == OutputConflictPolicy.skip;

  List<String> describe() {
    final ops = <String>[];
    if (skipped) {
      ops.add('跳过：输出已存在 ${volume.outputDir}/${volume.cbzFileName}');
      return ops;
    }
    ops.add('创建 CBZ → $outputDisplay');
    ops.add('写入 ComicInfo.xml（Title=${volume.title}，Series=${volume.series}，'
        'Number=${volume.volume ?? "未设置"}，Writer=${volume.writer.isEmpty ? "空" : volume.writer}，'
        'LanguageISO=${langIso(volume.language, volume.otherLangCode) ?? "不写入"}）');
    ops.add('按页序打包 ${renumbered.length} 张并重命名为页码（0001 起）');
    if (sourcePolicy == SourcePolicy.delete) {
      ops.add('打包后删除源目录：${volume.dirPath}');
    }
    return ops;
  }
}

/// 生成整库打包计划。
List<VolumePackPlan> buildPackagePlans(
  List<ComicVolume> volumes, {
  OutputConflictPolicy conflictPolicy = OutputConflictPolicy.rename,
  SourcePolicy sourcePolicy = SourcePolicy.keep,
}) {
  return volumes
      .where((v) => v.included)
      .map((v) => VolumePackPlan(
            volume: v,
            renumbered: renumberPages(v),
            conflict: v.outputExists,
            conflictPolicy: conflictPolicy,
            sourcePolicy: sourcePolicy,
          ))
      .toList();
}
