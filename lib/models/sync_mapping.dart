/// 一组「名称 → 目标目录」的映射（Android 接收端只保存目标目录）。
///
/// - [name]：配对键 + 显示名，两端必须一致才能对上（如「中文」）。
/// - [destPath]：Android 接收端的绝对目录。
class SyncMapping {
  final String id;
  final String name;
  final String destPath;

  const SyncMapping({
    required this.id,
    required this.name,
    required this.destPath,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'destPath': destPath,
      };

  factory SyncMapping.fromJson(Map<String, dynamic> j) => SyncMapping(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '',
        destPath: j['destPath'] as String? ?? '',
      );
}
