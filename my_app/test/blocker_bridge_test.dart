import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focusmeme/data/app_limit.dart';
import 'package:focusmeme/data/auth_providers.dart';
import 'package:focusmeme/data/limits_repository.dart';
import 'package:focusmeme/features/limits/limits_providers.dart';
import 'package:focusmeme/native/blocker_channel.dart';
import 'package:focusmeme/native/blocker_models.dart';
import 'package:focusmeme/native/blocker_sync_service.dart';

class _FailingLimitsRepository implements LimitsRepository {
  @override
  Stream<List<AppLimit>> watchLimits(String uid) =>
      Stream<List<AppLimit>>.value(const <AppLimit>[]);

  @override
  Future<void> addLimit(String uid, AppLimit limit) async {
    throw StateError('Simulated Firestore offline/permission error');
  }

  @override
  Future<void> updateLimit(String uid, AppLimit limit) async {
    throw StateError('Simulated Firestore error');
  }

  @override
  Future<void> deleteLimit(String uid, String packageName) async {
    throw StateError('Simulated Firestore error');
  }
}

class _RecordingBlockerChannel extends BlockerChannel {
  _RecordingBlockerChannel() : super(isAndroidOverride: true);

  final List<NativeSyncPayload> syncedPayloads = <NativeSyncPayload>[];

  @override
  Future<void> sync(NativeSyncPayload payload) async {
    syncedPayloads.add(payload);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('BlockerChannel (8 locked methods)', () {
    const channel = MethodChannel(BlockerChannel.channelName);
    final recordedCalls = <MethodCall>[];

    setUp(() {
      recordedCalls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
            recordedCalls.add(call);
            switch (call.method) {
              case 'status':
                return <String, Object?>{
                  'accessibilityEnabled': true,
                  'usageAccessGranted': true,
                  'batteryOptimizationIgnored': false,
                  'serviceRunning': true,
                  'strictSchedule': <String, Object?>{'subject': 'DSA'},
                };
              case 'getInstalledApps':
                return <Object?>[
                  <String, Object?>{
                    'name': 'Instagram',
                    'packageName': 'com.instagram.android',
                  },
                ];
              case 'getUsageStats':
                return <String, Object?>{
                  'dayKey': '2026-11-27',
                  'perAppUsageMs': <String, Object?>{
                    'com.instagram.android': 120000,
                  },
                  'perAppAttempts': <String, Object?>{
                    'com.instagram.android': 2,
                  },
                };
              default:
                return null;
            }
          });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('invokes all 8 MethodChannel methods and parses responses', () async {
      final bridge = BlockerChannel(isAndroidOverride: true);

      await bridge.sync(
        const NativeSyncPayload(
          limits: [
            AppLimit(
              packageName: 'com.instagram.android',
              limitMinutes: 20,
              focusOnly: true,
            ),
          ],
          settings: BlockerSettings(farewellMemeOn: true, wifiOnlySync: false),
        ),
      );
      final status = await bridge.status();
      final apps = await bridge.getInstalledApps();
      final stats = await bridge.getUsageStats();
      await bridge.openAccessibility();
      await bridge.openUsageAccess();
      await bridge.openBattery();
      await bridge.openAutostart();

      expect(
        recordedCalls.map((c) => c.method).toList(),
        equals([
          'sync',
          'status',
          'getInstalledApps',
          'getUsageStats',
          'openAccessibility',
          'openUsageAccess',
          'openBattery',
          'openAutostart',
        ]),
      );

      final syncArgs = recordedCalls.first.arguments as Map<Object?, Object?>;
      expect(syncArgs.containsKey('strictSchedule'), isFalse);
      expect((syncArgs['limits'] as List<Object?>).length, 1);

      expect(status.accessibilityEnabled, isTrue);
      expect(status.usageAccessGranted, isTrue);
      expect(status.batteryOptimizationIgnored, isFalse);
      expect(status.serviceRunning, isTrue);
      expect(status.strictSchedule?['subject'], 'DSA');

      expect(apps.single.name, 'Instagram');
      expect(stats.dayKey, '2026-11-27');
      expect(stats.perAppUsageMs['com.instagram.android'], 120000);
      expect(stats.perAppAttempts['com.instagram.android'], 2);
    });

    test('safely no-ops on Web / non-Android platforms', () async {
      final bridge = BlockerChannel(isAndroidOverride: false);

      await bridge.sync(
        const NativeSyncPayload(limits: [], settings: BlockerSettings()),
      );
      final status = await bridge.status();
      final apps = await bridge.getInstalledApps();
      final stats = await bridge.getUsageStats();
      await bridge.openAccessibility();
      await bridge.openUsageAccess();
      await bridge.openBattery();
      await bridge.openAutostart();

      expect(recordedCalls, isEmpty);
      expect(status.accessibilityEnabled, isFalse);
      expect(apps, isEmpty);
      expect(stats.perAppUsageMs, isEmpty);
    });
  });

  group('BlockerSyncService', () {
    test('debounces rapid limit edits into a single native sync', () async {
      final channel = _RecordingBlockerChannel();
      final service = BlockerSyncService(
        channel,
        debounceDuration: const Duration(milliseconds: 40),
      );
      addTearDown(service.dispose);

      service.applyLocalLimit(
        const AppLimit(packageName: 'com.instagram.android', limitMinutes: 30),
      );
      service.applyLocalLimit(
        const AppLimit(packageName: 'com.instagram.android', limitMinutes: 20),
      );
      service.applyLocalLimit(
        const AppLimit(
          packageName: 'com.google.android.youtube',
          limitMinutes: 45,
        ),
      );

      // Nothing sent yet during debounce window.
      expect(channel.syncedPayloads, isEmpty);

      await Future<void>.delayed(const Duration(milliseconds: 80));

      expect(channel.syncedPayloads.length, 1);
      final synced = channel.syncedPayloads.single;
      expect(synced.limits.length, 2);
      expect(
        synced.limits.firstWhere((l) => l.packageName == 'com.instagram.android').limitMinutes,
        20,
      );
      expect(synced.includeStrictSchedule, isFalse);
    });

    test('local-first: native updates even when Firestore write fails', () async {
      final channel = _RecordingBlockerChannel();
      final container = ProviderContainer(
        overrides: [
          authUidProvider.overrideWith((ref) async => 'uid-1'),
          limitsRepositoryProvider.overrideWithValue(_FailingLimitsRepository()),
          blockerChannelProvider.overrideWithValue(channel),
          blockerSyncServiceProvider.overrideWith((ref) {
            final svc = BlockerSyncService(
              channel,
              debounceDuration: const Duration(milliseconds: 20),
            );
            ref.onDispose(svc.dispose);
            return svc;
          }),
        ],
      );
      addTearDown(container.dispose);

      await container
          .read(limitsActionsProvider.notifier)
          .addLimit(
            const AppLimit(
              packageName: 'com.instagram.android',
              limitMinutes: 15,
            ),
          );

      // Firestore failed, so actions state holds the error...
      expect(container.read(limitsActionsProvider).hasError, isTrue);

      // ...yet native sync STILL succeeded locally!
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(channel.syncedPayloads.length, 1);
      expect(
        channel.syncedPayloads.single.limits.single.packageName,
        'com.instagram.android',
      );
      expect(channel.syncedPayloads.single.limits.single.limitMinutes, 15);
    });
  });
}
