import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import '../core/constants.dart';
import '../models/device_info.dart';

/// 局域网设备发现服务（协议 v2，UDP 广播）。
///
/// - 2 秒广播一次 hello，12 秒无心跳视为离线
/// - 只保留 `role == 'pc'` 且 `proto == 2` 的设备
/// - 向 255.255.255.255 与每个网卡的定向广播地址各发一份
class DiscoveryService extends ChangeNotifier {
  final String deviceId;
  final String deviceName;
  final int transferPort;
  final String session;

  final Map<String, PeerDevice> _devices = {};
  RawDatagramSocket? _socket;
  Timer? _announceTimer;
  Timer? _cleanupTimer;

  DiscoveryService({
    required this.deviceId,
    required this.deviceName,
    required this.transferPort,
    required this.session,
  });

  /// 当前发现的设备列表，按名称排序。
  List<PeerDevice> get devices =>
      _devices.values.toList()..sort((a, b) => a.name.compareTo(b.name));

  bool get running => _socket != null;

  /// 主动连接 / 被配对后把设备加入列表并标记为已连接。
  ///
  /// 已由 UDP 发现过的设备保留其真实地址与端口，避免配对报文中的默认端口覆盖。
  void addScanned(PeerDevice d, {bool connected = false}) {
    final existed = _devices[d.id];
    final keep = (existed?.connected ?? false) || connected;
    final base = existed ?? d;
    _devices[d.id] = keep ? base.copyWith(connected: true) : base;
    notifyListeners();
  }

  /// 断开后仅切换连接状态，保留设备在列表中。
  void markDisconnected(String id) {
    final d = _devices[id];
    if (d == null || !d.connected) return;
    _devices[id] = d.copyWith(connected: false);
    notifyListeners();
  }

  /// 对端主动断开时立即移除。
  void removeDevice(String id) {
    if (_devices.remove(id) != null) notifyListeners();
  }

  Future<void> start() async {
    if (_socket != null) return;
    try {
      _socket = await RawDatagramSocket.bind(
        InternetAddress.anyIPv4,
        AppConstants.discoveryPort,
        reuseAddress: true,
        reusePort: true,
      );
      _socket!.broadcastEnabled = true;
      _socket!.listen(_onPacket);
      _announce();
      _announceTimer =
          Timer.periodic(const Duration(seconds: 2), (_) => _announce());
      _cleanupTimer = Timer.periodic(const Duration(seconds: 5), (_) {
        final now = DateTime.now();
        final expired = _devices.values
            .where((d) =>
                now.difference(d.lastSeen).inMilliseconds > 12000 &&
                !d.connected)
            .map((d) => d.id)
            .toList();
        if (expired.isNotEmpty) {
          for (final id in expired) {
            _devices.remove(id);
          }
          notifyListeners();
        }
      });
    } catch (e) {
      debugPrint('发现服务启动失败: $e');
    }
  }

  Future<void> stop() async {
    _announceTimer?.cancel();
    _announceTimer = null;
    _cleanupTimer?.cancel();
    _cleanupTimer = null;
    try {
      _socket?.close();
    } catch (_) {}
    _socket = null;
    _devices.clear();
    notifyListeners();
  }

  void _onPacket(RawSocketEvent event) {
    if (event != RawSocketEvent.read) return;
    final datagram = _socket?.receive();
    if (datagram == null) return;
    try {
      final text = utf8.decode(datagram.data);
      final json = jsonDecode(text) as Map<String, dynamic>;
      if (json['t'] != 'hello') return;
      if ((json['proto'] as num?)?.toInt() != AppConstants.protoVersion) return;
      if (json['role'] != 'pc') return;
      final peer = PeerDevice.fromHello(json, datagram.address.address);
      if (peer.id.isEmpty || peer.id == deviceId) return;
      final existed = _devices[peer.id];
      _devices[peer.id] = (existed != null && existed.connected)
          ? peer.copyWith(connected: true)
          : peer;
      notifyListeners();
    } catch (_) {
      // 忽略无法解析的报文。
    }
  }

  Future<void> _announce() async {
    final socket = _socket;
    if (socket == null) return;
    final payload = utf8.encode(jsonEncode({
      't': 'hello',
      'proto': AppConstants.protoVersion,
      'id': deviceId,
      'name': deviceName,
      'role': 'android',
      'app': AppConstants.appTag,
      'appVersion': AppConstants.currentVersion,
      'port': transferPort,
      'session': session,
    }));

    final targets = <String>{'255.255.255.255'};
    try {
      for (final nic
          in await NetworkInterface.list(type: InternetAddressType.IPv4)) {
        for (final addr in nic.addresses) {
          final ip = addr.address;
          if (ip == '127.0.0.1') continue;
          final parts = ip.split('.');
          if (parts.length == 4) {
            targets.add('${parts[0]}.${parts[1]}.${parts[2]}.255');
          }
        }
      }
    } catch (_) {}

    for (final target in targets) {
      try {
        socket.send(payload, InternetAddress(target), AppConstants.discoveryPort);
      } catch (_) {}
    }
  }

  @override
  void dispose() {
    stop();
    super.dispose();
  }
}
