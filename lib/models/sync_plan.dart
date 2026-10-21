import 'file_entry.dart';

/// 一次对比得到的待同步文件。
class SyncPlan {
  final String mappingId;
  final String mappingName;

  /// 电脑端存在、本机不存在的文件。
  final List<FileEntry> toSend;

  const SyncPlan({
    required this.mappingId,
    required this.mappingName,
    required this.toSend,
  });

  List<FileEntry> get newOnly => toSend;

  int get totalBytes => toSend.fold(0, (sum, e) => sum + e.size);

  bool get isEmpty => toSend.isEmpty;
}
