/// SPW-Send Android 接收端全局常量与协议约定。
class AppConstants {
  AppConstants._();

  /// 局域网 UDP 发现端口（协议 v2）。
  static const int discoveryPort = 54317;

  /// HTTP 传输默认端口（协议 v2）。
  static const int transferPort = 54318;

  /// 协议版本。
  static const int protoVersion = 2;

  /// 本端应用标识（写入 hello / pair 报文）。
  static const String appTag = 'spw-send-android';

  /// 当前应用版本（与 pubspec.yaml 的 version 保持一致）。
  static const String currentVersion = '0.1.1';

  /// 可接受的最低对端版本。
  static const int minPeer = 1;

  /// 配置文件文件名。
  static const String configFileName = 'spw_send_config.json';

  /// 同步日志文件名。
  static const String logFileName = 'spw_send_logs.json';

  /// 分片目录名（位于每个映射目标根目录下）。
  static const String partDirName = '.spw-send-part';

  /// 项目仓库地址。
  static const String repoUrl = 'https://github.com/WinGua/SPW-Send';

  /// GitHub Releases 页面地址。
  static const String releasesUrl = 'https://github.com/WinGua/SPW-Send/releases';

  /// GitHub Releases 最新版本查询接口。
  static const String releasesApiUrl =
      'https://api.github.com/repos/WinGua/SPW-Send/releases/latest';
}
