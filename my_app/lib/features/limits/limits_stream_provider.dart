import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/app_limit.dart';

/// Streams the signed-in user's per-app limits — owned by you ([ME] in M1).
///
/// TODO [ME]: Replace this placeholder (~10 lines) so it:
/// 1. Imports `../../data/auth_providers.dart` and `limits_providers.dart`.
/// 2. Awaits the user's `uid` from `ref.watch(authUidProvider.future)` (using `async*`).
/// 3. Watches `limitsRepositoryProvider` and yields `repo.watchLimits(uid)` with `yield*`.
final limitsStreamProvider = StreamProvider<List<AppLimit>>((ref) {
  return Stream<List<AppLimit>>.value(const <AppLimit>[]);
});
