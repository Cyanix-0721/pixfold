/// PixFold D1 交互原型入口。
/// 原型说明：所有数据为确定性 mock，不访问真实文件系统；
/// 验证目标是 HANGOFF §9 D1 验收点 + §10 功能验收（交互层）。
library;

import 'package:flutter/material.dart';

import 'mock/mock_data.dart';
import 'workflow_a/flow.dart';
import 'workflow_b/flow.dart';

void main() {
  runApp(const PixFoldD1App());
}

class PixFoldD1App extends StatelessWidget {
  const PixFoldD1App({super.key});

  @override
  Widget build(BuildContext context) {
    // 显式指定 CJK 字体：避免 Windows 上 DirectWrite 回退链落到宋体等老字体导致发虚。
    const cjkFonts = ['Microsoft YaHei UI', 'Microsoft YaHei', 'Noto Sans CJK SC'];
    return MaterialApp(
      title: 'PixFold D1 原型',
      theme: ThemeData(
        colorSchemeSeed: Colors.indigo,
        useMaterial3: true,
        fontFamilyFallback: cjkFonts,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: Colors.indigo,
        useMaterial3: true,
        brightness: Brightness.dark,
        fontFamilyFallback: cjkFonts,
      ),
      // 全局字号放大 15%（用户反馈"字体稍微再大点"）。
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(1.15)),
        child: child!,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PixFold · D1 交互原型'),
        actions: [
          IconButton(
            tooltip: '原型说明',
            icon: const Icon(Icons.info_outline),
            onPressed: () => _showAbout(context),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            color: Theme.of(context).colorScheme.tertiaryContainer,
            child: const Padding(
              padding: EdgeInsets.all(12),
              child: Text(
                '本原型使用确定性 mock 数据，不连接真实文件系统。\n'
                '目标：完整走通两条工作流，验证每个必须人工输入的字段都有明确入口。\n'
                '异常样例已内置：重名 / 非法字符 / 大小写冲突 / 缺卷号 / 语言不一致 / 输出冲突。',
                style: TextStyle(fontSize: 13),
              ),
            ),
          ),
          const SizedBox(height: 8),
          LayoutBuilder(builder: (context, constraints) {
            final wide = constraints.maxWidth > 640;
            final cards = [
              _flowCard(
                context,
                icon: Icons.photo_library,
                title: '工作流 A · 图片整理与命名',
                desc: '目录分组 → 多级排序 → 缩略图拖拽 → 命名结构编辑 → 冲突检查 → 执行计划',
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => WorkflowAScreen(workspace: buildWorkspaceA())),
                ),
              ),
              _flowCard(
                context,
                icon: Icons.collections_bookmark,
                title: '工作流 B · CBZ 制作',
                desc: '库识别 → 分组确认 → 页序 + 逐卷元数据 → ComicInfo 预览 → 打包计划',
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => WorkflowBScreen(library: buildLibraryB())),
                ),
              ),
            ];
            if (wide) {
              return Row(
                children: [
                  for (final c in cards) Expanded(child: c),
                ],
              );
            }
            return Column(children: cards);
          }),
        ],
      ),
    );
  }

  Widget _flowCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String desc,
    required VoidCallback onTap,
  }) {
    return Card(
      margin: const EdgeInsets.all(8),
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 40, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 12),
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 6),
              Text(desc, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 12),
              const Align(
                alignment: Alignment.centerRight,
                child: Icon(Icons.arrow_forward),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showAbout(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('D1 交互原型'),
        content: const Text(
          '范围（HANGOFF §9 D1）：\n'
          '· 图片列表 / 缩略图预览\n'
          '· 拖拽排序（两层状态：自动 + 人工）\n'
          '· 命名结构编辑器（组件编排 / 逐项覆盖）\n'
          '· CBZ 元数据编辑器（建议值 + 来源 + 逐卷设置）\n'
          '· 执行计划预览与冲突警告\n\n'
          '不包含：真实文件系统访问（D2/D3）、真实打包执行。\n'
          '工程：C:\\Personal\\pixfold-d1（原型，验收通过后再决策正式工程）。',
        ),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了'))],
      ),
    );
  }
}
