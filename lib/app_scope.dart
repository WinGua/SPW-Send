import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'core/constants.dart';
import 'core/utils.dart';
import 'models/device_info.dart';
import 'models/sync_mapping.dart';
import 'models/sync_plan.dart';
import 'services/discovery_service.dart';
import 'services/receiver_sync_service.dart';
import 'services/transfer/peer_client.dart';
import 'services/transfer/receiver_server.dart';
import 'storage/config_repository.dart';
import 'storage/sync_log_repository.dart';

/// 全局服务容器：配置、同步日志、设备发现、接收服务与会话 token。
class AppScope extends ChangeNotifier {
  final ConfigRepository config = ConfigRepository();
  final SyncLogRepository syncLog = SyncLogRepository();

  DiscoveryService? discovery;
  ReceiverServer? server;

  /// 本次运行会话标识（重启后变化）。
  final String session = _newSessionId();

  /// 对端设备 id -> 对端下发给本机的 token（用于主动断开等请求）。
  final Map<String, String> _peerTokens = {};

  /// 首次配对确认回调（由 UI 注入；为空时拒绝未知设备）。
  Future<bool> Function(PeerDevice requester)? pairConfirmer;

  AppScope() {
    // 配置与日志变化时一并刷新 UI（Shell 统一监听本容器）。
    config.addListener(notifyListeners);
    syncLog.addListener(notifyListeners);
    syncLog.load();
  }

  /// 描述自身设备的配对报文。
  Map<String, dynamic> selfDescribe() {
    final cfg = config.config;
    return {
      ...PeerDevice.describe(
        id: cfg.deviceId,
        name: cfg.deviceName,
        app: AppConstants.appTag,
        appVersion: AppConstants.currentVersion,
        proto: AppConstants.protoVersion,
      ),
      'port': server?.port ?? cfg.transferPort,
      'session': session,
    };
  }

  /// 依据当前配置启动（或重启）后台服务。
  Future<void> startServices() async {
    await stopServices();
    final cfg = config.config;

    server = ReceiverServer(
      preferredPort: cfg.transferPort,
      config: config,
      session: session,
      onPairRequest: _onPairRequest,
      onPeerConnected: _onPeerConnected,
      onSyncLog: _onSyncLog,
      onDisconnect: _onDisconnect,
    );
    try {
      await server!.start();
    } catch (e) {
      // 端口被占用等异常不应阻断 UI 启动；无接收服务时不再广播发现。
      debugPrint('[SPW-Send] 接收服务启动失败: $e');
      server = null;
      notifyListeners();
      return;
    }

    discovery = DiscoveryService(
      deviceId: cfg.deviceId,
      deviceName: cfg.deviceName,
      transferPort: server!.port,
      session: session,
    );
    discovery!.addListener(notifyListeners);
    await discovery!.start();

    notifyListeners();
  }

  Future<void> stopServices() async {
    discovery?.removeListener(notifyListeners);
    await discovery?.stop();
    await server?.stop();
    discovery = null;
    server = null;
    _peerTokens.clear();
    notifyListeners();
  }

  /// 主动连接对端（配对）；成功返回 true。
  Future<bool> connectTo(PeerDevice device) async {
    final client = PeerClient(address: device.address, port: device.port);
    // 附带本机签发的 token，让电脑端在本次配对后也能反向调用本机。
    final self = selfDescribe();
    final ownToken = server?.issueTokenFor(device.id) ?? '';
    if (ownToken.isNotEmpty) self['token'] = ownToken;
    final result = await client.pair(self);
    if (result == null || result.token.isEmpty) return false;
    _peerTokens[device.id] = result.token;
    discovery?.addScanned(device.copyWith(connected: true), connected: true);
    notifyListeners();
    return true;
  }

  /// 确保本机持有可调用电脑端的 token；必要时补一次配对。
  Future<String?> requirePeerToken(PeerDevice device) async {
    final existing = _peerTokens[device.id];
    if (existing != null && existing.isNotEmpty) return existing;
    final ok = await connectTo(device);
    if (!ok) return null;
    return _peerTokens[device.id];
  }

  /// 安卓端发起对比：拉取电脑端索引并与本地目标目录比较。
  Future<SyncPlan> compareMapping(PeerDevice device, SyncMapping mapping) async {
    var token = await requirePeerToken(device);
    if (token == null) throw Exception('尚未连接「${device.name}」');
    try {
      return await ReceiverSyncService.compare(
        peer: device,
        mapping: mapping,
        token: token,
      );
    } on HttpException catch (e) {
      // 电脑端重启后旧 token 失效，重新配对后再试一次。
      if (e.message != 'UNAUTHORIZED') rethrow;
      _peerTokens.remove(device.id);
      token = await requirePeerToken(device);
      if (token == null) throw Exception('会话已失效，请重新连接「${device.name}」');
      return ReceiverSyncService.compare(
        peer: device,
        mapping: mapping,
        token: token,
      );
    }
  }

  /// 安卓端发起同步：从电脑端拉取新增文件，并记录/推送同步日志。
  Future<ReceiverSyncOutcome> syncMapping({
    required PeerDevice device,
    required SyncMapping mapping,
    required SyncPlan plan,
    required void Function(int done, int total, String path) onProgress,
    required bool Function() cancelled,
    void Function(int transferredBytes)? onBytes,
  }) async {
    final token = await requirePeerToken(device);
    if (token == null) throw Exception('尚未连接「${device.name}」');
    final outcome = await ReceiverSyncService.sync(
      peer: device,
      mapping: mapping,
      token: token,
      files: plan.toSend,
      onProgress: (done, total, current) {
        onProgress(done, total, current.relativePath);
      },
      cancelled: cancelled,
      onBytes: onBytes,
    );
    await syncLog.add(outcome.log);
    return outcome;
  }

  /// 主动断开对端：先通知对端，再更新本地状态。
  Future<void> disconnectFrom(PeerDevice device) async {
    final token = _peerTokens.remove(device.id);
    if (token != null && token.isNotEmpty) {
      final client = PeerClient(address: device.address, port: device.port);
      await client.disconnect(token);
    }
    server?.revokeFor(device.id);
    discovery?.markDisconnected(device.id);
    notifyListeners();
  }

  Future<bool> _onPairRequest(PeerDevice requester) async {
    final confirmer = pairConfirmer;
    if (confirmer == null) {
      debugPrint('[SPW-Send] 无 UI 可确认配对，拒绝 ${requester.name}');
      return false;
    }
    return confirmer(requester);
  }

  void _onPeerConnected(PeerDevice peer) {
    discovery?.addScanned(peer, connected: true);
    notifyListeners();
  }

  Future<void> _onSyncLog(SyncLogEntry entry) => syncLog.add(entry);

  void _onDisconnect(String deviceId) {
    if (deviceId.isEmpty) return;
    discovery?.removeDevice(deviceId);
    _peerTokens.remove(deviceId);
    notifyListeners();
  }

  static String _newSessionId() {
    final rnd = Random.secure();
    final bytes = List<int>.generate(16, (_) => rnd.nextInt(256));
    return hexEncode(bytes);
  }
}

/// 全局实例，在 `main()` 中初始化。
late AppScope scope;
