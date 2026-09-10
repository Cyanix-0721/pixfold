/// 命名结构求值：按组件序列生成建议名称，并做冲突/非法字符/大小写冲突检测。
library;

import 'models.dart';

/// 按命名结构对"当前顺序"（含人工调整）逐项生成建议名称。
/// [order] 为该集合的最终页序；imageIndex 组件取其在 order 中的位置。
List<NameProposal> buildProposals({
  required List<MockImage> order,
  required NamingScheme scheme,
  Map<String, int>? dirIndexMap,
  Map<String, String>? overrides,
}) {
  // 目录序号：集合内去重目录排序后 1-based 编号
  final dirs = order.map((m) => m.dir).toSet().toList()..sort();
  final dim = dirIndexMap ??
      {for (var i = 0; i < dirs.length; i++) dirs[i]: i + 1};

  final proposals = <NameProposal>[];
  for (var i = 0; i < order.length; i++) {
    final img = order[i];
    final parts = <String>[];
    final seps = <String>[];
    for (final comp in scheme.components) {
      if (!comp.enabled) continue;
      final part = _componentText(comp, img, i, dim, scheme);
      if (part.isEmpty) continue;
      parts.add(part);
      seps.add(comp.separatorBefore);
    }
    // 首个组件不加分隔符，其余按各自前置分隔符拼接
    var name = parts.isEmpty ? '' : parts.first;
    for (var k = 1; k < parts.length; k++) {
      name += seps[k] + parts[k];
    }
    // 扩展名策略
    switch (scheme.extPolicy) {
      case ExtPolicy.keep:
        name += '.${img.ext}';
      case ExtPolicy.lower:
        name += '.${img.ext.toLowerCase()}';
      case ExtPolicy.upper:
        name += '.${img.ext.toUpperCase()}';
      case ExtPolicy.strip:
        break;
    }
    if (scheme.sanitize) name = sanitizeName(name, scheme.replacement);
    final p = NameProposal(image: img, proposedName: name);
    p.overrideName = overrides?[img.id];
    proposals.add(p);
  }
  _attachWarnings(proposals, sanitize: scheme.sanitize);
  return proposals;
}

String _componentText(
  NameComponent comp,
  MockImage img,
  int position,
  Map<String, int> dirIndexMap,
  NamingScheme scheme,
) {
  String t;
  switch (comp.kind) {
    case NameComponentKind.prefix:
    case NameComponentKind.customText:
      t = comp.text;
    case NameComponentKind.rootDir:
      t = scheme.rootDirName;
    case NameComponentKind.relDir:
      t = img.dir;
    case NameComponentKind.dirIndex:
      final n = dirIndexMap[img.dir] ?? 1;
      t = n.toString().padLeft(scheme.indexPadding, '0');
    case NameComponentKind.imageIndex:
      final n = scheme.indexStart + position;
      t = n.toString().padLeft(scheme.indexPadding, '0');
    case NameComponentKind.origName:
      t = img.baseName;
  }
  switch (comp.casePolicy) {
    case CasePolicy.keep:
      break;
    case CasePolicy.lower:
      t = t.toLowerCase();
    case CasePolicy.upper:
      t = t.toUpperCase();
  }
  if (comp.trimSpaces) t = t.trim();
  return t;
}

/// 全工作区冲突检测：重名（完全相同）、大小写冲突（Windows 同目录不区分大小写）、非法字符、超长。
void _attachWarnings(List<NameProposal> proposals, {required bool sanitize}) {
  final byName = <String, List<NameProposal>>{};
  for (final p in proposals) {
    byName.putIfAbsent(p.finalName, () => []).add(p);
    if (p.isOverridden) {
      p.warnings.add(const ProposalWarning(ProposalWarningType.overridden, '人工覆盖'));
    }
  }
  for (final p in proposals) {
    if (!sanitize && containsIllegalChar(p.finalName)) {
      p.warnings.add(const ProposalWarning(
          ProposalWarningType.illegalChar, '包含非法字符 /\\:*?"<>|'));
    }
    if (p.finalName.length > 180) {
      p.warnings.add(const ProposalWarning(ProposalWarningType.tooLong, '名称过长（>180 字符）'));
    }
    if ((byName[p.finalName]?.length ?? 0) > 1) {
      p.warnings.add(ProposalWarning(
          ProposalWarningType.duplicate, '目标名称重复（${byName[p.finalName]!.length} 项同名）'));
    }
  }
  // 大小写冲突：不同名但 lower 后相同
  final byLower = <String, List<NameProposal>>{};
  for (final p in proposals) {
    byLower.putIfAbsent(p.finalName.toLowerCase(), () => []).add(p);
  }
  for (final group in byLower.values) {
    if (group.length < 2) continue;
    final names = group.map((p) => p.finalName).toSet();
    if (names.length < group.length) continue; // 已按重名报过
    for (final p in group) {
      p.warnings.add(const ProposalWarning(
          ProposalWarningType.caseCollision, 'Windows 下大小写冲突（同名不同大小写）'));
    }
  }
}

/// 生成重命名执行计划：冲突项默认跳过（供 UI 选择策略后调整）。
List<RenameOp> buildRenamePlan(List<NameProposal> proposals) {
  return proposals.map((p) {
    if (p.hasConflict) {
      return RenameOp(
        proposal: p,
        status: 'skipped',
        reason: p.warnings
            .firstWhere((w) =>
                w.type == ProposalWarningType.duplicate ||
                w.type == ProposalWarningType.illegalChar)
            .message,
      );
    }
    return RenameOp(proposal: p, status: 'ok');
  }).toList();
}
