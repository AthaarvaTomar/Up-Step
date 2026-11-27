/// Per-app daily limit and focus-blocking configuration.
///
/// Matches PRD section 7:
/// `users/{uid}/limits/{pkg}: { limitMinutes, focusOnly, pendingMinutes?, pendingRemove?, pendingFrom? }`
class AppLimit {
  const AppLimit({
    required this.packageName,
    required this.limitMinutes,
    this.focusOnly = false,
    this.pendingMinutes,
    this.pendingRemove,
    this.pendingFrom,
  });

  /// Android package name (used as the Firestore document ID `{pkg}`).
  final String packageName;

  /// Daily usage cap in minutes. `0` means no daily minute cap (focus-only app).
  final int limitMinutes;

  /// When `true`, this app is also blocked during strict Pomodoro focus phases.
  final bool focusOnly;

  /// Pending raised limit (in minutes) scheduled to apply from [pendingFrom] (M7).
  final int? pendingMinutes;

  /// When `true`, deletion is queued to apply from [pendingFrom] (M7).
  final bool? pendingRemove;

  /// Local date (`yyyy-MM-dd`) when a pending raise or removal takes effect (M7).
  final String? pendingFrom;

  /// Serializes document fields for Firestore (`packageName` is the doc ID).
  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'limitMinutes': limitMinutes,
      'focusOnly': focusOnly,
      if (pendingMinutes != null) 'pendingMinutes': pendingMinutes,
      if (pendingRemove != null) 'pendingRemove': pendingRemove,
      if (pendingFrom != null) 'pendingFrom': pendingFrom,
    };
  }

  /// Creates an [AppLimit] from a Firestore document ID and field map.
  factory AppLimit.fromMap(String packageName, Map<String, dynamic> map) {
    return AppLimit(
      packageName: packageName,
      limitMinutes: (map['limitMinutes'] as num?)?.toInt() ?? 0,
      focusOnly: (map['focusOnly'] as bool?) ?? false,
      pendingMinutes: (map['pendingMinutes'] as num?)?.toInt(),
      pendingRemove: map['pendingRemove'] as bool?,
      pendingFrom: map['pendingFrom'] as String?,
    );
  }

  AppLimit copyWith({
    String? packageName,
    int? limitMinutes,
    bool? focusOnly,
    int? pendingMinutes,
    bool? pendingRemove,
    String? pendingFrom,
  }) {
    return AppLimit(
      packageName: packageName ?? this.packageName,
      limitMinutes: limitMinutes ?? this.limitMinutes,
      focusOnly: focusOnly ?? this.focusOnly,
      pendingMinutes: pendingMinutes ?? this.pendingMinutes,
      pendingRemove: pendingRemove ?? this.pendingRemove,
      pendingFrom: pendingFrom ?? this.pendingFrom,
    );
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is AppLimit &&
            other.packageName == packageName &&
            other.limitMinutes == limitMinutes &&
            other.focusOnly == focusOnly &&
            other.pendingMinutes == pendingMinutes &&
            other.pendingRemove == pendingRemove &&
            other.pendingFrom == pendingFrom;
  }

  @override
  int get hashCode => Object.hash(
    packageName,
    limitMinutes,
    focusOnly,
    pendingMinutes,
    pendingRemove,
    pendingFrom,
  );

  @override
  String toString() {
    return 'AppLimit(pkg: $packageName, limitMinutes: $limitMinutes, '
        'focusOnly: $focusOnly, pendingMinutes: $pendingMinutes, '
        'pendingRemove: $pendingRemove, pendingFrom: $pendingFrom)';
  }
}
