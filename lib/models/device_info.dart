/// 局域网中发现的远端设备（协议 v2：Android 只展示 role == 'pc' 的设备）。
class PeerDevice {
  final String id;
  final String name;

  /// `pc` | `android`
  final String role;

  /// 应用标识，如 `spw-send-mod` / `spw-send-android`。
  final String app;
  final String appVersion;

  /// IP 地址。
  final String address;

  /// HTTP 服务端口。
  final int port;

  /// 对端本次会话标识。
  final String session;
  final DateTime lastSeen;

  /// 是否已通过配对建立连接。
  final bool connected;

  const PeerDevice({
    required this.id,
    required this.name,
    required this.role,
    required this.app,
    required this.appVersion,
    required this.address,
    required this.port,
    required this.session,
    required this.lastSeen,
    this.connected = false,
  });

  /// 解析 UDP hello 报文。
  factory PeerDevice.fromHello(Map<String, dynamic> e, String sourceAddress) {
    return PeerDevice(
      id: e['id'] as String? ?? '',
      name: e['name'] as String? ?? '未知设备',
      role: e['role'] as String? ?? 'pc',
      app: e['app'] as String? ?? '',
      appVersion: e['appVersion'] as String? ?? '',
      address: sourceAddress,
      port: (e['port'] as num?)?.toInt() ?? 54318,
      session: e['session'] as String? ?? '',
      lastSeen: DateTime.now(),
    );
  }

  /// 描述自身设备（用于配对请求）。
  static Map<String, dynamic> describe({
    required String id,
    required String name,
    required String app,
    required String appVersion,
    required int proto,
  }) =>
      {
        'id': id,
        'name': name,
        'role': 'android',
        'app': app,
        'appVersion': appVersion,
        'proto': proto,
      };

  PeerDevice copyWith({
    bool? connected,
    DateTime? lastSeen,
    String? name,
    int? port,
    String? session,
  }) =>
      PeerDevice(
        id: id,
        name: name ?? this.name,
        role: role,
        app: app,
        appVersion: appVersion,
        address: address,
        port: port ?? this.port,
        session: session ?? this.session,
        lastSeen: lastSeen ?? this.lastSeen,
        connected: connected ?? this.connected,
      );
}

/// 配对成功后拿到的会话 token。
class PairResult {
  final String token;

  const PairResult({required this.token});
}
