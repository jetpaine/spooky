package com.jetpaine.spooky

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/** Draws the camera feed full screen, optionally tinted with the depth map for debugging. */
class BackgroundRenderer {
    var cameraTextureId = 0
        private set

    private var program = 0
    private var uCamera = 0
    private var uDepth = 0
    private var uShowDepth = 0

    private val quadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadBuffer = ShaderUtil.floatBuffer(8).apply { put(quadNdc); position(0) }
    private val texCoords = FloatArray(8)
    private val texCoordBuffer = ShaderUtil.floatBuffer(8)

    fun createOnGlThread() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cameraTextureId = ids[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, cameraTextureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        program = ShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uCamera = GLES30.glGetUniformLocation(program, "u_CameraTexture")
        uDepth = GLES30.glGetUniformLocation(program, "u_DepthTexture")
        uShowDepth = GLES30.glGetUniformLocation(program, "u_ShowDepth")
    }

    fun draw(frame: Frame, depthTexture: DepthTexture, showDepth: Boolean) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
            texCoordBuffer.position(0)
            texCoordBuffer.put(texCoords)
            texCoordBuffer.position(0)
        }
        if (frame.timestamp == 0L) return // Camera hasn't produced an image yet.

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(uCamera, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.textureId)
        GLES30.glUniform1i(uDepth, 1)
        GLES30.glUniform1f(uShowDepth, if (showDepth) 1f else 0f)

        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glDepthMask(true)
    }

    private companion object {
        const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 a_Position;
layout(location = 1) in vec2 a_TexCoord;
out vec2 v_TexCoord;
void main() {
    gl_Position = vec4(a_Position, 0.0, 1.0);
    v_TexCoord = a_TexCoord;
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES u_CameraTexture;
uniform sampler2D u_DepthTexture;
uniform float u_ShowDepth;
in vec2 v_TexCoord;
out vec4 o_FragColor;

// Near = red, then yellow, green, far = blue (0..8 m).
vec3 depthColor(float t) {
    vec3 c = mix(vec3(1.0, 0.15, 0.1), vec3(1.0, 0.85, 0.1), smoothstep(0.0, 0.3, t));
    c = mix(c, vec3(0.1, 0.85, 0.3), smoothstep(0.3, 0.6, t));
    return mix(c, vec3(0.15, 0.3, 1.0), smoothstep(0.6, 1.0, t));
}

void main() {
    vec4 color = texture(u_CameraTexture, v_TexCoord);
    if (u_ShowDepth > 0.5) {
        vec2 depthRg = texture(u_DepthTexture, v_TexCoord).rg;
        float mm = dot(depthRg, vec2(255.0, 256.0 * 255.0));
        vec3 tint = mm < 1.0 ? vec3(0.5) : depthColor(clamp(mm / 8000.0, 0.0, 1.0));
        color.rgb = mix(color.rgb, tint, 0.6);
    }
    o_FragColor = color;
}
"""
    }
}
