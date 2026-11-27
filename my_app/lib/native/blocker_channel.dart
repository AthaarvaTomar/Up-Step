import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'installed_app.dart';

/// Dart side of the `focusmeme/blocker` MethodChannel (PRD section 6).
///
/// In M1 only `getInstalledApps` is implemented; M2-M3 add `sync`, `status`,
/// `getUsageStats`, and the permission `open*` methods.
class BlockerChannel {
  BlockerChannel({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel(channelName);

  static const String channelName = 'focusmeme/blocker';

  final MethodChannel _channel;

  /// Queries launchable apps installed on the Android device.
  ///
  /// Returns an empty list on Web and non-Android platforms where no native
  /// Android `PackageManager` exists.
  Future<List<InstalledApp>> getInstalledApps() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return const <InstalledApp>[];
    }

    final rawList = await _channel.invokeListMethod<Object?>('getInstalledApps');
    if (rawList == null) {
      return const <InstalledApp>[];
    }

    return rawList
        .whereType<Map<Object?, Object?>>()
        .map(InstalledApp.fromMap)
        .where((app) => app.packageName.isNotEmpty)
        .toList(growable: false);
  }
}
