/// 工作流 B（CBZ 制作）：控制器 + 四步流程壳。
/// 步骤：① 库与分组 → ② 页序与元数据 → ③ 预览 → ④ 计划与执行。
library;

import 'package:flutter/material.dart';

import '../domain/comic.dart';
import '../domain/models.dart';
import '../mock/mock_data.dart';
import '../widgets/common.dart';
import 'library_page.dart';
import 'metadata_page.dart';
import 'plan_page.dart' as b;

class WorkflowBController extends ChangeNotifier {
  WorkflowBController(this.library) {
    for (final v in library.volumes) {
      // 逐卷元数据(ComicVolume)与页序(PageOrder)的变化都要转发给 controller，
      // 否则页面收不到通知 → 数据变了但界面不刷新（与工作流 A 同类问题）。
      v.addListener(notifyListeners);
      v.pageOrder.addListener(notifyListeners);
    }
    currentVolumeId = library.volumes.first.id;
  }

  @override
  void dispose() {
    for (final v in library.volumes) {
      v.removeListener(notifyListeners);
      v.pageOrder.removeListener(notifyListeners);
      v.dispose();
    }
    super.dispose();
  }

  final MockLibraryB library;

  String currentVolumeId = '';
  int get volumeCount => library.volumes.length;
  int get includedCount => library.volumes.where((v) => v.included).length;

  ComicVolume get currentVolume =>
      library.volumes.firstWhere((v) => v.id == currentVolumeId);

  List<ComicVolume> get volumes => library.volumes;

  // 按当前 series 值分组展示（用户改 series 会改变分组）
  Map<String, List<ComicVolume>> get grouped => {
        for (final key in library.volumes.map((v) => v.series).toSet())
          key: library.volumes.where((v) => v.series == key).toList(),
      };

  void selectVolume(String id) {
    currentVolumeId = id;
    notifyListeners();
  }

  /// 外部触发刷新（notifyListeners 是 protected）。
  void refresh() => notifyListeners();

  void toggleIncluded(ComicVolume v) {
    v.included = !v.included;
    notifyListeners();
  }

  /// 批量设置（逐项例外保留）。
  int batchSet(MetaField f, Object? v, {bool force = false}) {
    final n = ComicVolume.batchApply(library.volumes, f, v, force: force);
    notifyListeners();
    return n;
  }

  // 计划
  OutputConflictPolicy conflictPolicy = OutputConflictPolicy.rename;
  SourcePolicy sourcePolicy = SourcePolicy.keep;
  List<VolumePackPlan>? plans;
  ExecutionReport? report;

  List<VolumePackPlan> buildPlans() {
    plans = buildPackagePlans(
      library.volumes,
      conflictPolicy: conflictPolicy,
      sourcePolicy: sourcePolicy,
    );
    return plans!;
  }

  void simulateExecute() {
    final ps = plans ?? buildPlans();
    var ok = 0;
    var skip = 0;
    final msgs = <String>[];
    for (final p in ps) {
      if (p.skipped) {
        skip++;
        msgs.add('跳过 ${p.volume.dirPath}（输出已存在）');
      } else {
        ok++;
      }
    }
    final langPending = library.volumes
        .where((v) => v.included && v.language == LangChoice.unset)
        .toList();
    for (final v in langPending) {
      msgs.add('⚠️ ${v.dirPath}：语言未确认（已按"不写入"处理）');
    }
    report = ExecutionReport(
      time: DateTime.now(),
      okCount: ok,
      skipCount: skip,
      failCount: 0,
      messages: msgs,
    );
    notifyListeners();
  }

  void undo() {
    report = null;
    notifyListeners();
  }
}

class WorkflowBScreen extends StatefulWidget {
  const WorkflowBScreen({super.key, required this.library});

  final MockLibraryB library;

  @override
  State<WorkflowBScreen> createState() => _WorkflowBScreenState();
}

class _WorkflowBScreenState extends State<WorkflowBScreen> {
  late final WorkflowBController controller;
  int step = 0;

  @override
  void initState() {
    super.initState();
    controller = WorkflowBController(widget.library);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      // controller 已转发所有 volume / pageOrder 的变更（见构造函数）
      animation: controller,
      builder: (context, _) {
        return FlowShell(
          title: '工作流 B · CBZ 制作（原型）',
          steps: const ['库与分组', '页序与元数据', '预览', '计划与执行'],
          current: step,
          onStep: (i) => setState(() => step = i),
          child: switch (step) {
            0 => LibraryPage(controller: controller),
            1 => MetadataPage(controller: controller),
            2 => b.PreviewPage(controller: controller),
            _ => b.PlanPage(controller: controller),
          },
        );
      },
    );
  }
}
