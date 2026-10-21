import 'dart:io';

import 'package:path/path.dart' as p;

import '../core/utils.dart';
import '../models/file_entry.dart';

/// 本地文件索引扫描器（用于接收端目标目录）。
///
/// 递归扫描一个根目录，产出 `相对路径 -> FileEntry` 映射（跳过隐藏/系统文件）。
/// 差异比对只看相对路径，`size/mtime` 仅用于展示与续传校验。
class LocalIndexService {
  LocalIndexService._();

  static Future<Map<String, FileEntry>> scan(
    String root, {
    void Function(int count)? onProgress,
  }) async {
    final result = <String, FileEntry>{};
    final dir = Directory(root);
    if (root.trim().isEmpty || !await dir.exists()) {
      return result;
    }

    int count = 0;
    await for (final entity in dir.list(recursive: true, followLinks: false)) {
      if (entity is! File) continue;

      final rel = toRelativePath(root, entity.path);
      if (rel.isEmpty) continue;
      if (_isIgnoredPath(rel)) continue;

      try {
        final stat = await entity.stat();
        result[rel] = FileEntry(
          relativePath: rel,
          size: stat.size,
          mtimeMs: stat.modified.millisecondsSinceEpoch,
        );
        count++;
        onProgress?.call(count);
      } catch (_) {
        // 单个文件读取失败不影响整体扫描。
      }
    }

    return result;
  }

  /// 判断相对路径是否应被忽略（逐路径段判断）。
  static bool _isIgnoredPath(String relPath) {
    for (final part in relPath.split('/')) {
      if (part.isEmpty) continue;
      if (part.startsWith('.')) return true;
      if (part.startsWith('~\u0024')) return true;
      final lower = part.toLowerCase();
      if (lower == 'desktop.ini' ||
          lower == 'thumbs.db' ||
          lower == 'ehthumbs.db') {
        return true;
      }
    }
    return false;
  }

  /// 校验安全路径并解析为绝对路径；非法则返回 null。
  static File? safeFile(String root, String relativePath) {
    if (!isValidRelPath(relativePath)) return null;
    if (!isSafeWithin(root, relativePath)) return null;
    final file =
        File(p.join(root, relativePath.replaceAll('/', Platform.pathSeparator)));
    // 符号链接逃逸校验：若目标或其父目录已存在，真实路径必须仍在根目录内。
    try {
      final rootReal =
          Directory(root).resolveSymbolicLinksSync().replaceAll('\\', '/');
      final prefix = rootReal.endsWith('/') ? rootReal : '$rootReal/';
      FileSystemEntity probe = file;
      while (!probe.existsSync()) {
        final parent = probe.parent;
        if (parent.path == probe.path) break;
        probe = parent;
      }
      final probeReal = probe.resolveSymbolicLinksSync().replaceAll('\\', '/');
      if (probeReal != rootReal && !probeReal.startsWith(prefix)) {
        return null;
      }
    } catch (_) {
      // 无法解析（目录不存在等）时退回字符串越界校验结果。
    }
    return file;
  }
}
