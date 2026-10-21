import 'package:fluent_ui/fluent_ui.dart';

import 'ui/shell.dart';
import 'ui/theme.dart';

class SpwSendApp extends StatelessWidget {
  const SpwSendApp({super.key});

  @override
  Widget build(BuildContext context) {
    return FluentApp(
      title: 'SPW-Send',
      theme: AppTheme.dark(),
      debugShowCheckedModeBanner: false,
      home: const Shell(),
    );
  }
}
