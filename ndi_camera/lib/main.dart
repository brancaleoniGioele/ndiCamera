import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'NDI Camera',
      theme: ThemeData(useMaterial3: true),
      home: const CameraHomePage(),
    );
  }
}

class CameraHomePage extends StatefulWidget {
  const CameraHomePage({super.key});

  @override
  State<CameraHomePage> createState() => _CameraHomePageState();
}

class _CameraHomePageState extends State<CameraHomePage> {
  static const platform = MethodChannel('ndi_camera/channel');

  bool _permissionsGranted = false;
  String _status = 'Pronto';

  Map<String, List<int>> presetModes = {};
  String? selectedResolution;
  int? selectedFps;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final camera = await Permission.camera.request();
    final mic = await Permission.microphone.request();

    setState(() {
      _permissionsGranted = camera.isGranted && mic.isGranted;
      _status = _permissionsGranted ? 'Permessi concessi' : 'Permessi negati';
    });
  }

  Future<void> _getPresetModes() async {
    final result = await platform.invokeMethod('getPresetModes');
    final rawMap = Map<dynamic, dynamic>.from(result);

    final parsed = <String, List<int>>{};
    for (final entry in rawMap.entries) {
      parsed[entry.key.toString()] = List<int>.from(entry.value);
    }

    setState(() {
      presetModes = parsed;
      if (presetModes.isNotEmpty) {
        selectedResolution ??= presetModes.keys.first;
        final fpsList = presetModes[selectedResolution] ?? [];
        if (fpsList.isNotEmpty) {
          selectedFps = fpsList.contains(selectedFps) ? selectedFps : fpsList.first;
        }
      }
    });
  }

  Future<void> _startPreview() async {
    if (selectedResolution == null || selectedFps == null) return;

    try {
      await platform.invokeMethod('startPreview', {
        'resolution': selectedResolution,
        'fps': selectedFps,
      });

      setState(() {
        _status = 'Preview avviata: $selectedResolution @ ${selectedFps}fps';
      });
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Errore preview: ${e.message}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final fpsOptions = selectedResolution == null
        ? <int>[]
        : (presetModes[selectedResolution] ?? []);

    return Scaffold(
      appBar: AppBar(
        title: const Text('NDI Camera'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_status),
            const SizedBox(height: 16),
            if (_permissionsGranted)
              FilledButton(
                onPressed: _getPresetModes,
                child: const Text('Leggi modalità compatibili'),
              ),
            const SizedBox(height: 20),
            if (presetModes.isNotEmpty) ...[
              const Text('Risoluzione'),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: presetModes.keys.map((resolution) {
                  return ChoiceChip(
                    label: Text(resolution),
                    selected: selectedResolution == resolution,
                    onSelected: (_) {
                      final newFpsOptions = presetModes[resolution] ?? [];
                      setState(() {
                        selectedResolution = resolution;
                        selectedFps = newFpsOptions.contains(selectedFps)
                            ? selectedFps
                            : (newFpsOptions.isNotEmpty ? newFpsOptions.first : null);
                      });
                    },
                  );
                }).toList(),
              ),
              const SizedBox(height: 20),
              const Text('FPS'),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: fpsOptions.map((fps) {
                  return ChoiceChip(
                    label: Text('$fps fps'),
                    selected: selectedFps == fps,
                    onSelected: (_) {
                      setState(() {
                        selectedFps = fps;
                      });
                    },
                  );
                }).toList(),
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: (selectedResolution != null && selectedFps != null)
                    ? _startPreview
                    : null,
                child: const Text('Avvia preview'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}