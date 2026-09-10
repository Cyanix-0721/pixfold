import 'package:flutter/material.dart';
import 'saf_channel.dart';

void main() => runApp(const PixFoldSpikeApp());

class PixFoldSpikeApp extends StatelessWidget {
  const PixFoldSpikeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PixFold D2 SAF Spike',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const SafSpikePage(),
    );
  }
}

/// 测试序列:选目录 → 列图 → 读首张 → 复制改名 → 删除副本(对应 HANGOFF §8.3 验收)。
class SafSpikePage extends StatefulWidget {
  const SafSpikePage({super.key});

  @override
  State<SafSpikePage> createState() => _SafSpikePageState();
}

class _SafSpikePageState extends State<SafSpikePage> {
  final SafChannel _saf = SafChannel();
  final List<String> _log = [];
  final ScrollController _scroll = ScrollController();

  String? _tree;
  List<SafImage> _images = [];

  @override
  void initState() {
    super.initState();
    _restoreLastTree();
  }

  /// 启动时尝试恢复上次授权目录:能恢复则直接可点 ② 验证跨进程持久授权。
  Future<void> _restoreLastTree() async {
    final last = await _saf.getLastTree();
    if (last != null && mounted) {
      setState(() => _tree = last);
      _add('↻ 已恢复上次授权目录(跨进程)→ 直接点 ② 验证持久授权');
    }
  }

  Future<void> _run(String label, Future<Object?> Function() action) async {
    final sw = Stopwatch()..start();
    try {
      final r = await action();
      _add('✓ $label  ${sw.elapsedMilliseconds}ms  → $r');
    } catch (e) {
      _add('✗ $label  ${sw.elapsedMilliseconds}ms  → $e');
    }
  }

  void _add(String line) {
    setState(() {
      _log.add(line);
      if (_log.length > 200) _log.removeAt(0);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  Future<void> _openTree() => _run('openTree(选目录)', () async {
        _tree = await _saf.openTree();
        _images = [];
        return _tree ?? '(已取消)';
      });

  Future<void> _list() => _run('listImages', () async {
        if (_tree == null) return '(先选目录)';
        final list = await _saf.listImages(_tree!);
        _images = list;
        final head = list.take(3).map((e) => e.toString()).join(' | ');
        return '共 ${list.length} 张' + (head.isEmpty ? '' : '\n  前3:$head');
      });

  Future<void> _readFirst() => _run('readBytes(第1张)', () async {
        if (_images.isEmpty) return '(先列图)';
        final r = await _saf.readBytes(_images.first.uri);
        return '${r['length']}B head=${r['headHex']}';
      });

  Future<void> _writeRenameDelete() => _run('createAndWrite→rename→delete', () async {
        if (_images.isEmpty) return '(先列图)';
        final dir = _images.first.parent;
        final created = await _saf.createAndWrite(dir, 'spike_copy.txt', 'pixfold-spike write test');
        if (created == null) return '(create 失败)';
        final renamed = await _saf.renameDoc(created, 'spike_copy_renamed.txt');
        if (renamed == null) return '(rename 失败: $created)';
        final deleted = await _saf.deleteDoc(renamed);
        return 'created=$created\n  renamed=$renamed\n  deleted=$deleted';
      });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('PixFold D2 · SAF Spike')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton(onPressed: _openTree, child: const Text('① 选目录')),
                FilledButton.tonal(onPressed: _list, child: const Text('② 列图片')),
                OutlinedButton(onPressed: _readFirst, child: const Text('③ 读首张')),
                OutlinedButton(onPressed: _writeRenameDelete, child: const Text('④ 写→改名→删')),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Text(
              _tree == null
                  ? '未授权目录 · 点"① 选目录"开始(SAF 弹窗选择后重启进程应仍可访问=持久授权)'
                  : '已授权:$_tree',
              style: Theme.of(context).textTheme.bodySmall,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const Divider(height: 4),
          Expanded(
            child: ListView.builder(
              controller: _scroll,
              itemCount: _log.length,
              itemBuilder: (_, i) => Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
                child: Text(_log[i], style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
