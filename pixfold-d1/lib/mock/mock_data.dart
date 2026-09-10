/// D1 原型 mock 数据：确定性种子生成，覆盖 HANGOFF 要求的异常样例——
/// 重名、非法字符文件名、大小写冲突、乱序数字（自然排序）、缺卷号、语言不一致、输出冲突。
library;

import '../domain/comic.dart';
import '../domain/models.dart';

DateTime _d(int y, int m, int day, [int h = 12, int min = 0]) => DateTime(y, m, day, h, min);

// ---------------------------------------------------------------------------
// 工作流 A：图片整理 mock 工作区
// ---------------------------------------------------------------------------

class MockWorkspaceA {
  MockWorkspaceA({required this.rootPath, required this.collections});
  final String rootPath;
  final List<ImageCollection> collections;
}

MockWorkspaceA buildWorkspaceA() {
  final c1 = ImageCollection(
    id: 'trip',
    name: '旅行照片',
    rootDirName: '旅行照片',
    images: _gen('trip', '旅行照片', [
      ('day1', 'IMG_20260701', 'jpg', 18),
      ('day2', 'IMG_20260702', 'jpg', 12),
    ]),
  );

  // 异常样例集中区：
  // - 跨子目录同名 baseName（改名后可能重名）
  // - 非法字符文件名（封:面?.png）
  // - 大小写冲突对 page1.JPG / Page1.jpg
  final scan = ImageCollection(
    id: 'scan',
    name: '扫描件',
    rootDirName: '扫描件',
    images: [
      ..._gen('scan', '扫描件', [('ch01', 'page', 'png', 6)]),
      ..._gen('scan', '扫描件', [('ch02', 'page', 'png', 6)]), // 与 ch01 同名 → 重名样例
      MockImage(
        id: 'scan-x1',
        collectionId: 'scan',
        dir: 'ch02',
        baseName: '封:面?',
        ext: 'png',
        sizeBytes: 2048,
        modified: _d(2026, 8, 1, 9, 30),
        created: _d(2026, 8, 1, 9, 30),
        seed: 91,
      ),
      MockImage(
        id: 'scan-x2',
        collectionId: 'scan',
        dir: 'ch02',
        baseName: 'Page1',
        ext: 'JPG',
        sizeBytes: 1536,
        modified: _d(2026, 8, 1, 10, 0),
        created: _d(2026, 8, 1, 10, 0),
        seed: 92,
      ),
    ],
  );

  // 自然排序样例：img2 / img10 / img1 字典序会乱，自然序正确
  final misc = ImageCollection(
    id: 'misc',
    name: '杂图',
    rootDirName: '杂图',
    images: _gen('misc', '杂图', [('', 'img', 'png', 12)]),
  );

  return MockWorkspaceA(rootPath: r'D:\照片整理_2026', collections: [c1, scan, misc]);
}

List<MockImage> _gen(
  String collectionId,
  String rootName,
  List<(String, String, String, int)> specs,
) {
  final out = <MockImage>[];
  var seed = collectionId.hashCode & 0xff;
  for (final (dir, base, ext, count) in specs) {
    for (var i = 1; i <= count; i++) {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      out.add(MockImage(
        id: '$collectionId-${dir.isEmpty ? 'root' : dir}-$i',
        collectionId: collectionId,
        dir: dir,
        baseName: '$base${_numLabel(base, i)}',
        ext: ext,
        sizeBytes: 300 * 1024 + seed % 4000 * 1024,
        modified: _d(2026, 7, 1 + (seed % 28), 8 + seed % 12, seed % 60),
        created: _d(2026, 6, 1 + (seed % 27), 8 + seed % 12, seed % 60),
        seed: seed,
      ));
    }
  }
  return out;
}

/// IMG_20260701 + 序号 → IMG_20260701_001；page → page_01；img → img1（配合自然排序样例）。
String _numLabel(String base, int i) {
  if (base.startsWith('IMG_')) return '_${i.toString().padLeft(3, '0')}';
  if (base == 'page') return '_${i.toString().padLeft(2, '0')}';
  return '$i'; // img → img1..img12（字典序 1,10,11,12,2... 自然序正确）
}

// ---------------------------------------------------------------------------
// 工作流 B：漫画库 mock
// ---------------------------------------------------------------------------

class MockLibraryB {
  MockLibraryB({required this.rootPath, required this.volumes});
  final String rootPath;
  final List<ComicVolume> volumes;
}

MockLibraryB buildLibraryB() {
  final volumes = <ComicVolume>[
    // 系列 1：卷号齐全，作者带前缀/括号原作/尾部标签（演示清理建议）
    _vol(
      id: 'aot-1',
      dir: r'E:\漫画库\进击的巨人\第01卷',
      pages: 16,
      title: ('进击的巨人 第01卷', '目录层级 L2/L3'),
      series: ('进击的巨人', '目录层级 L2'),
      writer: ('[作者]谏山创(原作:某人)(电子版)', '目录层级 L2 段命名'),
      writerCleaned: '谏山创',
      volume: (1, '目录名「第01卷」解析'),
      lang: ('ja', '卷内文件名含日文片段（低置信）'),
      outputExists: false,
    ),
    _vol(
      id: 'aot-2',
      dir: r'E:\漫画库\进击的巨人\第02卷',
      pages: 18,
      title: ('进击的巨人 第02卷', '目录层级 L2/L3'),
      series: ('进击的巨人', '目录层级 L2'),
      writer: ('[作者]谏山创(原作:某人)(电子版)', '目录层级 L2 段命名'),
      writerCleaned: '谏山创',
      volume: (2, '目录名「第02卷」解析'),
      lang: ('ja', '卷内文件名含日文片段（低置信）'),
      outputExists: false,
    ),
    // 缺卷号样例：目录名无卷号 → 建议空 + 警告
    _vol(
      id: 'aot-0',
      dir: r'E:\漫画库\进击的巨人\外传',
      pages: 12,
      title: ('进击的巨人 外传', '目录层级 L2/L3'),
      series: ('进击的巨人', '目录层级 L2'),
      writer: ('[作者]谏山创(原作:某人)(电子版)', '目录层级 L2 段命名'),
      writerCleaned: '谏山创',
      volume: (null, '目录名「外传」未解析出卷号'),
      lang: ('ja', '系列内其他卷为 ja（推断）'),
      outputExists: false,
    ),
    // 系列 2：语言不一致样例（101 卷无语言线索；102 卷中文文件名 → zh 建议）
    // 输出冲突样例：102 卷输出目录已有同名 CBZ
    _vol(
      id: 'op-101',
      dir: r'E:\漫画库\海贼王\第101卷',
      pages: 20,
      title: ('海贼王 第101卷', '目录层级 L2/L3'),
      series: ('海贼王', '目录层级 L2'),
      writer: ('[作者]尾田荣一郎', '目录层级 L2 段命名'),
      writerCleaned: '尾田荣一郎',
      volume: (101, '目录名「第101卷」解析'),
      lang: (null, '无语言线索'),
      outputExists: false,
    ),
    _vol(
      id: 'op-102',
      dir: r'E:\漫画库\海贼王\第102卷',
      pages: 20,
      title: ('海贼王 第102卷', '目录层级 L2/L3'),
      series: ('海贼王', '目录层级 L2'),
      writer: ('[作者]尾田荣一郎', '目录层级 L2 段命名'),
      writerCleaned: '尾田荣一郎',
      volume: (102, '目录名「第102卷」解析'),
      lang: ('zh', '卷内文件名以中文命名（中置信）'),
      outputExists: true,
    ),
  ];
  return MockLibraryB(rootPath: r'E:\漫画库', volumes: volumes);
}

ComicVolume _vol({
  required String id,
  required String dir,
  required int pages,
  required (String, String) title,
  required (String, String) series,
  required (String, String) writer,
  required String writerCleaned,
  required (int?, String) volume,
  required (String?, String) lang,
  required bool outputExists,
}) {
  final segs = dir.split(r'\');
  final volDirName = segs.last;
  final images = <MockImage>[];
  var seed = id.hashCode & 0x7fffffff;
  for (var i = 1; i <= pages; i++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    images.add(MockImage(
      id: '$id-$i',
      collectionId: id,
      dir: '',
      baseName: '${volDirName}_p${i.toString().padLeft(3, '0')}',
      ext: 'jpg',
      sizeBytes: 800 * 1024 + seed % 3000 * 1024,
      modified: _d(2026, 8, 20, 8, i),
      created: _d(2026, 8, 20, 8, i),
      seed: seed,
    ));
  }
  return ComicVolume(
    id: id,
    dirPath: dir,
    pages: images,
    titleSug: Suggestion(title.$1, title.$2),
    seriesSug: Suggestion(series.$1, series.$2),
    writerSug: Suggestion(writer.$1, writer.$2),
    writerCleanedSug: Suggestion(writerCleaned, '清理规则：去作者前缀 / 括号原作 / 尾部标签'),
    volumeSug: Suggestion(volume.$1, volume.$2),
    langSug: Suggestion(lang.$1, lang.$2),
    outputDirSug: r'E:\漫画库\_output',
    outputExists: outputExists,
  );
}
