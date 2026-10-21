/// 一个文件的索引条目，以「相对路径」为唯一身份标识。
class FileEntry {
  /// 相对映射根目录的路径，统一 `/` 分隔。
  final String relativePath;

  /// 文件字节数。
  final int size;

  /// 文件最后修改时间（毫秒时间戳）。
  final int mtimeMs;

  const FileEntry({
    required this.relativePath,
    required this.size,
    required this.mtimeMs,
  });

  // 网络传输使用短字段名以减小报文体积。
  Map<String, dynamic> toJson() => {
        'p': relativePath,
        's': size,
        'm': mtimeMs,
      };

  @override
  String toString() => 'FileEntry($relativePath, $size B)';
}
