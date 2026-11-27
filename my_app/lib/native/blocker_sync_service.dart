import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/app_limit.dart';
import 'blocker_channel.dart';
import 'blocker_models.dart';

/// Local-first, debounced bridge that pushes FocusMeme state to native Kotlin
/// via `MethodChannel("focusmeme/blocker")` (`sync`).
///
/// Always updates its local mirror and schedules native `sync` independently of
/// Firestore so a Firestore failure or offline state never delays or blocks
/// native enforcement (PRD section 6).
class BlockerSyncService {
  BlockerSyncService(
    this._channel, {
    this.debounceDuration = const Duration(milliseconds: 300),
  });

  final BlockerChannel _channel;
  final Duration debounceDuration;

  final Map<String, AppLimit> _limitsByPackage = <String, AppLimit>{};
  BlockerSettings _settings = const BlockerSettings();
  Map<String, dynamic>? _strictSchedule;
  bool _hasScheduleUpdate = false;

  Timer? _debounceTimer;
  bool _disposed = false;
  Object? _lastSyncError;

  /// Most recent native sync error, if any (exposed for diagnostics/tests).
  Object? get lastSyncError => _lastSyncError;

  /// Current local mirror of limits queued for native enforcement.
  List<AppLimit> get currentLimits {
    final items = _limitsByPackage.values.toList()
      ..sort((a, b) => a.packageName.compareTo(b.packageName));
    return List<AppLimit>.unmodifiable(items);
  }

  /// Updates or inserts a single limit locally and schedules a debounced sync.
  void applyLocalLimit(AppLimit limit) {
    _limitsByPackage[limit.packageName] = limit;
    _scheduleSync();
  }

  /// Removes a single limit locally and schedules a debounced sync.
  void removeLocalLimit(String packageName) {
    _limitsByPackage.remove(packageName);
    _scheduleSync();
  }

  /// Reconciles the local mirror when Firestore streams a fresh list of limits.
  ///
  /// Skips scheduling if the incoming list already matches the local mirror.
  void syncLimitsFromStream(List<AppLimit> limits) {
    final incoming = <String, AppLimit>{
      for (final limit in limits) limit.packageName: limit,
    };
    if (mapEquals(_limitsByPackage, incoming)) {
      return;
    }
    _limitsByPackage
      ..clear()
      ..addAll(incoming);
    _scheduleSync();
  }

  /// Updates settings locally and schedules a debounced native sync.
  void updateSettings(BlockerSettings settings) {
    if (_settings == settings) return;
    _settings = settings;
    _scheduleSync();
  }

  /// Updates (or clears with `null`) the native strict Pomodoro schedule.
  void updateStrictSchedule(Map<String, dynamic>? schedule) {
    _strictSchedule = schedule;
    _hasScheduleUpdate = true;
    _scheduleSync();
  }

  void _scheduleSync() {
    if (_disposed) return;
    _debounceTimer?.cancel();
    _debounceTimer = Timer(debounceDuration, () {
      unawaited(flushNow());
    });
  }

  /// Cancels any pending debounce timer and pushes the latest snapshot now.
  Future<void> flushNow() async {
    if (_disposed) return;
    _debounceTimer?.cancel();
    _debounceTimer = null;

    final includeSchedule = _hasScheduleUpdate;
    final payload = NativeSyncPayload(
      limits: currentLimits,
      settings: _settings,
      strictSchedule: _strictSchedule,
      includeStrictSchedule: includeSchedule,
    );
    _hasScheduleUpdate = false;

    try {
      await _channel.sync(payload);
      _lastSyncError = null;
    } catch (error) {
      _lastSyncError = error;
    }
  }

  void dispose() {
    _disposed = true;
    _debounceTimer?.cancel();
    _debounceTimer = null;
  }
}
