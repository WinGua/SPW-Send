import 'package:fluent_ui/fluent_ui.dart';

/// 深色磨砂玻璃主题（背景由 shell 的渐变层提供）。
class AppTheme {
  AppTheme._();

  static FluentThemeData dark() => FluentThemeData(
        brightness: Brightness.dark,
        accentColor: Colors.blue,
        scaffoldBackgroundColor: const Color(0x00000000),
      );
}
