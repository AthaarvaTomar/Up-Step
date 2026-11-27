import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'blocker_models.dart';
import 'installed_app.dart';

/// Dart side of the locked 8-method `focusmeme/blocker` MethodChannel (PRD section 6):
/// `sync`, `status`, `getInstalledApps`, `getUsageStats`,
/// `openAccessibility`, `openUsageAccess`, `openBattery`, `openAutostart`.
class BlockerChannel {
  BlockerChannel({
    MethodChannel? channel,
    this.isAndroidOverride,
  }) : _channel = channel ?? const MethodChannel(channelName);

  static const String channelName = 'focusmeme/blocker';

  final MethodChannel _channel;
  final bool? isAndroidOverride;

  bool get _isAndroid =>
      isAndroidOverride ??
      (!kIsWeb && defaultTargetPlatform == TargetPlatform.android);

  /// Pushes limits, settings, and (optionally) strictSchedule to native storage.
  Future<void> sync(NativeSyncPayload payload) async {
    if (!_isAndroid) return;
    await _channel.invokeMethod<void>('sync', payload.toMap());
  }

  /// Reads live Android permission states and any active native `strictSchedule`.
  Future<BlockerStatus> status() async {
    if (!_isAndroid) return const BlockerStatus.unavailable();
    final raw = await _channel.invokeMapMethod<Object?, Object?>('status');
    if (raw == null) return const BlockerStatus.unavailable();
    return BlockerStatus.fromMap(raw);
  }

  /// Queries launchable apps installed on the Android device.
  ///
  /// Returns an empty list on Web and non-Android platforms.
  Future<List<InstalledApp>> getInstalledApps() async {
    if (!_isAndroid) return const <InstalledApp>[];

    final rawList = await _channel.invokeListMethod<Object?>('getInstalledApps');
    if (rawList == null) return const <InstalledApp>[];

    return rawList
        .whereType<Map<Object?, Object?>>()
        .map(InstalledApp.fromMap)
        .where((app) => app.packageName.isNotEmpty)
        .toList(growable: false);
  }

  /// Pulls today's per-app usage (ms) and block attempt counts from native.
  Future<NativeUsageStats> getUsageStats() async {
    if (!_isAndroid) return const NativeUsageStats.empty();
    final raw = await _channel.invokeMapMethod<Object?, Object?>(
      'getUsageStats',
    );
    if (raw == null) return const NativeUsageStats.empty();
    return NativeUsageStats.fromMap(raw);
  }

  /// Opens Android's Accessibility Settings screen.
  Future<void> openAccessibility() async {
    if (!_isAndroid) return;
    await _channel.invokeMethod<void>('openAccessibility');
  }

  /// Opens Android's Usage Access Settings screen.
  Future<void> openUsageAccess() async {
    if (!_isAndroid) return;
    await _channel.invokeMethod<void>('openUsageAccess');
  }

  /// Opens Android's Battery Optimization Settings screen.
  Future<void> openBattery() async {
    if (!_isAndroid) return;
    await _channel.invokeMethod<void>('openBattery');
  }

  /// Opens OEM autostart/background-run settings (with App Details fallback).
  Future<void> openAutostart() async {
    if (!_isAndroid) return;
    await _channel.invokeMethod<void>('openAutostart');
  }
}
