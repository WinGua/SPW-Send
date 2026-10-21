import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../core/constants.dart';
import '../core/utils.dart';
import '../models/sync_mapping.dart';

/// 应用配置，持久化为本地 JSON 文件。
class AppConfig {
  String deviceId;
  String deviceName;
  int transferPort;

  /// 多组映射。两端通过 [SyncMapping.name] 配对。
  List<SyncMapping> mappings;

  /// 已信任的设备 id 白名单（首次配对确认后写入）。
  List<String> trustedPeers;

  AppConfig({
    required this.deviceId,
    required this.deviceName,
    required this.transferPort,
    required this.mappings,
    required this.trustedPeers,
  });

  factory AppConfig.defaults() => AppConfig(
        deviceId: newUuid(),
        deviceName: '我的安卓设备',
        transferPort: AppConstants.transferPort,
        mappings: [],
        trustedPeers: [],
      );

  Map<String, dynamic> toJson() => {
        'deviceId': deviceId,
        'deviceName': deviceName,
        'transferPort': transferPort,
        'mappings': mappings.map((m) => m.toJson()).toList(),
        'trustedPeers': trustedPeers,
      };

  factory AppConfig.fromJson(Map<String, dynamic> j) {
    final id = j['deviceId'];
    final name = j['deviceName'];
    return AppConfig(
      deviceId: id is String && id.isNotEmpty ? id : newUuid(),
      deviceName: name is String && name.trim().isNotEmpty ? name : '我的安卓设备',
      transferPort:
          (j['transferPort'] as num?)?.toInt() ?? AppConstants.transferPort,
      mappings: (j['mappings'] as List<dynamic>? ?? [])
          .map((e) => SyncMapping.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
      trustedPeers: (j['trustedPeers'] as List<dynamic>? ?? [])
          .map((e) => e.toString())
          .where((e) => e.isNotEmpty)
          .toList(),
    );
  }
}

/// 配置仓库：负责读取 / 写入本地 JSON 配置，并在变更时通知 UI。
class ConfigRepository extends ChangeNotifier {
  AppConfig _config = AppConfig.defaults();

  AppConfig get config => _config;

  Future<File> _file() async {
    final dir = await getApplicationSupportDirectory();
    return File(p.join(dir.path, AppConstants.configFileName));
  }

  Future<void> load() async {
    try {
      final file = await _file();
      if (await file.exists()) {
        final raw = await file.readAsString();
        _config = AppConfig.fromJson(jsonDecode(raw) as Map<String, dynamic>);
      }
    } catch (_) {
      // 配置损坏时回退默认值。
    }
    notifyListeners();
  }

  Future<void> save() async {
    try {
      final file = await _file();
      await file.parent.create(recursive: true);
      await file.writeAsString(jsonEncode(_config.toJson()), flush: true);
    } catch (e) {
      debugPrint('保存配置失败: $e');
    }
  }

  void setDeviceName(String name) {
    _config.deviceName = name.trim().isEmpty ? _config.deviceName : name.trim();
    notifyListeners();
    save();
  }

  void setTransferPort(int port) {
    _config.transferPort = port;
    notifyListeners();
    save();
  }

  void addMapping(SyncMapping mapping) {
    _config.mappings.add(mapping);
    notifyListeners();
    save();
  }

  void updateMapping(SyncMapping mapping) {
    final i = _config.mappings.indexWhere((m) => m.id == mapping.id);
    if (i >= 0) _config.mappings[i] = mapping;
    notifyListeners();
    save();
  }

  void removeMapping(String id) {
    _config.mappings.removeWhere((m) => m.id == id);
    notifyListeners();
    save();
  }

  bool isTrusted(String deviceId) => _config.trustedPeers.contains(deviceId);

  void trustPeer(String deviceId) {
    if (deviceId.isEmpty || _config.trustedPeers.contains(deviceId)) return;
    _config.trustedPeers.add(deviceId);
    notifyListeners();
    save();
  }

  void untrustPeer(String deviceId) {
    if (_config.trustedPeers.remove(deviceId)) {
      notifyListeners();
      save();
    }
  }
}
