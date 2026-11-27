import '../data/app_limit.dart';

/// User-configurable blocker settings synced to native storage.
class BlockerSettings {
  const BlockerSettings({
    this.farewellMemeOn = true,
    this.wifiOnlySync = true,
  });

  final bool farewellMemeOn;
  final bool wifiOnlySync;

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'farewellMemeOn': farewellMemeOn,
      'wifiOnlySync': wifiOnlySync,
    };
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is BlockerSettings &&
            other.farewellMemeOn == farewellMemeOn &&
            other.wifiOnlySync == wifiOnlySync;
  }

  @override
  int get hashCode => Object.hash(farewellMemeOn, wifiOnlySync);
}

/// Live permission and service state returned by `MethodChannel.status`.
class BlockerStatus {
  const BlockerStatus({
    required this.accessibilityEnabled,
    required this.usageAccessGranted,
    required this.batteryOptimizationIgnored,
    required this.serviceRunning,
    this.strictSchedule,
  });

  const BlockerStatus.unavailable()
    : accessibilityEnabled = false,
      usageAccessGranted = false,
      batteryOptimizationIgnored = false,
      serviceRunning = false,
      strictSchedule = null;

  final bool accessibilityEnabled;
  final bool usageAccessGranted;
  final bool batteryOptimizationIgnored;
  final bool serviceRunning;

  /// Active native Pomodoro schedule (used in M4 to recover UI after app kill).
  final Map<String, dynamic>? strictSchedule;

  factory BlockerStatus.fromMap(Map<Object?, Object?> map) {
    final rawSchedule = map['strictSchedule'];
    Map<String, dynamic>? parsedSchedule;
    if (rawSchedule is Map) {
      parsedSchedule = rawSchedule.map(
        (key, value) => MapEntry(key.toString(), value),
      );
    }

    return BlockerStatus(
      accessibilityEnabled: (map['accessibilityEnabled'] as bool?) ?? false,
      usageAccessGranted: (map['usageAccessGranted'] as bool?) ?? false,
      batteryOptimizationIgnored:
          (map['batteryOptimizationIgnored'] as bool?) ?? false,
      serviceRunning: (map['serviceRunning'] as bool?) ?? false,
      strictSchedule: parsedSchedule,
    );
  }
}

/// Today's per-app usage and block attempt counts returned by `getUsageStats`.
class NativeUsageStats {
  const NativeUsageStats({
    required this.dayKey,
    required this.perAppUsageMs,
    required this.perAppAttempts,
  });

  const NativeUsageStats.empty()
    : dayKey = '',
      perAppUsageMs = const <String, int>{},
      perAppAttempts = const <String, int>{};

  final String dayKey;
  final Map<String, int> perAppUsageMs;
  final Map<String, int> perAppAttempts;

  factory NativeUsageStats.fromMap(Map<Object?, Object?> map) {
    final usageRaw = map['perAppUsageMs'];
    final attemptsRaw = map['perAppAttempts'];

    final usage = <String, int>{};
    if (usageRaw is Map) {
      for (final entry in usageRaw.entries) {
        final v = entry.value;
        if (v is num) {
          usage[entry.key.toString()] = v.toInt();
        }
      }
    }

    final attempts = <String, int>{};
    if (attemptsRaw is Map) {
      for (final entry in attemptsRaw.entries) {
        final v = entry.value;
        if (v is num) {
          attempts[entry.key.toString()] = v.toInt();
        }
      }
    }

    return NativeUsageStats(
      dayKey: (map['dayKey'] as String?) ?? '',
      perAppUsageMs: Map<String, int>.unmodifiable(usage),
      perAppAttempts: Map<String, int>.unmodifiable(attempts),
    );
  }
}

/// Payload sent over `MethodChannel.sync` to native Kotlin storage (PRD section 6).
class NativeSyncPayload {
  const NativeSyncPayload({
    required this.limits,
    required this.settings,
    this.strictSchedule,
    this.includeStrictSchedule = false,
  });

  final List<AppLimit> limits;
  final BlockerSettings settings;
  final Map<String, dynamic>? strictSchedule;

  /// When `false`, omits `strictSchedule` so syncing limits does not overwrite
  /// an active Pomodoro schedule owned by native.
  final bool includeStrictSchedule;

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'limits': limits
          .map(
            (limit) => <String, dynamic>{
              'packageName': limit.packageName,
              ...limit.toMap(),
            },
          )
          .toList(growable: false),
      if (includeStrictSchedule) 'strictSchedule': strictSchedule,
      'settings': settings.toMap(),
    };
  }
}
