import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../core/constants.dart';

/// 单个文件的失败记录（对齐协议 `/v2/log` 的 failures 字段）。
class SyncFailure {
  final String path;
  final String reason;

  const SyncFailure({required this.path, required this.reason});

  Map<String, dynamic> toJson() => {'p': path, 'reason': reason};

  factory SyncFailure.fromJson(Map<String, dynamic> j) => SyncFailure(
        path: j['p']?.toString() ?? '',
        reason: j['reason']?.toString() ?? '',
      );
}

/// 一条同步任务日志，字段与协议 `/v2/log` 一致。
class SyncLogEntry {
  final String task;
  final int startedAt;
  final int finishedAt;
  final int total;
  final int success;
  final int failed;
  final int bytes;
  final List<SyncFailure> failures;

  const SyncLogEntry({
    required this.task,
    required this.startedAt,
    required this.finishedAt,
    required this.total,
    required this.success,
    required this.failed,
    required this.bytes,
    this.failures = const [],
  });

  Map<String, dynamic> toJson() => {
        'task': task,
        'startedAt': startedAt,
        'finishedAt': finishedAt,
        'total': total,
        'success': success,
        'failed': failed,
        'bytes': bytes,
        'failures': failures.map((e) => e.toJson()).toList(),
      };

  factory SyncLogEntry.fromJson(Map<String, dynamic> j) => SyncLogEntry(
        task: j['task']?.toString() ?? '',
        startedAt: _int(j['startedAt']),
        finishedAt: _int(j['finishedAt']),
        total: _int(j['total']),
        success: _int(j['success']),
        failed: _int(j['failed']),
        bytes: _int(j['bytes']),
        failures: (j['failures'] as List<dynamic>? ?? const [])
            .whereType<Map>()
            .map((e) => SyncFailure.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );

  static int _int(Object? v) => v is num ? v.toInt() : 0;
}

/// 同步日志仓库：持久化为本地 JSON，自动清理超 7 天的记录。
class SyncLogRepository extends ChangeNotifier {
  static const Duration _retention = Duration(days: 7);

  final List<SyncLogEntry> _entries = [];

  /// 按时间倒序（最新在前）。
  List<SyncLogEntry> get entries => List.unmodifiable(_entries);

  Future<File> _file() async {
    final dir = await getApplicationSupportDirectory();
    return File(p.join(dir.path, AppConstants.logFileName));
  }

  Future<void> load() async {
    try {
      final file = await _file();
      if (await file.exists()) {
        final raw = await file.readAsString();
        final list = (jsonDecode(raw) as List<dynamic>)
            .map((e) => SyncLogEntry.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList();
        _entries
          ..clear()
          ..addAll(list);
      }
    } catch (_) {
      // 日志损坏则从空开始。
    }
    prune();
    notifyListeners();
  }

  Future<void> _save() async {
    try {
      final file = await _file();
      await file.parent.create(recursive: true);
      await file.writeAsString(
        jsonEncode(_entries.map((e) => e.toJson()).toList()),
        flush: true,
      );
    } catch (e) {
      debugPrint('保存同步日志失败: $e');
    }
  }

  Future<void> add(SyncLogEntry entry) async {
    _entries.insert(0, entry);
    prune();
    notifyListeners();
    await _save();
  }

  Future<void> clear() async {
    _entries.clear();
    notifyListeners();
    await _save();
  }

  /// 移除超过保留期的记录。
  void prune() {
    final cutoff = DateTime.now().subtract(_retention).millisecondsSinceEpoch;
    _entries.removeWhere((e) => e.finishedAt < cutoff);
  }
}
