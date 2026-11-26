import 'package:material_ui/material_ui.dart';

/// Placeholder Focus tab screen (Pomodoro session controls added in M4).
class FocusScreen extends StatelessWidget {
  const FocusScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Focus')),
      body: const Center(child: Text('Focus tab')),
    );
  }
}
