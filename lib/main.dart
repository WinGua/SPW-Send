import 'package:flutter/widgets.dart';

import 'app.dart';
import 'app_scope.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  scope = AppScope();
  await scope.config.load();
  await scope.startServices();

  runApp(const SpwSendApp());
}
