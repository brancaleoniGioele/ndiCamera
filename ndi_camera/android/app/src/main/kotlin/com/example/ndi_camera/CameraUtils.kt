package com.example.ndi_camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

object CameraUtils {

    private val presetSizes = linkedMapOf(
        "720p" to Size(1280, 720),
        "1080p" to Size(1920, 1080),
        "1440p" to Size(2560, 1440),
        "2160p" to Size(3840, 2160)
    )

    fun getPresetModes(context: Context): Map<String, List<Int>> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = linkedMapOf<String, MutableSet<Int>>()

        for (label in presetSizes.keys) {
            result[label] = linkedSetOf()
        }

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) continue

            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: continue

            val normalSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()

            val normalAeRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )?.toList() ?: emptyList()

            val normalMaxFps = normalAeRanges.map { it.upper }.maxOrNull() ?: 30

            val highSpeedSizes = try {
                map.highSpeedVideoSizes?.toSet() ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }

            for ((label, size) in presetSizes) {
                if (normalSizes.contains(size)) {
                    result[label]?.add(30)

                    if (normalMaxFps >= 60) {
                        result[label]?.add(60)
                    }
                }

                if (highSpeedSizes.contains(size)) {
                    val hsRanges = try {
                        map.getHighSpeedVideoFpsRangesFor(size)?.toList() ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }

                    val maxUpper = hsRanges.map { it.upper }.maxOrNull() ?: 0

                    if (maxUpper >= 60) {
                        result[label]?.add(60)
                    }
                    if (maxUpper >= 120) {
                        result[label]?.add(120)
                    }
                }
            }

            break
        }

        return result
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.toList().sorted() }
    }

    fun sizeFromLabel(label: String?): Size? {
        return presetSizes[label]
    }
}