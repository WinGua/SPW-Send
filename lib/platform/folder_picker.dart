import 'package:file_picker/file_picker.dart';

/// 目录选择器：调用系统 SAF 文件夹选择器，返回可写的真实绝对路径。
class FolderPicker {
  FolderPicker._();

  static Future<String?> pick() async {
    try {
      final result = await FilePicker.platform.getDirectoryPath();
      if (result == null || result.isEmpty) return null;
      return result;
    } catch (_) {
      return null;
    }
  }
}
