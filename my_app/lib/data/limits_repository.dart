import 'dart:async';

import 'app_limit.dart';

/// Contract for persisting and streaming a user's per-app limits.
///
/// Firestore path (PRD section 7): `users/{uid}/limits/{pkg}`.
abstract interface class LimitsRepository {
  /// Streams all limits for [uid] whenever they change.
  Stream<List<AppLimit>> watchLimits(String uid);

  /// Creates (or overwrites) a limit document for [limit.packageName].
  Future<void> addLimit(String uid, AppLimit limit);

  /// Updates an existing limit document for [limit.packageName].
  Future<void> updateLimit(String uid, AppLimit limit);

  /// Deletes the limit document for [packageName].
  Future<void> deleteLimit(String uid, String packageName);
}

/// In-memory [LimitsRepository] stub used until Firestore is wired by you in M1
/// (and useful in widget/unit tests without needing live Firebase).
class StubLimitsRepository implements LimitsRepository {
  StubLimitsRepository({List<AppLimit> initialLimits = const []}) {
    for (final limit in initialLimits) {
      _store[limit.packageName] = limit;
    }
  }

  final Map<String, AppLimit> _store = <String, AppLimit>{};
  final StreamController<List<AppLimit>> _controller =
      StreamController<List<AppLimit>>.broadcast();

  List<AppLimit> _snapshot() {
    final items = _store.values.toList()
      ..sort((a, b) => a.packageName.compareTo(b.packageName));
    return List<AppLimit>.unmodifiable(items);
  }

  void _emit() {
    if (!_controller.isClosed) {
      _controller.add(_snapshot());
    }
  }

  @override
  Stream<List<AppLimit>> watchLimits(String uid) async* {
    // Emit current in-memory state immediately, then forward future updates.
    yield _snapshot();
    yield* _controller.stream;
  }

  @override
  Future<void> addLimit(String uid, AppLimit limit) async {
    _store[limit.packageName] = limit;
    _emit();
  }

  @override
  Future<void> updateLimit(String uid, AppLimit limit) async {
    _store[limit.packageName] = limit;
    _emit();
  }

  @override
  Future<void> deleteLimit(String uid, String packageName) async {
    _store.remove(packageName);
    _emit();
  }

  /// Closes the internal broadcast stream controller.
  void dispose() {
    _controller.close();
  }
}
