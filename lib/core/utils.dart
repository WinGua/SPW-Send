import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:path/path.dart' as p;
import 'package:uuid/uuid.dart';

/// 生成一个 UUID（用作设备 id / 映射 id）。
String newUuid() => const Uuid().v4();

/// 是否运行在 Android 端。
bool get isAndroid => !kIsWeb && Platform.isAndroid;

/// 计算文件相对根目录的相对路径（统一使用 `/` 分隔）。
String toRelativePath(String root, String filePath) =>
    p.relative(filePath, from: root).replaceAll('\\', '/');

/// 校验子路径是否安全地落在 [root] 目录内（防路径穿越）。
bool isSafeWithin(String root, String relativePath) {
  final normalizedRoot = p.normalize(root).replaceAll('\\', '/');
  final normalizedChild =
      p.normalize(p.join(root, relativePath)).replaceAll('\\', '/');
  final prefix = normalizedRoot.endsWith('/') ? normalizedRoot : '$normalizedRoot/';
  return normalizedChild == normalizedRoot ||
      normalizedChild.startsWith(prefix);
}

/// 校验协议中的相对路径：非空、相对、无 `..`/空段/`:`、不以分片目录开头。
bool isValidRelPath(String rel) {
  if (rel.isEmpty) return false;
  if (rel.startsWith('/') || rel.startsWith('\\')) return false;
  if (rel.contains('\\')) return false;
  for (final part in rel.split('/')) {
    if (part.isEmpty || part == '.' || part == '..') return false;
    if (part.contains(':')) return false;
    if (part == '.spw-send-part') return false;
  }
  return true;
}

/// 计算字节数组的 SHA-256 小写十六进制摘要。
String sha256Hex(List<int> bytes) => sha256.convert(bytes).toString();

/// 流式计算文件的 SHA-256 小写十六进制摘要。
Future<String> sha256File(String path) async {
  final digest = await sha256.bind(File(path).openRead()).first;
  return digest.toString();
}

/// 人类可读的字节数。
String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
}

/// 十六进制编码小写。
String hexEncode(List<int> bytes) {
  final sb = StringBuffer();
  for (final b in bytes) {
    sb.write(b.toRadixString(16).padLeft(2, '0'));
  }
  return sb.toString();
}

/// 延迟释放文本控制器：弹窗退场动画期间控件仍会读取控制器，
/// 立即 dispose 会触发「used after being disposed」断言。
void disposeControllerLater(TextEditingController controller) {
  Timer(const Duration(milliseconds: 400), controller.dispose);
}

/// 将 JSON 字符串安全解码为 Map；失败返回 null。
Map<String, dynamic>? decodeJsonObject(String text) {
  try {
    final decoded = jsonDecode(text);
    if (decoded is Map) return Map<String, dynamic>.from(decoded);
  } catch (_) {}
  return null;
}
