import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusmeme/data/app_limit.dart';
import 'package:focusmeme/data/auth_providers.dart';
import 'package:focusmeme/data/limits_repository.dart';
import 'package:focusmeme/features/limits/limits_providers.dart';
import 'package:focusmeme/features/limits/limits_screen.dart';
import 'package:focusmeme/features/limits/limits_stream_provider.dart';
import 'package:focusmeme/native/blocker_channel.dart';
import 'package:focusmeme/native/installed_app.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  group('AppLimit serialization', () {
    test('toMap and fromMap preserve all PRD section 7 fields', () {
      const original = AppLimit(
        packageName: 'com.instagram.android',
        limitMinutes: 30,
        focusOnly: true,
        pendingMinutes: 45,
        pendingRemove: false,
        pendingFrom: '2026-11-28',
      );

      final map = original.toMap();
      expect(map.containsKey('packageName'), isFalse);
      expect(map['limitMinutes'], 30);
      expect(map['focusOnly'], isTrue);
      expect(map['pendingMinutes'], 45);
      expect(map['pendingRemove'], isFalse);
      expect(map['pendingFrom'], '2026-11-28');

      final restored = AppLimit.fromMap('com.instagram.android', map);
      expect(restored, original);
    });
  });

  group('StubLimitsRepository', () {
    test('streams live add, update, and delete changes', () async {
      final repo = StubLimitsRepository();
      addTearDown(repo.dispose);

      final emissions = <List<AppLimit>>[];
      final sub = repo.watchLimits('test-uid').listen(emissions.add);
      addTearDown(sub.cancel);

      await pumpEventQueue();
      expect(emissions.last, isEmpty);

      const insta = AppLimit(
        packageName: 'com.instagram.android',
        limitMinutes: 25,
      );
      await repo.addLimit('test-uid', insta);
      await pumpEventQueue();
      expect(emissions.last, [insta]);

      final updated = insta.copyWith(limitMinutes: 15, focusOnly: true);
      await repo.updateLimit('test-uid', updated);
      await pumpEventQueue();
      expect(emissions.last, [updated]);

      await repo.deleteLimit('test-uid', 'com.instagram.android');
      await pumpEventQueue();
      expect(emissions.last, isEmpty);
    });
  });

  group('LimitsScreen', () {
    testWidgets('adds, edits, and deletes an app limit via providers', (
      WidgetTester tester,
    ) async {
      final repo = StubLimitsRepository();
      addTearDown(repo.dispose);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            authUidProvider.overrideWith((ref) async => 'test-uid'),
            blockerChannelProvider.overrideWithValue(
              BlockerChannel(isAndroidOverride: false),
            ),
            limitsRepositoryProvider.overrideWithValue(repo),
            limitsStreamProvider.overrideWith(
              (ref) => repo.watchLimits('test-uid'),
            ),
            installedAppsProvider.overrideWith(
              (ref) async => const <InstalledApp>[
                InstalledApp(
                  name: 'Instagram',
                  packageName: 'com.instagram.android',
                ),
                InstalledApp(
                  name: 'YouTube',
                  packageName: 'com.google.android.youtube',
                ),
              ],
            ),
          ],
          child: const MaterialApp(home: LimitsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('No app limits yet. Tap + to add one.'), findsOneWidget);

      // Add a 30-minute limit for Instagram (selected by default).
      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '30');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(find.text('Instagram'), findsOneWidget);
      expect(find.textContaining('30 min/day'), findsOneWidget);

      // Edit the existing limit to 15 minutes + Focus-only.
      await tester.tap(find.text('Instagram'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '15');
      await tester.tap(find.byType(Switch));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(find.textContaining('15 min/day • Focus-only'), findsOneWidget);

      // Delete the limit.
      await tester.tap(find.byIcon(Icons.delete_outline));
      await tester.pumpAndSettle();

      expect(find.text('No app limits yet. Tap + to add one.'), findsOneWidget);
    });
  });
}
