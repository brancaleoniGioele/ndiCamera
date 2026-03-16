import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
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
        toolbarHeight: 48,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _status,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 15),
                    ),
                    const SizedBox(height: 12),
                    if (_permissionsGranted)
                      FilledButton(
                        onPressed: _getPresetModes,
                        child: const Text('Leggi modalità'),
                      ),
                    const Spacer(),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                width: 260,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text(
                          'Controlli',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 12),
                        DropdownButtonFormField<String>(
                          value: selectedResolution,
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: 'Risoluzione',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                          items: presetModes.keys
                              .map(
                                (r) => DropdownMenuItem<String>(
                                  value: r,
                                  child: Text(r),
                                ),
                              )
                              .toList(),
                          onChanged: presetModes.isEmpty
                              ? null
                              : (value) {
                                  if (value == null) return;
                                  final newFps = presetModes[value] ?? [];
                                  setState(() {
                                    selectedResolution = value;
                                    selectedFps = newFps.contains(selectedFps)
                                        ? selectedFps
                                        : (newFps.isNotEmpty ? newFps.first : null);
                                  });
                                },
                        ),
                        const SizedBox(height: 12),
                        DropdownButtonFormField<int>(
                          value: fpsOptions.contains(selectedFps) ? selectedFps : null,
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: 'FPS',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                          items: fpsOptions
                              .map(
                                (fps) => DropdownMenuItem<int>(
                                  value: fps,
                                  child: Text('$fps fps'),
                                ),
                              )
                              .toList(),
                          onChanged: fpsOptions.isEmpty
                              ? null
                              : (value) {
                                  setState(() {
                                    selectedFps = value;
                                  });
                                },
                        ),
                        const SizedBox(height: 12),
                        FilledButton(
                          onPressed: (selectedResolution != null && selectedFps != null)
                              ? _startPreview
                              : null,
                          child: const Text('Avvia preview'),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}