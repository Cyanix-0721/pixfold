import 'package:flutter/services.dart';

/// pixfold/saf 通道的 Dart 封装(D2 SAF spike,见 HANGOFF §8.3)。
class SafChannel {
  static const MethodChannel _channel = MethodChannel('pixfold/saf');

  /// 弹出 SAF 目录选择器;返回 tree uri,取消返回 null。
  Future<String?> openTree() async =>
      await _channel.invokeMethod<String>('openTree');

  /// 读取上次授权的 tree uri(跨进程持久授权验证用);从未授权过返回 null。
  Future<String?> getLastTree() async =>
      await _channel.invokeMethod<String>('getLastTree');

  /// 递归列出 tree 下图片文件。
  Future<List<SafImage>> listImages(String treeUri, {int maxDepth = 4}) async {
    final raw = await _channel.invokeListMethod<Map<Object?, Object?>>(
        'listImages', {'uri': treeUri, 'maxDepth': maxDepth});
    return (raw ?? [])
        .map((m) => SafImage(
              name: m['name'] as String? ?? '',
              path: m['path'] as String? ?? '',
              uri: m['uri'] as String? ?? '',
              size: (m['size'] as num?)?.toInt() ?? -1,
              parent: m['parent'] as String? ?? '',
            ))
        .toList();
  }

  /// 读取文件字节,返回 {length, headHex}(前 16 字节 hex)。
  Future<Map<String, Object?>> readBytes(String uri) async =>
      Map<String, Object?>.from(
          await _channel.invokeMapMethod<Object?, Object?>('readBytes', {'uri': uri}) ?? {});

  Future<String?> renameDoc(String uri, String name) async =>
      await _channel.invokeMethod<String>('renameDoc', {'uri': uri, 'name': name});

  Future<String?> createAndWrite(String parentUri, String name, String content) async =>
      await _channel.invokeMethod<String>(
          'createAndWrite', {'parentUri': parentUri, 'name': name, 'content': content});

  Future<bool> deleteDoc(String uri) async =>
      (await _channel.invokeMethod<bool>('deleteDoc', {'uri': uri})) ?? false;
}

class SafImage {
  final String name;
  final String path; // 相对 tree 的目录路径(不含文件名)
  final String uri;
  final int size;
  final String parent; // 所在目录的 document uri(供 createAndWrite)

  SafImage({required this.name, required this.path, required this.uri, required this.size, required this.parent});

  @override
  String toString() => '$name  ${size >= 0 ? '${size}B' : '?'}  @$path';
}
