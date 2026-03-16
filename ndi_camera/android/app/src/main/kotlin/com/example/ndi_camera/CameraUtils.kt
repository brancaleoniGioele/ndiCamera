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
        val cameraId = "3"
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyMap()

        val result = linkedMapOf<String, MutableSet<Int>>()
        for (label in presetSizes.keys) {
            result[label] = linkedSetOf()
        }

        val normalSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()

        for ((label, size) in presetSizes) {
            if (normalSizes.contains(size)) {
                result[label]?.add(30)
            }

            val hsRanges = try {
                map.getHighSpeedVideoFpsRangesFor(size)?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (hsRanges.any { it.lower <= 60 && it.upper >= 60 }) {
                result[label]?.add(60)
            }

            if (hsRanges.any { it.lower <= 120 && it.upper >= 120 }) {
                result[label]?.add(120)
            }

            android.util.Log.d(
                "PRESET_DEBUG",
                "camera=$cameraId size=${size.width}x${size.height} hsRanges=$hsRanges"
            )
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
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null

            val ranges = map.getHighSpeedVideoFpsRangesFor(size)?.toList() ?: return null

            val exact = ranges.firstOrNull { it.lower == fps && it.upper == fps }
            if (exact != null) return exact

            ranges.firstOrNull { it.lower <= fps && it.upper >= fps }
        } catch (e: Exception) {
            null
        }
    }

    fun findBestBackCameraId(context: Context, size: Size?, fps: Int): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        data class Candidate(
            val id: String,
            val score: Long
        )

        val candidates = mutableListOf<Candidate>()

        for (id in manager.cameraIdList) {
            try {
                val chars = manager.getCameraCharacteristics(id)

                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                val normalSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
                val supportsNormal = size == null || normalSizes.contains(size)

                val hsRanges = if (size != null) {
                    try { map.getHighSpeedVideoFpsRangesFor(size)?.toList() ?: emptyList() }
                    catch (_: Exception) { emptyList() }
                } else emptyList()

                val supportsHs = size != null && fps > 30 &&
                    hsRanges.any { it.lower <= fps && it.upper >= fps }

                val supportsRequestedMode =
                    if (fps > 30) supportsHs else supportsNormal

                if (!supportsRequestedMode) continue

                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val area = if (pixelArray != null) {
                    pixelArray.width.toLong() * pixelArray.height.toLong()
                } else 0L

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalScore = focalLengths?.maxOrNull()?.times(1000)?.toLong() ?: 0L

                // Score: prima sensore più grande, poi focale più "normale"
                val score = area * 10_000L + focalScore

                candidates.add(Candidate(id, score))
            } catch (_: Exception) {
            }
        }

        return candidates.maxByOrNull { it.score }?.id
    }

    fun sizeFromLabel(label: String?): Size? = presetSizes[label]
}