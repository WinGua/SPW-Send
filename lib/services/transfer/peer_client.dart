import 'dart:convert';
import 'dart:io';

import '../../core/constants.dart';
import '../../core/utils.dart';
import '../../models/device_info.dart';
import '../../models/file_entry.dart';
import '../../storage/sync_log_repository.dart';
import '../local_index_service.dart';
import 'part_store.dart';

/// 本机向电脑端发起的 HTTP 客户端：配对、握手、索引、拉取文件与日志推送。
///
/// 索引/日志使用独立短连接；连续拉取文件时复用一个 [HttpClient]，
/// 但每个请求仍设置 `persistentConnection = false` 配合服务端 `Connection: close`。
class PeerClient {
  final String address;
  final int port;

  HttpClient? _http;

  PeerClient({required this.address, required this.port});

  HttpClient _create() {
    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 8);
    client.idleTimeout = const Duration(seconds: 10);
    return client;
  }

  HttpClient _ensure() => _http ??= _create();

  /// 关闭连续拉取文件期间复用的连接池。
  void close() {
    _http?.close(force: true);
    _http = null;
  }

  Uri _uri(String path, [Map<String, String>? query]) => Uri(
        scheme: 'http',
        host: address,
        port: port,
        path: path,
        queryParameters: query,
      );

  void _auth(HttpClientRequest req, String token) {
    req.headers.set('X-SpwSend-Proto', '${AppConstants.protoVersion}');
    req.headers.set('X-SpwSend-Token', token);
  }

  /// POST /v2/pair，成功返回会话 token。
  Future<PairResult?> pair(Map<String, dynamic> selfInfo) async {
    final http = _create();
    try {
      final req = await http.postUrl(_uri('/v2/pair'));
      req.headers.contentType =
          ContentType('application', 'json', charset: 'utf-8');
      req.headers.set('X-SpwSend-Proto', '${AppConstants.protoVersion}');
      req.persistentConnection = false;
      req.write(jsonEncode(selfInfo));
      final res = await req.close();
      final text = await res.transform(utf8.decoder).join();
      if (res.statusCode != 200) return null;
      final j = decodeJsonObject(text);
      if (j == null || j['ok'] != true) return null;
      return PairResult(token: j['token'] as String? ?? '');
    } catch (_) {
      return null;
    } finally {
      http.close(force: true);
    }
  }

  /// POST /v2/disconnect，通知对端本机已断开。
  Future<void> disconnect(String token, {String reason = 'user'}) async {
    final http = _create();
    try {
      final req = await http.postUrl(_uri('/v2/disconnect'));
      req.headers.contentType =
          ContentType('application', 'json', charset: 'utf-8');
      _auth(req, token);
      req.persistentConnection = false;
      req.write(jsonEncode({'reason': reason}));
      final res = await req.close();
      await res.drain<void>();
    } catch (_) {
      // 对端离线时静默忽略。
    } finally {
      http.close(force: true);
    }
  }

  /// GET /v2/index，读取电脑端某个映射的源索引；失败返回 null。
  Future<List<FileEntry>?> fetchIndex(String token, String mappingName) async {
    final http = _create();
    try {
      final req = await http.getUrl(_uri('/v2/index', {'name': mappingName}));
      _auth(req, token);
      req.persistentConnection = false;
      final res = await req.close();
      final text = await res.transform(utf8.decoder).join();
      if (res.statusCode == 401) {
        throw HttpException('UNAUTHORIZED');
      }
      if (res.statusCode != 200) return null;
      final j = decodeJsonObject(text);
      final files = j?['files'];
      if (files is! List) return null;
      return files
          .whereType<Map>()
          .map((e) {
            final m = Map<String, dynamic>.from(e);
            return FileEntry(
              relativePath: m['p'] as String? ?? '',
              size: (m['s'] as num?)?.toInt() ?? 0,
              mtimeMs: (m['m'] as num?)?.toInt() ?? 0,
            );
          })
          .where((e) => e.relativePath.isNotEmpty)
          .toList();
    } on HttpException {
      rethrow;
    } catch (_) {
      return null;
    } finally {
      http.close(force: true);
    }
  }

  /// GET /v2/file，按本地 `.part` 进度续传并落盘到 [root]。
  Future<void> downloadFile({
    required String token,
    required String mappingName,
    required String root,
    required FileEntry entry,
    required void Function(int received, int total) onProgress,
  }) async {
    final target = LocalIndexService.safeFile(root, entry.relativePath);
    if (target == null) {
      throw HttpException('路径非法');
    }

    final offset = await PartStore.currentOffset(root, entry.relativePath, entry.size);
    if (offset >= entry.size) {
      // 分片已完整但尚未改名（例如上次在改名前后退出），这里补一次落盘。
      final part = PartStore.partFile(root, entry.relativePath);
      if (await part.exists()) {
        await target.parent.create(recursive: true);
        await part.rename(target.path);
      }
      try {
        await PartStore.metaFile(root, entry.relativePath).delete();
      } catch (_) {}
      onProgress(entry.size, entry.size);
      return;
    }

    await PartStore.writeMeta(
      root,
      entry.relativePath,
      PartMeta(
        path: entry.relativePath,
        size: entry.size,
        mtime: entry.mtimeMs,
      ),
    );
    final part = PartStore.partFile(root, entry.relativePath);
    await part.parent.create(recursive: true);

    final http = _ensure();
    final req = await http.getUrl(
      _uri('/v2/file', {'name': mappingName, 'path': entry.relativePath}),
    );
    _auth(req, token);
    req.persistentConnection = false;
    if (offset > 0) {
      req.headers.set(HttpHeaders.rangeHeader, 'bytes=$offset-');
    }
    final res = await req.close();
    if (res.statusCode != 200 && res.statusCode != 206) {
      await res.drain<void>();
      throw HttpException('下载失败: HTTP ${res.statusCode}');
    }

    final sink = part.openWrite(mode: FileMode.append);
    var received = offset;
    onProgress(received, entry.size);
    try {
      await for (final chunk in res) {
        sink.add(chunk);
        received += chunk.length;
        onProgress(received, entry.size);
      }
      await sink.flush();
      await sink.close();
    } catch (_) {
      await sink.close();
      rethrow;
    }

    if (received != entry.size) {
      throw HttpException('文件大小不一致');
    }

    await target.parent.create(recursive: true);
    await part.rename(target.path);
    try {
      await target.setLastModified(
        DateTime.fromMillisecondsSinceEpoch(entry.mtimeMs),
      );
    } catch (_) {
      // 部分文件系统不支持设置 mtime，不影响落盘结果。
    }
    try {
      await PartStore.metaFile(root, entry.relativePath).delete();
    } catch (_) {
      // 元数据文件可能已被清理。
    }
    onProgress(entry.size, entry.size);
  }

  /// POST /v2/log，把本机发起同步的日志推送给电脑端留存。
  Future<void> sendSyncLog(String token, SyncLogEntry entry) async {
    final http = _create();
    try {
      final req = await http.postUrl(_uri('/v2/log'));
      req.headers.contentType =
          ContentType('application', 'json', charset: 'utf-8');
      _auth(req, token);
      req.persistentConnection = false;
      req.write(jsonEncode(entry.toJson()));
      final res = await req.close();
      await res.drain<void>();
    } catch (_) {
      // 日志推送失败不影响同步结果。
    } finally {
      http.close(force: true);
    }
  }
}
