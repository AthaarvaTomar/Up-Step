import 'package:material_ui/material_ui.dart';

/// Placeholder Settings tab screen (meme sync and farewell toggles added in M6).
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: const Center(child: Text('Settings tab')),
    );
  }
}
