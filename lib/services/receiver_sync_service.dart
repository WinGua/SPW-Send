import 'dart:io';

import '../models/device_info.dart';
import '../models/file_entry.dart';
import '../models/sync_mapping.dart';
import '../models/sync_plan.dart';
import '../storage/sync_log_repository.dart';
import 'local_index_service.dart';
import 'transfer/peer_client.dart';

/// 安卓端主动发起同步的结果。
class ReceiverSyncOutcome {
  final SyncLogEntry log;
  final bool cancelled;

  const ReceiverSyncOutcome({required this.log, required this.cancelled});
}

/// 安卓端主动同步：从电脑端拉取索引并下载新增文件。
///
/// 方向仍然是「电脑 → 手机」，只是由手机端发起。
class ReceiverSyncService {
  ReceiverSyncService._();

  static Future<SyncPlan> compare({
    required PeerDevice peer,
    required SyncMapping mapping,
    required String token,
  }) async {
    if (mapping.destPath.trim().isEmpty) {
      throw HttpException('映射「${mapping.name}」尚未选择目标目录');
    }
    final client = PeerClient(address: peer.address, port: peer.port);
    try {
      final source = await client.fetchIndex(token, mapping.name);
      if (source == null) {
        throw HttpException('无法获取电脑端索引，请重新连接');
      }
      final dest = await LocalIndexService.scan(mapping.destPath);
      final pending = source
          .where((e) => !dest.containsKey(e.relativePath))
          .toList()
        ..sort((a, b) => a.relativePath.compareTo(b.relativePath));
      return SyncPlan(
        mappingId: mapping.id,
        mappingName: mapping.name,
        toSend: pending,
      );
    } finally {
      client.close();
    }
  }

  static Future<ReceiverSyncOutcome> sync({
    required PeerDevice peer,
    required SyncMapping mapping,
    required String token,
    required List<FileEntry> files,
    required void Function(int done, int total, FileEntry current) onProgress,
    required bool Function() cancelled,
    void Function(int transferredBytes)? onBytes,
  }) async {
    final startedAt = DateTime.now().millisecondsSinceEpoch;
    final failures = <SyncFailure>[];
    var success = 0;
    var transferred = 0;
    var cancelledFlag = false;

    final client = PeerClient(address: peer.address, port: peer.port);
    try {
      for (final file in files) {
        if (cancelled()) {
          cancelledFlag = true;
          break;
        }
        try {
          await client.downloadFile(
            token: token,
            mappingName: mapping.name,
            root: mapping.destPath,
            entry: file,
            onProgress: (received, total) {
              onBytes?.call(transferred + received);
            },
          );
          success++;
          transferred += file.size;
          onBytes?.call(transferred);
        } catch (_) {
          if (cancelled()) {
            cancelledFlag = true;
            break;
          }
          failures.add(SyncFailure(path: file.relativePath, reason: 'IO_ERROR'));
        }
        onProgress(success + failures.length, files.length, file);
      }

      final log = SyncLogEntry(
        task: mapping.name,
        startedAt: startedAt,
        finishedAt: DateTime.now().millisecondsSinceEpoch,
        total: files.length,
        success: success,
        failed: failures.length,
        bytes: transferred,
        failures: failures,
      );
      await client.sendSyncLog(token, log);
      return ReceiverSyncOutcome(log: log, cancelled: cancelledFlag);
    } finally {
      client.close();
    }
  }
}
