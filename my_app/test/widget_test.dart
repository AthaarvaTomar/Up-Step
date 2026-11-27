import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusmeme/app/app.dart';
import 'package:focusmeme/features/limits/limits_providers.dart';
import 'package:focusmeme/native/installed_app.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  testWidgets('Bottom NavigationBar switches across all four tabs', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          installedAppsProvider.overrideWith((ref) async => const <InstalledApp>[]),
        ],
        child: const FocusMemeApp(),
      ),
    );
    await tester.pumpAndSettle();

    // Starts on the Home branch.
    expect(find.text('Home tab'), findsOneWidget);
    expect(find.byType(NavigationBar), findsOneWidget);

    await tester.tap(find.text('Focus'));
    await tester.pumpAndSettle();
    expect(find.text('Focus tab'), findsOneWidget);

    await tester.tap(find.text('Limits'));
    await tester.pumpAndSettle();
    expect(find.text('No app limits yet. Tap + to add one.'), findsOneWidget);

    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('Settings tab'), findsOneWidget);

    await tester.tap(find.text('Home'));
    await tester.pumpAndSettle();
    expect(find.text('Home tab'), findsOneWidget);
  });
}
