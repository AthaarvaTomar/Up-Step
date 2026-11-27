import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Exposes the app's [FirebaseAuth] instance (overridable in tests).
final firebaseAuthProvider = Provider<FirebaseAuth>((ref) {
  return FirebaseAuth.instance;
});

/// Emits the current Firebase [User] whenever auth state changes.
final authUserProvider = StreamProvider<User?>((ref) {
  return ref.watch(firebaseAuthProvider).authStateChanges();
});

/// Ensures the device has a signed-in Firebase user and returns its `uid`.
///
/// First-time anonymous sign-in requires network once. On subsequent launches,
/// Firebase Auth restores the cached user locally so Firestore offline
/// persistence continues to work without network.
final authUidProvider = FutureProvider<String>((ref) async {
  final auth = ref.watch(firebaseAuthProvider);

  // Wait for Firebase Auth to emit the locally restored user (if any) before
  // creating a new anonymous account, avoiding duplicate UIDs on offline launch.
  final existingUser = await ref.watch(authUserProvider.future);
  if (existingUser != null) {
    return existingUser.uid;
  }

  final credential = await auth.signInAnonymously();
  final user = credential.user;
  if (user == null) {
    throw StateError('Firebase anonymous sign-in returned a null user.');
  }
  return user.uid;
});
