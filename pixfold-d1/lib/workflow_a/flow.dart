/// 工作流 A（图片整理与命名）：控制器 + 三步流程壳。
/// 步骤：① 排序与预览 → ② 命名结构 → ③ 执行计划与报告。
library;

import 'package:flutter/material.dart';

import '../domain/models.dart';
import '../domain/naming.dart';
import '../mock/mock_data.dart';
import '../widgets/common.dart';
import 'naming_page.dart';
import 'plan_page.dart';
import 'sort_page.dart';

class WorkflowAController extends ChangeNotifier {
  WorkflowAController(this.workspace) {
    for (final c in workspace.collections) {
      final o = PageOrder(c.images);
      // PageOrder 自身的变化（拖拽重排/排序规则/固定位置）必须转发给 controller，
      // 否则 SortPage 的 AnimatedBuilder 收不到通知 → 数据变了但界面不刷新。
      o.addListener(notifyListeners);
      orders[c.id] = o;
    }
    scheme = NamingScheme(rootDirName: workspace.rootPath.split('\\').last);
  }

  @override
  void dispose() {
    for (final o in orders.values) {
      o.removeListener(notifyListeners);
      o.dispose();
    }
    super.dispose();
  }

  final MockWorkspaceA workspace;

  /// 每个集合的页序（两层状态）。
  final Map<String, PageOrder> orders = {};

  /// 批次默认排序规则 + 使用独立规则的集合（批次统一 + 逐项例外）。
  SortRule batchRule = PageOrder.defaultRule();
  final Set<String> customRuleCollections = {};

  /// 命名结构（批次级）；逐项例外存 overrides。
  late NamingScheme scheme;

  /// 逐项覆盖（imageId → 名称）。scheme/顺序变化重算后不丢失。
  final Map<String, String> overrides = {};

  String currentCollectionId = '';

  // 计划与执行
  List<RenameOp>? plan;
  ExecutionReport? report;
  bool cleanEmptyDirs = false; // 危险选项，默认关
  bool executed = false;

  ImageCollection get currentCollection =>
      workspace.collections.firstWhere((c) => c.id == currentCollectionId);

  PageOrder get currentOrder => orders[currentCollectionId]!;

  bool collectionHasManual(String id) => orders[id]?.hasManual ?? false;

  int get manualTotal =>
      orders.values.where((o) => o.hasManual).length;

  void selectCollection(String id) {
    currentCollectionId = id;
    notifyListeners();
  }

  /// 当前生效规则（本组独立规则 or 批次默认）。
  SortRule effectiveRule(String collectionId) =>
      customRuleCollections.contains(collectionId)
          ? orders[collectionId]!.rule
          : batchRule;

  /// 编辑的是批次默认还是本组规则。
  bool get editingBatchRule => !customRuleCollections.contains(currentCollectionId);

  void applyRule(String collectionId, SortRule rule) {
    if (customRuleCollections.contains(collectionId)) {
      orders[collectionId]!.applyRule(rule);
    } else {
      batchRule = rule.copy();
      for (final e in orders.entries) {
        if (!customRuleCollections.contains(e.key)) {
          e.value.applyRule(batchRule);
        }
      }
    }
    invalidatePlan();
    notifyListeners();
  }

  void toggleCustomRule(String collectionId) {
    if (!customRuleCollections.add(collectionId)) {
      customRuleCollections.remove(collectionId);
      orders[collectionId]!.applyRule(batchRule);
    }
    notifyListeners();
  }

  void updateScheme(void Function(NamingScheme s) fn) {
    fn(scheme);
    invalidatePlan();
    notifyListeners();
  }

  void setOverride(String imageId, String? name) {
    if (name == null || name.isEmpty) {
      overrides.remove(imageId);
    } else {
      overrides[imageId] = name;
    }
    invalidatePlan();
    notifyListeners();
  }

  /// 某集合的建议名称（顺序取当前页序，含人工调整）。
  List<NameProposal> proposalsFor(String collectionId) {
    final order = orders[collectionId]!;
    return buildProposals(order: order.order, scheme: scheme, overrides: overrides);
  }

  void invalidatePlan() {
    plan = null;
  }

  /// 生成整库计划。
  List<RenameOp> buildPlan() {
    final ops = <RenameOp>[];
    for (final c in workspace.collections) {
      ops.addAll(buildRenamePlan(proposalsFor(c.id)));
    }
    plan = ops;
    return ops;
  }

  void simulateExecute() {
    final ops = plan ?? buildPlan();
    var ok = 0;
    var skip = 0;
    final msgs = <String>[];
    for (final op in ops) {
      if (op.skipped) {
        skip++;
        msgs.add('跳过 ${op.proposal.image.relPath}（${op.reason}）');
      } else {
        ok++;
      }
    }
    if (cleanEmptyDirs) {
      msgs.add('已清理空目录（本原型仅记录，不执行真实删除）');
    }
    report = ExecutionReport(
      time: DateTime.now(),
      okCount: ok,
      skipCount: skip,
      failCount: 0,
      messages: msgs,
    );
    executed = true;
    notifyListeners();
  }

  void undo() {
    executed = false;
    report = null;
    notifyListeners();
  }
}

class WorkflowAScreen extends StatefulWidget {
  const WorkflowAScreen({super.key, required this.workspace});

  final MockWorkspaceA workspace;

  @override
  State<WorkflowAScreen> createState() => _WorkflowAScreenState();
}

class _WorkflowAScreenState extends State<WorkflowAScreen> {
  late final WorkflowAController controller;
  int step = 0;

  @override
  void initState() {
    super.initState();
    controller = WorkflowAController(widget.workspace);
    controller.selectCollection(widget.workspace.collections.first.id);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return FlowShell(
          title: '工作流 A · 图片整理与命名（原型）',
          steps: const ['排序与预览', '命名结构', '执行计划'],
          current: step,
          onStep: (i) => setState(() => step = i),
          child: switch (step) {
            0 => SortPage(controller: controller),
            1 => NamingPage(controller: controller),
            _ => PlanPage(controller: controller),
          },
        );
      },
    );
  }
}
