import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../features/limits/limits_providers.dart';
import 'router.dart';

/// Root widget of FocusMeme.
///
/// Uses stock Google Material 3 (`ColorScheme.fromSeed`) following the system
/// light/dark mode during M0-M8. Custom dark/AMOLED theming is deferred to M9.
class FocusMemeApp extends ConsumerWidget {
  const FocusMemeApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Keep native blocker storage synced with live Firestore limit updates.
    ref.watch(limitsNativeSyncProvider);
    final router = ref.watch(routerProvider);

    return MaterialApp.router(
      title: 'FocusMeme',
      themeMode: ThemeMode.system,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
          brightness: Brightness.dark,
        ),
      ),
      routerConfig: router,
    );
  }
}
