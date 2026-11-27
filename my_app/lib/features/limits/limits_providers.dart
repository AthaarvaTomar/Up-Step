import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/app_limit.dart';
import '../../data/auth_providers.dart';
import '../../data/limits_repository.dart';
import '../../native/blocker_channel.dart';
import '../../native/blocker_sync_service.dart';
import '../../native/installed_app.dart';
import 'limits_stream_provider.dart';

/// Provides the active [LimitsRepository] implementation.
///
/// Currently returns [StubLimitsRepository] so the UI compiles before M1
/// Firestore code is written.
/// TODO [ME]: Once you implement `FirestoreLimitsRepository`, swap this to:
/// `return FirestoreLimitsRepository(FirebaseFirestore.instance);`
final limitsRepositoryProvider = Provider<LimitsRepository>((ref) {
  final stub = StubLimitsRepository();
  ref.onDispose(stub.dispose);
  return stub;
});

/// Provides the [BlockerChannel] bridge used to call native Kotlin methods.
final blockerChannelProvider = Provider<BlockerChannel>((ref) {
  return BlockerChannel();
});

/// Provides the local-first, debounced [BlockerSyncService] that pushes limits,
/// settings, and Pomodoro schedules to native Android storage.
final blockerSyncServiceProvider = Provider<BlockerSyncService>((ref) {
  final service = BlockerSyncService(ref.watch(blockerChannelProvider));
  ref.onDispose(service.dispose);
  return service;
});

/// Keeps native Android storage synced whenever [limitsStreamProvider] emits
/// remote Firestore updates (e.g. changes made in the Firebase Console).
final limitsNativeSyncProvider = Provider<void>((ref) {
  ref.listen<AsyncValue<List<AppLimit>>>(limitsStreamProvider, (_, next) {
    final limits = next.value;
    if (limits != null) {
      ref.read(blockerSyncServiceProvider).syncLimitsFromStream(limits);
    }
  }, fireImmediately: true);
});

/// Loads the list of launchable apps installed on the Android device.
///
/// Returns an empty list on Web, where the Limits screen falls back to manual
/// package-name input.
final installedAppsProvider = FutureProvider<List<InstalledApp>>((ref) {
  return ref.watch(blockerChannelProvider).getInstalledApps();
});

/// Maps `packageName -> AppLimit` from [limitsStreamProvider] for fast lookups.
final limitsByPackageProvider = Provider<Map<String, AppLimit>>((ref) {
  final limits = ref.watch(limitsStreamProvider).value ?? const <AppLimit>[];
  return <String, AppLimit>{
    for (final limit in limits) limit.packageName: limit,
  };
});

/// Handles add, update, and delete mutations on app limits and exposes
/// loading/error state for the UI via [AsyncValue].
///
/// Local-first (PRD section 6): pushes the change to native Android via
/// [blockerSyncServiceProvider] immediately, before attempting Firestore sync,
/// so a Firestore failure never blocks native enforcement.
class LimitsActionsNotifier extends AsyncNotifier<void> {
  @override
  FutureOr<void> build() {}

  /// Saves a new [AppLimit] locally to native first, then backs up to Firestore.
  Future<void> addLimit(AppLimit limit) async {
    ref.read(blockerSyncServiceProvider).applyLocalLimit(limit);

    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final uid = await ref.read(authUidProvider.future);
      await ref.read(limitsRepositoryProvider).addLimit(uid, limit);
    });
  }

  /// Updates an existing [AppLimit] locally to native first, then backs up to Firestore.
  Future<void> updateLimit(AppLimit limit) async {
    ref.read(blockerSyncServiceProvider).applyLocalLimit(limit);

    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final uid = await ref.read(authUidProvider.future);
      await ref.read(limitsRepositoryProvider).updateLimit(uid, limit);
    });
  }

  /// Deletes the limit for [packageName] locally in native first, then in Firestore.
  Future<void> deleteLimit(String packageName) async {
    ref.read(blockerSyncServiceProvider).removeLocalLimit(packageName);

    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final uid = await ref.read(authUidProvider.future);
      await ref.read(limitsRepositoryProvider).deleteLimit(uid, packageName);
    });
  }
}

/// Exposes [LimitsActionsNotifier] for saving, editing, and deleting limits.
final limitsActionsProvider =
    AsyncNotifierProvider<LimitsActionsNotifier, void>(
      LimitsActionsNotifier.new,
    );
