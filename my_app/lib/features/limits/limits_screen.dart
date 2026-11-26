import 'package:material_ui/material_ui.dart';

/// Placeholder Limits tab screen (per-app daily limits added in M1).
class LimitsScreen extends StatelessWidget {
  const LimitsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Limits')),
      body: const Center(child: Text('Limits tab')),
    );
  }
}
