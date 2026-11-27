import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../data/app_limit.dart';
import '../../native/installed_app.dart';
import 'limits_providers.dart';
import 'limits_stream_provider.dart';

/// Basic stock Material 3 screen for viewing, adding, editing, and deleting
/// per-app limits (UI polish deferred to M9).
class LimitsScreen extends ConsumerWidget {
  const LimitsScreen({super.key});

  String _formatSubtitle(AppLimit limit) {
    final parts = <String>[
      if (limit.limitMinutes > 0) '${limit.limitMinutes} min/day',
      if (limit.focusOnly) 'Focus-only',
    ];
    final summary = parts.isEmpty ? 'No limit set' : parts.join(' • ');
    return '$summary\n${limit.packageName}';
  }

  Future<void> _openEditor(
    BuildContext context,
    WidgetRef ref, {
    AppLimit? existing,
    required List<InstalledApp> installedApps,
    required Set<String> existingPackages,
  }) async {
    final availableApps = installedApps
        .where((app) => !existingPackages.contains(app.packageName))
        .toList(growable: false);

    final result = await showDialog<AppLimit>(
      context: context,
      builder: (context) => _LimitEditorDialog(
        existing: existing,
        availableApps: availableApps,
      ),
    );

    if (result == null) return;

    final notifier = ref.read(limitsActionsProvider.notifier);
    if (existing == null) {
      await notifier.addLimit(result);
    } else {
      await notifier.updateLimit(result);
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Surface mutation errors (e.g. Firestore rules or TODO stub errors) via SnackBar.
    ref.listen<AsyncValue<void>>(limitsActionsProvider, (previous, next) {
      if (next.hasError && next.error != previous?.error) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to save limit: ${next.error}')),
        );
      }
    });

    final limitsAsync = ref.watch(limitsStreamProvider);
    final installedApps =
        ref.watch(installedAppsProvider).value ?? const <InstalledApp>[];
    final actionsState = ref.watch(limitsActionsProvider);

    final appNames = <String, String>{
      for (final app in installedApps) app.packageName: app.name,
    };

    return Scaffold(
      appBar: AppBar(title: const Text('Limits')),
      body: limitsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('Could not load limits: $error'),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: () => ref.invalidate(limitsStreamProvider),
                  child: const Text('Retry'),
                ),
              ],
            ),
          ),
        ),
        data: (limits) {
          if (limits.isEmpty) {
            return const Center(
              child: Text('No app limits yet. Tap + to add one.'),
            );
          }

          final existingPackages = limits.map((l) => l.packageName).toSet();

          return ListView.builder(
            itemCount: limits.length,
            itemBuilder: (context, index) {
              final limit = limits[index];
              final title = appNames[limit.packageName] ?? limit.packageName;

              return ListTile(
                title: Text(title),
                subtitle: Text(_formatSubtitle(limit)),
                isThreeLine: true,
                onTap: actionsState.isLoading
                    ? null
                    : () => _openEditor(
                        context,
                        ref,
                        existing: limit,
                        installedApps: installedApps,
                        existingPackages: existingPackages,
                      ),
                trailing: IconButton(
                  tooltip: 'Delete limit',
                  icon: const Icon(Icons.delete_outline),
                  onPressed: actionsState.isLoading
                      ? null
                      : () => ref
                            .read(limitsActionsProvider.notifier)
                            .deleteLimit(limit.packageName),
                ),
              );
            },
          );
        },
      ),
      floatingActionButton: FloatingActionButton(
        tooltip: 'Add limit',
        onPressed: actionsState.isLoading
            ? null
            : () {
                final existingPackages =
                    (limitsAsync.value ?? const <AppLimit>[])
                        .map((l) => l.packageName)
                        .toSet();
                _openEditor(
                  context,
                  ref,
                  installedApps: installedApps,
                  existingPackages: existingPackages,
                );
              },
        child: const Icon(Icons.add),
      ),
    );
  }
}

class _LimitEditorDialog extends StatefulWidget {
  const _LimitEditorDialog({
    required this.existing,
    required this.availableApps,
  });

  final AppLimit? existing;
  final List<InstalledApp> availableApps;

  @override
  State<_LimitEditorDialog> createState() => _LimitEditorDialogState();
}

class _LimitEditorDialogState extends State<_LimitEditorDialog> {
  late final TextEditingController _minutesController;
  late final TextEditingController _packageController;
  late bool _focusOnly;
  String? _selectedPackage;
  String? _errorText;

  bool get _isEditing => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    _minutesController = TextEditingController(
      text: existing != null && existing.limitMinutes > 0
          ? '${existing.limitMinutes}'
          : '',
    );
    _packageController = TextEditingController(
      text: existing?.packageName ?? '',
    );
    _focusOnly = existing?.focusOnly ?? false;
    _selectedPackage = existing?.packageName ??
        (widget.availableApps.isNotEmpty
            ? widget.availableApps.first.packageName
            : null);
  }

  @override
  void dispose() {
    _minutesController.dispose();
    _packageController.dispose();
    super.dispose();
  }

  void _save() {
    final pkg = _isEditing
        ? widget.existing!.packageName
        : (widget.availableApps.isNotEmpty
              ? (_selectedPackage ?? '')
              : _packageController.text.trim());

    if (pkg.isEmpty) {
      setState(() => _errorText = 'Choose or enter an app package name.');
      return;
    }

    final rawMinutes = _minutesController.text.trim();
    final minutes = rawMinutes.isEmpty ? 0 : int.tryParse(rawMinutes);
    if (minutes == null || minutes < 0) {
      setState(() => _errorText = 'Enter a valid number of minutes.');
      return;
    }

    if (minutes == 0 && !_focusOnly) {
      setState(
        () => _errorText = 'Set daily minutes (> 0) or enable Focus-only.',
      );
      return;
    }

    final updated = widget.existing != null
        ? widget.existing!.copyWith(
            limitMinutes: minutes,
            focusOnly: _focusOnly,
          )
        : AppLimit(
            packageName: pkg,
            limitMinutes: minutes,
            focusOnly: _focusOnly,
          );

    Navigator.of(context).pop(updated);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit limit' : 'Add limit'),
      content: SizedBox(
        width: double.maxFinite,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_isEditing)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(widget.existing!.packageName),
                )
              else if (widget.availableApps.isNotEmpty) ...[
                const Text('Select app'),
                const SizedBox(height: 8),
                SizedBox(
                  height: 180,
                  child: ListView.builder(
                    itemCount: widget.availableApps.length,
                    itemBuilder: (context, index) {
                      final app = widget.availableApps[index];
                      final isSelected = app.packageName == _selectedPackage;
                      return ListTile(
                        dense: true,
                        selected: isSelected,
                        title: Text(app.name),
                        subtitle: Text(app.packageName),
                        trailing: isSelected ? const Icon(Icons.check) : null,
                        onTap: () {
                          setState(() {
                            _selectedPackage = app.packageName;
                            _errorText = null;
                          });
                        },
                      );
                    },
                  ),
                ),
                const SizedBox(height: 12),
              ] else ...[
                TextField(
                  controller: _packageController,
                  decoration: const InputDecoration(
                    labelText: 'App package name',
                    hintText: 'e.g. com.instagram.android',
                  ),
                ),
                const SizedBox(height: 12),
              ],
              TextField(
                controller: _minutesController,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(
                  labelText: 'Daily limit (minutes)',
                  hintText: 'e.g. 30',
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  const Expanded(child: Text('Focus-only (block in Pomodoro)')),
                  Switch(
                    value: _focusOnly,
                    onChanged: (value) {
                      setState(() {
                        _focusOnly = value;
                        _errorText = null;
                      });
                    },
                  ),
                ],
              ),
              if (_errorText != null) ...[
                const SizedBox(height: 8),
                Text(
                  _errorText!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(onPressed: _save, child: const Text('Save')),
      ],
    );
  }
}
