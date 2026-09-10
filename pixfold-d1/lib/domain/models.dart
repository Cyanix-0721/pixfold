/// PixFold D1 原型 · 领域模型（简化版，对应 HANGOFF §5 统一领域模型）。
/// 原型不连真实文件系统：MockImage 即 SourceItem 的 mock 快照。
library;

import 'package:flutter/foundation.dart';

// ---------------------------------------------------------------------------
// SourceItem（mock）
// ---------------------------------------------------------------------------

/// 一张图片的 mock 快照。dir 为相对目录（"" 表示根）。
class MockImage {
  MockImage({
    required this.id,
    required this.collectionId,
    required this.dir,
    required this.baseName,
    required this.ext,
    required this.sizeBytes,
    required this.modified,
    required this.created,
    required this.seed,
  });

  final String id;
  final String collectionId;
  final String dir;
  final String baseName;
  final String ext;
  final int sizeBytes;
  final DateTime modified;
  final DateTime created;

  /// 程序化缩略图的确定性种子。
  final int seed;

  String get fileName => '$baseName.$ext';
  String get relPath => dir.isEmpty ? fileName : '$dir/$fileName';

  String get sizeLabel {
    if (sizeBytes >= 1024 * 1024) {
      return '${(sizeBytes / 1024 / 1024).toStringAsFixed(1)} MB';
    }
    if (sizeBytes >= 1024) return '${(sizeBytes / 1024).toStringAsFixed(0)} KB';
    return '$sizeBytes B';
  }
}

/// 一个图片集合（分组），对应 Collection。
class ImageCollection {
  ImageCollection({
    required this.id,
    required this.name,
    required this.rootDirName,
    required this.images,
  });

  final String id;
  final String name;
  final String rootDirName;
  final List<MockImage> images;
}

// ---------------------------------------------------------------------------
// PageOrder：自动排序结果 + 人工调整结果（两层状态）
// ---------------------------------------------------------------------------

/// 排序字段。**声明顺序 = 下拉选项顺序**（2026-09-10 用户定序）：
/// 自然序是主路径（用户以 `IMG_1_xxx` 这类规范命名保证顺序）；
/// 字典序保留作对照/特殊场景，紧随其后；其余按键按常用度排。
enum SortField {
  naturalName('文件名(自然)'),
  fileName('文件名(字典)'),
  dirName('目录名'),
  modified('修改时间'),
  created('创建时间'),
  size('文件大小');

  const SortField(this.label);
  final String label;
}

class SortKey {
  SortKey(this.field, this.ascending);
  SortField field;
  bool ascending;
}

class SortRule {
  SortRule(this.keys);
  final List<SortKey> keys;

  SortRule copy() => SortRule(keys.map((k) => SortKey(k.field, k.ascending)).toList());
}

/// 两层排序状态：
/// - 自动层：applyRule() 按 [SortRule] 计算；
/// - 人工层：reorder() 拖拽、pin() 固定位置，均记录标记；
/// - resetToAuto() 一键回到自动层（固定项位置保留）。
class PageOrder extends ChangeNotifier {
  PageOrder(this.images, {SortRule? rule})
      : rule = rule ?? defaultRule() {
    _order = sortImages(images, this.rule);
  }

  final List<MockImage> images;
  SortRule rule;

  List<MockImage> _order = [];
  final Set<String> _manualIds = {};
  final Set<String> _pinnedIds = {};

  static SortRule defaultRule() =>
      SortRule([SortKey(SortField.dirName, true), SortKey(SortField.naturalName, true)]);

  List<MockImage> get order => List.unmodifiable(_order);
  bool get hasManual => _manualIds.isNotEmpty;
  int get manualCount => _manualIds.length;
  int get pinnedCount => _pinnedIds.length;
  bool isManual(String id) => _manualIds.contains(id);
  bool isPinned(String id) => _pinnedIds.contains(id);

  int indexOf(MockImage img) => _order.indexOf(img);

  /// 应用自动排序规则；固定位置的图片保留在当前位置，其余按规则填充空位。
  void applyRule(SortRule newRule) {
    rule = newRule.copy();
    // 记住固定项的当前位置
    final pinned = <int, MockImage>{};
    for (var i = 0; i < _order.length; i++) {
      if (_pinnedIds.contains(_order[i].id)) pinned[i] = _order[i];
    }
    final sorted = sortImages(images, rule);
    final rest = sorted.where((m) => !_pinnedIds.contains(m.id)).toList();
    final result = List<MockImage?>.filled(images.length, null);
    // 固定项先落位（索引越界则顺延找空位）
    final entries = pinned.entries.toList()
      ..sort((a, b) => a.key.compareTo(b.key));
    for (final e in entries) {
      var idx = e.key < result.length ? e.key : result.length - 1;
      while (idx >= 0 && result[idx] != null) {
        idx--;
      }
      if (idx < 0) continue;
      result[idx] = e.value;
    }
    // 其余按顺序填空位
    var ri = 0;
    for (var i = 0; i < result.length && ri < rest.length; i++) {
      if (result[i] == null) result[i] = rest[ri++];
    }
    _order = result.whereType<MockImage>().toList();
    _manualIds.clear();
    notifyListeners();
  }

  /// 拖拽调整：把 [id] 移到 [targetIndex] 位置，其余顺移；记录人工标记。
  /// targetIndex 语义 = 目标格子下标（0..length-1），移除源项后插入到该位。
  void moveItemTo(String id, int targetIndex) {
    final from = _order.indexWhere((m) => m.id == id);
    if (from < 0) return;
    if (targetIndex < 0) targetIndex = 0;
    if (targetIndex >= _order.length) targetIndex = _order.length - 1;
    if (from == targetIndex) return;
    final img = _order.removeAt(from);
    _order.insert(targetIndex, img);
    _manualIds.add(id);
    notifyListeners();
  }

  /// 固定/解除固定当前位置（重新应用自动排序时保留）。
  void togglePin(String id) {
    if (!_pinnedIds.add(id)) {
      _pinnedIds.remove(id);
    }
    notifyListeners();
  }

  /// 放弃人工调整，回到自动层。
  void resetToAuto() {
    applyRule(rule);
  }

  @override
  String toString() => 'PageOrder(${_order.length} 项, 人工 ${_manualIds.length}, 固定 ${_pinnedIds.length})';
}

/// 多级排序 + 自然排序比较。
List<MockImage> sortImages(List<MockImage> images, SortRule rule) {
  final list = images.toList();
  list.sort((a, b) {
    for (final key in rule.keys) {
      final c = _compareField(a, b, key.field);
      if (c != 0) return key.ascending ? c : -c;
    }
    return a.id.compareTo(b.id); // 稳定兜底
  });
  return list;
}

int _compareField(MockImage a, MockImage b, SortField f) {
  switch (f) {
    case SortField.dirName:
      return a.dir.compareTo(b.dir);
    case SortField.fileName:
      return a.fileName.toLowerCase().compareTo(b.fileName.toLowerCase());
    case SortField.naturalName:
      return naturalCompare(a.fileName.toLowerCase(), b.fileName.toLowerCase());
    case SortField.modified:
      return a.modified.compareTo(b.modified);
    case SortField.created:
      return a.created.compareTo(b.created);
    case SortField.size:
      return a.sizeBytes.compareTo(b.sizeBytes);
  }
}

/// 自然排序：数字段按数值比较（"2" < "10"）。
int naturalCompare(String a, String b) {
  var i = 0;
  var j = 0;
  while (i < a.length && j < b.length) {
    final da = _isDigit(a.codeUnitAt(i));
    final db = _isDigit(b.codeUnitAt(j));
    if (da && db) {
      var ia = i;
      var ib = j;
      while (ia < a.length && _isDigit(a.codeUnitAt(ia))) {
        ia++;
      }
      while (ib < b.length && _isDigit(b.codeUnitAt(ib))) {
        ib++;
      }
      final na = int.parse(a.substring(i, ia));
      final nb = int.parse(b.substring(j, ib));
      if (na != nb) return na.compareTo(nb);
      if (ia - i != ib - j) return (ia - i).compareTo(ib - j);
      i = ia;
      j = ib;
    } else {
      final c = a.codeUnitAt(i).compareTo(b.codeUnitAt(j));
      if (c != 0) return c;
      i++;
      j++;
    }
  }
  return (a.length - i).compareTo(b.length - j);
}

bool _isDigit(int c) => c >= 0x30 && c <= 0x39;

// ---------------------------------------------------------------------------
// NamingScheme / NameProposal
// ---------------------------------------------------------------------------

enum NameComponentKind {
  prefix('自定义前缀'),
  rootDir('根目录名'),
  relDir('相对目录片段'),
  dirIndex('目录序号'),
  imageIndex('图片序号'),
  origName('原文件名'),
  customText('自定义文本');

  const NameComponentKind(this.label);
  final String label;
}

enum CasePolicy {
  keep('保持原样'),
  lower('转小写'),
  upper('转大写');

  const CasePolicy(this.label);
  final String label;
}

enum ExtPolicy {
  keep('保留原样'),
  lower('统一小写'),
  upper('统一大写'),
  strip('去除扩展名');

  const ExtPolicy(this.label);
  final String label;
}

/// 命名结构的一个组件：启用、顺序、前置分隔符、大小写、清洗。
class NameComponent {
  NameComponent({
    required this.kind,
    this.enabled = true,
    this.separatorBefore = '_',
    this.casePolicy = CasePolicy.keep,
    this.trimSpaces = true,
    this.text = '',
  });

  NameComponentKind kind;
  bool enabled;
  String separatorBefore;
  CasePolicy casePolicy;
  bool trimSpaces;
  String text; // prefix / customText 的内容
}

/// 命名结构：组件序列 + 序号规则 + 扩展名策略 + 清洗规则。
class NamingScheme {
  NamingScheme({
    required this.rootDirName,
    List<NameComponent>? components,
    this.indexStart = 1,
    this.indexPadding = 3,
    this.extPolicy = ExtPolicy.lower,
    this.sanitize = true,
    this.replacement = '_',
  }) : components = components ??
            [
              NameComponent(kind: NameComponentKind.rootDir),
              NameComponent(kind: NameComponentKind.imageIndex, separatorBefore: '_'),
              NameComponent(kind: NameComponentKind.origName, enabled: false, separatorBefore: '_'),
            ];

  final String rootDirName;
  List<NameComponent> components;
  int indexStart;
  int indexPadding;
  ExtPolicy extPolicy;
  bool sanitize;
  String replacement;

  NamingScheme copy() => NamingScheme(
        rootDirName: rootDirName,
        components: components
            .map((c) => NameComponent(
                  kind: c.kind,
                  enabled: c.enabled,
                  separatorBefore: c.separatorBefore,
                  casePolicy: c.casePolicy,
                  trimSpaces: c.trimSpaces,
                  text: c.text,
                ))
            .toList(),
        indexStart: indexStart,
        indexPadding: indexPadding,
        extPolicy: extPolicy,
        sanitize: sanitize,
        replacement: replacement,
      );
}

enum ProposalWarningType { duplicate, illegalChar, caseCollision, tooLong, overridden }

class ProposalWarning {
  const ProposalWarning(this.type, this.message);
  final ProposalWarningType type;
  final String message;
}

/// 单个文件的建议名称：自动建议 + 逐项覆盖 + 警告。
class NameProposal {
  NameProposal({required this.image, required this.proposedName});

  final MockImage image;
  final String proposedName;
  String? overrideName;

  String get finalName => overrideName ?? proposedName;
  bool get isOverridden => overrideName != null;

  final List<ProposalWarning> warnings = [];
  bool get hasConflict =>
      warnings.any((w) => w.type == ProposalWarningType.duplicate || w.type == ProposalWarningType.illegalChar);
}

/// 重命名操作计划项。
class RenameOp {
  RenameOp({required this.proposal, required this.status, this.reason});

  final NameProposal proposal;
  final String status; // ok | skipped
  final String? reason;

  bool get skipped => status == 'skipped';
}

/// 执行报告。
class ExecutionReport {
  ExecutionReport({required this.time, required this.okCount, required this.skipCount, required this.failCount, required this.messages});

  final DateTime time;
  final int okCount;
  final int skipCount;
  final int failCount;
  final List<String> messages;
}

/// Windows 文件名非法字符（Android/Linux 同样按此保守处理）。
const String kIllegalNameChars = r'\/:*?"<>|';

bool containsIllegalChar(String name) {
  for (var i = 0; i < name.length; i++) {
    final c = name.codeUnitAt(i);
    if (kIllegalNameChars.contains(name[i]) || c < 0x20) return true;
  }
  return false;
}

String sanitizeName(String name, String replacement) {
  var result = name;
  for (var i = 0; i < kIllegalNameChars.length; i++) {
    result = result.replaceAll(kIllegalNameChars[i], replacement);
  }
  return result.replaceAll(RegExp(r'[\x00-\x1f]'), replacement);
}
