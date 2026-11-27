import 'package:cloud_firestore/cloud_firestore.dart';

import 'app_limit.dart';
import 'limits_repository.dart';

/// Firestore implementation of [LimitsRepository] — owned by you ([ME] in M1).
///
/// Target collection path (PRD section 7):
/// `users/{uid}/limits/{pkg}`
///
/// Document fields (handled by [AppLimit.toMap] and [AppLimit.fromMap]):
/// `{ limitMinutes, focusOnly, pendingMinutes?, pendingRemove?, pendingFrom? }`
class FirestoreLimitsRepository implements LimitsRepository {
  FirestoreLimitsRepository(this.firestore);

  final FirebaseFirestore firestore;

  @override
  Stream<List<AppLimit>> watchLimits(String uid) {
    throw UnimplementedError('TODO [ME]: stream users/$uid/limits snapshots');
  }

  @override
  Future<void> addLimit(String uid, AppLimit limit) {
    throw UnimplementedError(
      'TODO [ME]: write limit.toMap() to users/$uid/limits/${limit.packageName}',
    );
  }

  @override
  Future<void> updateLimit(String uid, AppLimit limit) {
    throw UnimplementedError(
      'TODO [ME]: update users/$uid/limits/${limit.packageName}',
    );
  }

  @override
  Future<void> deleteLimit(String uid, String packageName) {
    throw UnimplementedError(
      'TODO [ME]: delete users/$uid/limits/$packageName',
    );
  }
}
