import 'dart:convert';
import 'dart:io';

import 'package:path/path.dart' as p;

import '../core/constants.dart';
import '../core/utils.dart';

/// 分片元数据（与协议 10.2 一致）。
class PartMeta {
  final String path;
  final int size;
  final int mtime;
  final String? sha256;

  const PartMeta({
    required this.path,
    required this.size,
    required this.mtime,
    this.sha256,
  });

  Map<String, dynamic> toJson() => {
        'p': path,
        'size': size,
        'mtime': mtime,
        if (sha256 != null) 'sha256': sha256,
      };

  factory PartMeta.fromJson(Map<String, dynamic> j) => PartMeta(
        path: j['p'] as String? ?? '',
        size: (j['size'] as num?)?.toInt() ?? 0,
        mtime: (j['mtime'] as num?)?.toInt() ?? 0,
        sha256: j['sha256'] as String?,
      );
}

/// `.part` 分片管理：路径、元数据、续传偏移、7 天清理。
///
/// 分片路径：`<目标根>/.spw-send-part/<sha256(相对路径)>.part`
/// 元数据：同目录 `<...>.meta`
class PartStore {
  PartStore._();

  static String keyFor(String relativePath) =>
      sha256Hex(utf8.encode(relativePath));

  static File partFile(String root, String relativePath) => File(p.join(
        root,
        AppConstants.partDirName,
        '${keyFor(relativePath)}.part',
      ));

  static File metaFile(String root, String relativePath) => File(p.join(
        root,
        AppConstants.partDirName,
        '${keyFor(relativePath)}.meta',
      ));

  static Future<PartMeta?> readMeta(String root, String relativePath) async {
    try {
      final f = metaFile(root, relativePath);
      if (!await f.exists()) return null;
      final raw = await f.readAsString();
      final j = jsonDecode(raw);
      if (j is! Map) return null;
      return PartMeta.fromJson(Map<String, dynamic>.from(j));
    } catch (_) {
      return null;
    }
  }

  static Future<void> writeMeta(
    String root,
    String relativePath,
    PartMeta meta,
  ) async {
    final f = metaFile(root, relativePath);
    await f.parent.create(recursive: true);
    await f.writeAsString(jsonEncode(meta.toJson()), flush: true);
  }

  static Future<void> discard(String root, String relativePath) async {
    for (final f in [partFile(root, relativePath), metaFile(root, relativePath)]) {
      try {
        if (await f.exists()) await f.delete();
      } catch (_) {}
    }
  }

  /// 返回可续传的字节偏移。
  ///
  /// 分片缺失、元数据缺失或总大小不一致时清理分片并返回 0。
  static Future<int> currentOffset(
    String root,
    String relativePath,
    int size,
  ) async {
    final part = partFile(root, relativePath);
    final meta = await readMeta(root, relativePath);
    if (meta == null) {
      // 无元数据的孤立分片不可信，直接清理。
      if (await part.exists()) {
        try {
          await part.delete();
        } catch (_) {}
      }
      return 0;
    }
    if (meta.size != size) {
      await discard(root, relativePath);
      return 0;
    }
    if (!await part.exists()) return 0;
    final len = await part.length();
    if (len > size) {
      await discard(root, relativePath);
      return 0;
    }
    return len;
  }

  /// 接收端启动时清理超过 7 天的分片与元数据。
  static Future<void> cleanup(String root) async {
    final dir = Directory(p.join(root, AppConstants.partDirName));
    if (!await dir.exists()) return;
    final cutoff = DateTime.now()
        .subtract(const Duration(days: 7))
        .millisecondsSinceEpoch;
    try {
      await for (final entity in dir.list(followLinks: false)) {
        if (entity is! File) continue;
        final name = p.basename(entity.path);
        if (!name.endsWith('.part') && !name.endsWith('.meta')) continue;
        try {
          final stat = await entity.stat();
          if (stat.modified.millisecondsSinceEpoch < cutoff) {
            await entity.delete();
          }
        } catch (_) {}
      }
    } catch (_) {}
  }
}
