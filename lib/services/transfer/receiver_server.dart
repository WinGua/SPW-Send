import 'dart:convert';
import 'dart:io';
import 'dart:math';

import '../../core/constants.dart';
import '../../core/utils.dart';
import '../../models/device_info.dart';
import '../../models/sync_mapping.dart';
import '../../storage/config_repository.dart';
import '../../storage/sync_log_repository.dart';
import '../local_index_service.dart';
import 'part_store.dart';

/// Android 接收端 HTTP 服务，实现协议 v2 的接收侧端点：
///
/// - GET  /v2/hello
/// - POST /v2/pair
/// - GET  /v2/index     查看目标目录索引
/// - GET  /v2/resume    查询续传偏移
/// - POST /v2/upload    接收文件（.part + 偏移续传 + SHA-256）
/// - POST /v2/log       接收对端同步日志
/// - POST /v2/disconnect
class ReceiverServer {
  final int preferredPort;
  final ConfigRepository config;
  final String session;

  /// 首次配对确认回调：返回 true 表示允许。
  final Future<bool> Function(PeerDevice requester) onPairRequest;

  /// 配对成功 / 对端连接后回调（用于更新设备列表状态）。
  final void Function(PeerDevice peer) onPeerConnected;

  /// 收到对端推送的同步日志。
  final Future<void> Function(SyncLogEntry entry) onSyncLog;

  /// 对端主动断开。
  final void Function(String deviceId) onDisconnect;

  HttpServer? _server;
  final Map<String, String> _tokens = {}; // token -> deviceId
  bool _uploading = false;

  ReceiverServer({
    required this.preferredPort,
    required this.config,
    required this.session,
    required this.onPairRequest,
    required this.onPeerConnected,
    required this.onSyncLog,
    required this.onDisconnect,
  });

  /// 实际监听端口（端口被占用时自动回退到临时端口）。
  int get port => _server?.port ?? preferredPort;

  bool get running => _server != null;

  /// 为主动配对签发一个供对端反向调用本机的 token。
  String issueTokenFor(String deviceId) {
    if (deviceId.isEmpty) return '';
    final token = _newToken();
    _tokens[token] = deviceId;
    return token;
  }

  /// 主动断开时撤销本机签发给该设备的全部 token。
  void revokeFor(String deviceId) {
    _tokens.removeWhere((_, id) => id == deviceId);
  }

  Future<void> start() async {
    if (_server != null) return;
    try {
      _server = await HttpServer.bind(InternetAddress.anyIPv4, preferredPort);
    } catch (_) {
      // 配置端口被占用时退回系统分配端口，并在 hello 中声明实际端口。
      _server = await HttpServer.bind(InternetAddress.anyIPv4, 0);
    }
    _server!.listen(_handle);
    _cleanupParts();
  }

  Future<void> stop() async {
    try {
      await _server?.close(force: true);
    } catch (_) {}
    _server = null;
    _tokens.clear();
  }

  Future<void> _cleanupParts() async {
    for (final m in config.config.mappings) {
      if (m.destPath.isEmpty) continue;
      await PartStore.cleanup(m.destPath);
    }
  }

  Future<void> _handle(HttpRequest req) async {
    try {
      final path = req.uri.path;
      if (path == '/v2/hello') {
        _hello(req);
        return;
      }
      if (path == '/v2/pair') {
        await _pair(req);
        return;
      }

      // 除 hello / pair 外，缺 token 一律 401（先于协议版本检查）。
      final token = req.headers.value('X-SpwSend-Token');
      if (token == null || !_tokens.containsKey(token)) {
        _error(req, 401, 'UNAUTHORIZED', '缺少或错误的会话 token');
        return;
      }
      final proto = int.tryParse(req.headers.value('X-SpwSend-Proto') ?? '');
      if (proto != null && proto != AppConstants.protoVersion) {
        _error(req, 426, 'PROTO_MISMATCH', '协议版本不一致');
        return;
      }

      switch (path) {
        case '/v2/index':
          await _index(req);
          return;
        case '/v2/resume':
          await _resume(req);
          return;
        case '/v2/upload':
          await _upload(req);
          return;
        case '/v2/log':
          await _log(req);
          return;
        case '/v2/disconnect':
          _disconnect(req, token);
          return;
        default:
          _error(req, 404, 'NOT_FOUND', '未知端点');
          return;
      }
    } catch (e) {
      try {
        _error(req, 500, 'IO_ERROR', '$e');
      } catch (_) {
        // 响应可能已发送（例如流式写入超时后），再次写入会抛异常，忽略即可。
      }
    }
  }

  void _hello(HttpRequest req) {
    final proto = int.tryParse(req.headers.value('X-SpwSend-Proto') ?? '');
    if (proto != null && proto != AppConstants.protoVersion) {
      _error(req, 426, 'PROTO_MISMATCH', '协议版本不一致');
      return;
    }
    final cfg = config.config;
    _json(req, {
      'proto': AppConstants.protoVersion,
      'app': AppConstants.appTag,
      'version': AppConstants.currentVersion,
      'minPeer': AppConstants.minPeer,
      'session': session,
      'device': {
        'id': cfg.deviceId,
        'name': cfg.deviceName,
        'role': 'android',
      },
    });
  }

  Future<void> _pair(HttpRequest req) async {
    final body = await _readJson(req);
    if (body == null) {
      _error(req, 400, 'BAD_REQUEST', '请求体非法');
      return;
    }
    if ((body['proto'] as num?)?.toInt() != AppConstants.protoVersion) {
      _error(req, 426, 'PROTO_MISMATCH', '协议版本不一致');
      return;
    }
    if (body['role'] != 'pc') {
      _error(req, 400, 'BAD_REQUEST', '角色不匹配');
      return;
    }
    if (_uploading) {
      _error(req, 409, 'BUSY', '已有同步任务进行中');
      return;
    }

    final remote = req.connectionInfo?.remoteAddress.address ?? '';
    final requester = PeerDevice.fromHello({
      'id': body['id'],
      'name': body['name'],
      'role': 'pc',
      'app': body['app'],
      'appVersion': body['appVersion'],
      'port': body['port'] ?? AppConstants.transferPort,
      'session': body['session'] ?? '',
    }, remote);
    if (requester.id.isEmpty) {
      _error(req, 400, 'BAD_REQUEST', '设备 id 缺失');
      return;
    }

    final trusted = config.isTrusted(requester.id);
    if (!trusted) {
      final accepted = await onPairRequest(requester);
      if (!accepted) {
        _error(req, 403, 'PAIR_REJECTED', '用户拒绝连接');
        return;
      }
      config.trustPeer(requester.id);
    }

    final token = _newToken();
    _tokens[token] = requester.id;
    onPeerConnected(requester.copyWith(connected: true));

    _json(req, {
      'ok': true,
      'token': token,
      'session': session,
      'device': {
        'id': requester.id,
        'name': requester.name,
        'role': 'pc',
      },
    });
  }

  Future<void> _index(HttpRequest req) async {
    final mapping = _mapping(req.uri.queryParameters['name'] ?? '');
    if (mapping == null) {
      _error(req, 404, 'MAPPING_NOT_FOUND', '映射不存在');
      return;
    }
    final index = await LocalIndexService.scan(mapping.destPath);
    final files = index.values.map((e) => e.toJson()).toList()
      ..sort((a, b) => (a['p'] as String).compareTo(b['p'] as String));
    _json(req, {
      'ok': true,
      'name': mapping.name,
      'files': files,
    });
  }

  Future<void> _resume(HttpRequest req) async {
    final q = req.uri.queryParameters;
    final mapping = _mapping(q['name'] ?? '');
    if (mapping == null) {
      _error(req, 404, 'MAPPING_NOT_FOUND', '映射不存在');
      return;
    }
    final rel = q['path'] ?? '';
    final size = int.tryParse(q['size'] ?? '') ?? -1;
    if (size < 0 || LocalIndexService.safeFile(mapping.destPath, rel) == null) {
      _error(req, 400, 'PATH_INVALID', '路径非法');
      return;
    }
    final offset = await PartStore.currentOffset(mapping.destPath, rel, size);
    _json(req, {'ok': true, 'offset': offset});
  }

  Future<void> _upload(HttpRequest req) async {
    if (_uploading) {
      _error(req, 409, 'BUSY', '已有同步任务进行中');
      return;
    }
    final q = req.uri.queryParameters;
    final mapping = _mapping(q['name'] ?? '');
    if (mapping == null) {
      _error(req, 404, 'MAPPING_NOT_FOUND', '映射不存在');
      return;
    }
    final rel = q['path'] ?? '';
    final target = LocalIndexService.safeFile(mapping.destPath, rel);
    if (target == null) {
      _error(req, 400, 'PATH_INVALID', '路径非法');
      return;
    }

    final offset = int.tryParse(req.headers.value('X-Offset') ?? '');
    final fileSize = int.tryParse(req.headers.value('X-File-Size') ?? '');
    final mtime = int.tryParse(req.headers.value('X-File-Mtime') ?? '');
    final sha = req.headers.value('X-Content-SHA256');
    if (offset == null || fileSize == null || mtime == null) {
      _error(req, 400, 'BAD_REQUEST', '缺少文件头');
      return;
    }
    if (offset < 0 || offset > fileSize) {
      _error(req, 416, 'RANGE_INVALID', '续传偏移非法');
      return;
    }

    _uploading = true;
    final part = PartStore.partFile(mapping.destPath, rel);
    var responded = false;
    try {
      final current = await PartStore.currentOffset(mapping.destPath, rel, fileSize);
      if (offset != current) {
        responded = true;
        _error(req, 416, 'RANGE_INVALID', '续传偏移非法');
        return;
      }

      await PartStore.writeMeta(
        mapping.destPath,
        rel,
        PartMeta(path: rel, size: fileSize, mtime: mtime, sha256: sha),
      );
      await part.parent.create(recursive: true);
      final sink = part.openWrite(mode: FileMode.append);
      try {
        await sink.addStream(req).timeout(const Duration(minutes: 10));
      } finally {
        await sink.close();
      }

      final written = await part.length();
      if (written != fileSize) {
        responded = true;
        _error(req, 409, 'INCOMPLETE', '数据不完整，已保留分片');
        return;
      }
      if (sha != null && sha.isNotEmpty) {
        final actual = await sha256File(part.path);
        if (actual.toLowerCase() != sha.toLowerCase()) {
          await PartStore.discard(mapping.destPath, rel);
          responded = true;
          _error(req, 409, 'CHECKSUM_MISMATCH', '摘要不符，已删除分片');
          return;
        }
      }

      await target.parent.create(recursive: true);
      // rename 在 Android/Linux 上是原子替换，避免先删除导致中途丢失。
      await part.rename(target.path);
      try {
        await target.setLastModified(
          DateTime.fromMillisecondsSinceEpoch(mtime),
        );
      } catch (_) {
        // 部分文件系统不支持设置 mtime，不影响落盘结果。
      }
      try {
        await PartStore.metaFile(mapping.destPath, rel).delete();
      } catch (_) {
        // 元数据文件可能已被清理。
      }
      responded = true;
      _json(req, {'ok': true, 'size': fileSize});
    } on FileSystemException catch (e) {
      responded = true;
      final code = e.osError?.errorCode;
      if (code == 28 || code == 122) {
        _error(req, 507, 'NO_SPACE', '目标空间不足');
      } else {
        _error(req, 500, 'IO_ERROR', '$e');
      }
    } catch (e) {
      responded = true;
      _error(req, 500, 'IO_ERROR', '$e');
    } finally {
      _uploading = false;
      if (!responded) {
        _error(req, 500, 'IO_ERROR', '写入失败');
      }
    }
  }

  Future<void> _log(HttpRequest req) async {
    final body = await _readJson(req);
    if (body == null) {
      _error(req, 400, 'BAD_REQUEST', '请求体非法');
      return;
    }
    final failures = <SyncFailure>[];
    final rawFailures = body['failures'];
    if (rawFailures is List) {
      for (final e in rawFailures) {
        if (e is Map) {
          failures.add(SyncFailure.fromJson(Map<String, dynamic>.from(e)));
        }
      }
    }
    final entry = SyncLogEntry(
      task: body['task'] as String? ?? '',
      startedAt: (body['startedAt'] as num?)?.toInt() ?? 0,
      finishedAt: (body['finishedAt'] as num?)?.toInt() ?? 0,
      total: (body['total'] as num?)?.toInt() ?? 0,
      success: (body['success'] as num?)?.toInt() ?? 0,
      failed: (body['failed'] as num?)?.toInt() ?? 0,
      bytes: (body['bytes'] as num?)?.toInt() ?? 0,
      failures: failures,
    );
    await onSyncLog(entry);
    _json(req, {'ok': true});
  }

  void _disconnect(HttpRequest req, String token) {
    final deviceId = _tokens.remove(token);
    _tokens.removeWhere((_, id) => id == deviceId);
    if (deviceId == null || deviceId.isEmpty) {
      _error(req, 401, 'UNAUTHORIZED', '会话不存在');
      return;
    }
    onDisconnect(deviceId);
    _json(req, {'ok': true});
  }

  SyncMapping? _mapping(String name) {
    for (final m in config.config.mappings) {
      if (m.name == name) return m;
    }
    return null;
  }

  Future<Map<String, dynamic>?> _readJson(HttpRequest req) async {
    if (req.contentLength > 1024 * 1024) return null;
    try {
      final body = await utf8.decoder.bind(req).join();
      final decoded = jsonDecode(body);
      if (decoded is Map) return Map<String, dynamic>.from(decoded);
    } catch (_) {
      // 请求体不是合法 JSON。
    }
    return null;
  }

  static String _newToken() {
    final rnd = Random.secure();
    final bytes = List<int>.generate(16, (_) => rnd.nextInt(256));
    return hexEncode(bytes);
  }

  void _json(HttpRequest req, Map<String, dynamic> body, {int status = 200}) {
    final res = req.response;
    res.statusCode = status;
    res.headers.set(HttpHeaders.connectionHeader, 'close');
    res.headers.contentType = ContentType('application', 'json', charset: 'utf-8');
    res.write(jsonEncode(body));
    // 客户端可能已断开，忽略 close 的异步错误。
    res.close().ignore();
  }

  void _error(
    HttpRequest req,
    int status,
    String code,
    String message,
  ) {
    _json(
      req,
      {'ok': false, 'code': code, 'message': message},
      status: status,
    );
  }
}
