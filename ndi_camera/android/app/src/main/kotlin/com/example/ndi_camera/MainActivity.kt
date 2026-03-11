package com.example.ndi_camera

import android.content.Intent
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "ndi_camera/channel"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startPreview" -> {
                        val resolution = call.argument<String>("resolution")
                        val fps = call.argument<Int>("fps")

                        val intent = Intent(this, CameraPreviewActivity::class.java)
                        intent.putExtra("resolution", resolution)
                        intent.putExtra("fps", fps)
                        startActivity(intent)
                        result.success(true)
                    }

                    "getPresetModes" -> {
                        val modes = CameraUtils.getPresetModes(this)
                        result.success(modes)
                    }

                    else -> result.notImplemented()
                }
            }
    }
}