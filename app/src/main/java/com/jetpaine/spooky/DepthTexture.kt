package com.jetpaine.spooky

import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException

/**
 * Uploads ARCore's smoothed 16-bit depth image (millimetres) into a GL_RG8 texture, so shaders
 * can recover depth as `r * 255 + g * 255 * 256`.
 */
class DepthTexture {
    var textureId = 0
        private set
    var hasData = false
        private set
    var width = 0
        private set
    var height = 0
        private set

    private var lastTimestamp = -1L

    fun createOnGlThread() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // NEAREST: linear filtering would blend the low and high bytes independently and corrupt depth.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        hasData = false
        lastTimestamp = -1L
    }

    /** Returns true if a depth image is available (this frame or a previous one). */
    fun update(frame: Frame): Boolean {
        try {
            frame.acquireDepthImage16Bits().use { image ->
                if (image.timestamp == lastTimestamp) return hasData
                lastTimestamp = image.timestamp
                val plane = image.planes[0]
                width = image.width
                height = image.height
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / 2)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, width, height, 0,
                    GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, plane.buffer
                )
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
                hasData = true
            }
        } catch (e: NotYetAvailableException) {
            // Depth usually takes a moment (and some phone movement) to start; keep the last image.
        }
        return hasData
    }

    companion object {
        /**
         * Affine transform (column-major mat3) from OpenGL NDC to depth-texture UV, accounting for
         * display rotation and the crop between camera image and screen.
         */
        fun computeNdcToUvTransform(frame: Frame, out: FloatArray) {
            val basis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
            val t = FloatArray(6)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, basis,
                Coordinates2d.TEXTURE_NORMALIZED, t
            )
            out[0] = t[2] - t[0]; out[1] = t[3] - t[1]; out[2] = 0f
            out[3] = t[4] - t[0]; out[4] = t[5] - t[1]; out[5] = 0f
            out[6] = t[0]; out[7] = t[1]; out[8] = 1f
        }
    }
}
