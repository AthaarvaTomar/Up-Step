import 'package:firebase_core/firebase_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'app/app.dart';
import 'firebase_options.dart';

Future<void> main() async {
  // Required before calling async platform plugins (like Firebase) prior to runApp.
  WidgetsFlutterBinding.ensureInitialized();

  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );

  // ProviderScope stores all Riverpod provider states for the widget tree below it.
  runApp(const ProviderScope(child: FocusMemeApp()));
}
