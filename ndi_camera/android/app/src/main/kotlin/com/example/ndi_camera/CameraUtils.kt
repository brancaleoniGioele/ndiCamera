package com.example.ndi_camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.SurfaceTexture
import android.util.Range
import android.util.Size

object CameraUtils {

    private val presetSizes = linkedMapOf(
        "720p"  to Size(1280, 720),
        "1080p" to Size(1920, 1080),
        "1440p" to Size(2560, 1440),
        "2160p" to Size(3840, 2160)
    )

    /**
     * Trova la camera posteriore principale.
     * Su Xiaomi 11T ci sono più sensori posteriori — vogliamo quello con
     * LENS_FACING_BACK e livello hardware più alto (FULL o LEVEL_3).
     */
    fun findMainBackCameraId(context: Context): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var bestId: String? = null
        var bestLevel = -1

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: continue
            if (level > bestLevel) {
                bestLevel = level
                bestId = id
            }
        }
        return bestId
    }

    /**
     * Restituisce tutte le modalità disponibili come mappa "risoluzione" → [fps, fps, ...]
     * Controlla sia i range normali che la high speed map.
     * Su Xiaomi 11T, 1080p@60 è SOLO nella high speed map.
     */
    fun getPresetModes(context: Context): Map<String, List<Int>> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findMainBackCameraId(context) ?: return emptyMap()
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyMap()

        val result = linkedMapOf<String, MutableSet<Int>>()
        for (label in presetSizes.keys) result[label] = linkedSetOf()

        // --- Pipeline NORMALE ---
        val normalSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
        val normalRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()
        val normalMaxFps = normalRanges.maxOfOrNull { it.upper } ?: 30

        for ((label, size) in presetSizes) {
            if (!normalSizes.contains(size)) continue
            result[label]?.add(30)
            if (normalMaxFps >= 60) result[label]?.add(60)
        }

        // --- Pipeline HIGH SPEED (qui si nasconde 1080p@60 sullo Xiaomi 11T) ---
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasHighSpeed = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
        )

        if (hasHighSpeed) {
            val hsSizes = try { map.highSpeedVideoSizes?.toSet() ?: emptySet() }
                          catch (_: Exception) { emptySet() }

            for ((label, size) in presetSizes) {
                if (!hsSizes.contains(size)) continue
                val hsRanges = try { map.getHighSpeedVideoFpsRangesFor(size)?.toList() ?: emptyList() }
                               catch (_: Exception) { emptyList() }
                val maxFps = hsRanges.maxOfOrNull { it.upper } ?: 0
                if (maxFps >= 60)  result[label]?.add(60)
                if (maxFps >= 120) result[label]?.add(120)
            }
        }

        return result
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.toList().sorted() }
    }

    /**
     * Trova il Range<Int> HIGH SPEED esatto per una size+fps dati.
     * Preferisce range fisso [fps,fps] rispetto a range variabile.
     */
    fun findHighSpeedRange(context: Context, cameraId: String, size: Size, fps: Int): Range<Int>? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
            if (!capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
            )) return null

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val hsSizes = map.highSpeedVideoSizes ?: return null
            if (!hsSizes.contains(size)) return null

            val ranges = map.getHighSpeedVideoFpsRangesFor(size) ?: return null
            ranges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges.firstOrNull { it.lower <= fps && it.upper >= fps }
        } catch (_: Exception) { null }
    }

    fun sizeFromLabel(label: String?): Size? = presetSizes[label]
}