import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/app_limit.dart';
import '../../data/auth_providers.dart';
import '../../data/limits_repository.dart';
import '../../native/blocker_channel.dart';
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
class LimitsActionsNotifier extends AsyncNotifier<void> {
  @override
  FutureOr<void> build() {}

  /// Saves a new [AppLimit] for the signed-in user.
  Future<void> addLimit(AppLimit limit) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final uid = await ref.read(authUidProvider.future);
      await ref.read(limitsRepositoryProvider).addLimit(uid, limit);
    });
  }

  /// Updates an existing [AppLimit] for the signed-in user.
  Future<void> updateLimit(AppLimit limit) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final uid = await ref.read(authUidProvider.future);
      await ref.read(limitsRepositoryProvider).updateLimit(uid, limit);
    });
  }

  /// Deletes the limit for [packageName] for the signed-in user.
  Future<void> deleteLimit(String packageName) async {
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
