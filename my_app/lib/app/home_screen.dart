import 'package:material_ui/material_ui.dart';

/// Placeholder Home tab screen (usage stats and streak added in M7;
/// "Blocking is off" banner added in M3).
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Home')),
      body: const Center(child: Text('Home tab')),
    );
  }
}
