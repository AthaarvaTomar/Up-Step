/// Metadata for a launchable Android app returned by `getInstalledApps`.
class InstalledApp {
  const InstalledApp({
    required this.name,
    required this.packageName,
  });

  /// Human-readable launcher label (e.g. "YouTube").
  final String name;

  /// Unique Android package name (e.g. "com.google.android.youtube").
  final String packageName;

  factory InstalledApp.fromMap(Map<Object?, Object?> map) {
    final pkg = (map['packageName'] as String?) ?? '';
    final rawName = (map['name'] as String?)?.trim() ?? '';
    return InstalledApp(
      name: rawName.isEmpty ? pkg : rawName,
      packageName: pkg,
    );
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is InstalledApp &&
            other.name == name &&
            other.packageName == packageName;
  }

  @override
  int get hashCode => Object.hash(name, packageName);
}
