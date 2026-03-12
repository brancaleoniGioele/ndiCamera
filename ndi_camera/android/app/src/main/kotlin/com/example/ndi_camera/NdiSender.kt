package com.example.ndi_camera

import android.util.Size

/**
 * Bridge verso il layer nativo NDI.
 * Il codice C++ corrispondente è in src/main/cpp/ndi_sender.cpp
 */
object NdiSender {

    init {
        System.loadLibrary("ndi_sender")
    }

    // --- Native methods (implementati in ndi_sender.cpp) ---

    /** Inizializza l'istanza NDI send con il nome sorgente visibile in rete */
    external fun init(sourceName: String): Boolean

    /** Invia un frame YUV_420_888 via NDI. Chiamato per ogni frame dalla camera. */
    external fun sendFrame(
        yPlane: ByteArray, uPlane: ByteArray, vPlane: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int,
        timestampUs: Long
    ): Boolean

    /** Rilascia le risorse NDI */
    external fun release()

    /** Ritorna l'IP locale su cui sta trasmettendo */
    external fun getLocalIp(): String
}