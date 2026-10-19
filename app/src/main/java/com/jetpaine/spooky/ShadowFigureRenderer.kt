package com.jetpaine.spooky

import android.opengl.GLES30

/**
 * Draws [ShadowFigure] particles as soft point sprites. Each fragment is compared against the
 * ARCore depth map: where real-world geometry is closer to the camera than the particle, the
 * particle fades out, so the figure appears to pass behind cars, trees, people, etc.
 */
class ShadowFigureRenderer {
    private var program = 0
    private var vbo = 0
    private var uView = 0
    private var uProj = 0
    private var uPointScale = 0
    private var uDepthTexture = 0
    private var uDepthUvTransform = 0
    private var uViewportSize = 0
    private var uOcclusion = 0
    private var uTime = 0

    private val buffer = ShaderUtil.floatBuffer(ShadowFigure.MAX_PARTICLES * ShadowFigure.FLOATS_PER_PARTICLE)

    fun createOnGlThread() {
        program = ShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uView = GLES30.glGetUniformLocation(program, "u_View")
        uProj = GLES30.glGetUniformLocation(program, "u_Proj")
        uPointScale = GLES30.glGetUniformLocation(program, "u_PointScale")
        uDepthTexture = GLES30.glGetUniformLocation(program, "u_DepthTexture")
        uDepthUvTransform = GLES30.glGetUniformLocation(program, "u_DepthUvTransform")
        uViewportSize = GLES30.glGetUniformLocation(program, "u_ViewportSize")
        uOcclusion = GLES30.glGetUniformLocation(program, "u_Occlusion")
        uTime = GLES30.glGetUniformLocation(program, "u_Time")

        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
    }

    fun draw(
        figure: ShadowFigure,
        view: FloatArray,
        proj: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        depthTexture: DepthTexture,
        depthUvTransform: FloatArray,
        occlusion: Boolean,
        time: Float,
    ) {
        val count = figure.particleCount
        if (count == 0) return
        val floats = count * ShadowFigure.FLOATS_PER_PARTICLE
        buffer.position(0)
        buffer.put(figure.particleData, 0, floats)
        buffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, buffer, GLES30.GL_DYNAMIC_DRAW)

        GLES30.glUseProgram(program)
        val stride = ShadowFigure.FLOATS_PER_PARTICLE * 4
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 32)
        for (i in 0..3) GLES30.glEnableVertexAttribArray(i)

        GLES30.glUniformMatrix4fv(uView, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(uProj, 1, false, proj, 0)
        // proj[5] = 1 / tan(fovY / 2): converts metres at 1 m distance into pixels.
        GLES30.glUniform1f(uPointScale, viewportHeight * proj[5] * 0.5f)
        GLES30.glUniform2f(uViewportSize, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniformMatrix3fv(uDepthUvTransform, 1, false, depthUvTransform, 0)
        GLES30.glUniform1f(uOcclusion, if (occlusion) 1f else 0f)
        GLES30.glUniform1f(uTime, time)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.textureId)
        GLES30.glUniform1i(uDepthTexture, 0)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        for (i in 0..3) GLES30.glDisableVertexAttribArray(i)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        const val VERTEX_SHADER = """#version 300 es
uniform mat4 u_View;
uniform mat4 u_Proj;
uniform float u_PointScale;
layout(location = 0) in vec3 a_Position;
layout(location = 1) in float a_Size;
layout(location = 2) in vec4 a_Color;
layout(location = 3) in float a_Seed;
out vec4 v_Color;
out float v_Seed;
out float v_DepthMm;
void main() {
    vec4 viewPos = u_View * vec4(a_Position, 1.0);
    gl_Position = u_Proj * viewPos;
    float dist = max(-viewPos.z, 0.05);
    gl_PointSize = clamp(a_Size * u_PointScale / dist, 1.0, 512.0);
    v_Color = a_Color;
    v_Seed = a_Seed;
    // ARCore depth is distance along the camera's optical axis, same as view-space -z.
    v_DepthMm = dist * 1000.0;
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
uniform sampler2D u_DepthTexture;
uniform mat3 u_DepthUvTransform;
uniform vec2 u_ViewportSize;
uniform float u_Occlusion;
uniform float u_Time;
in vec4 v_Color;
in float v_Seed;
in float v_DepthMm;
out vec4 o_FragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float depthMm(vec2 uv) {
    vec2 rg = texture(u_DepthTexture, uv).rg;
    return dot(rg, vec2(255.0, 256.0 * 255.0));
}

// 1 = particle in front of the real world, 0 = hidden behind it. Same model as Google's
// hello_ar occlusion sample: a depth-proportional soft band, and "no data" counts as visible.
float visibilityAt(vec2 uv, float assetMm) {
    float realMm = depthMm(uv);
    const float kTolerancePerMm = 0.015;
    float occ = clamp(0.5 * (realMm - assetMm) / (kTolerancePerMm * assetMm) + 0.5, 0.0, 1.0);
    float noDataNear = 1.0 - clamp((realMm - 150.0) / 50.0, 0.0, 1.0);
    float noDataFar = clamp((realMm - 7500.0) / 500.0, 0.0, 1.0);
    return max(occ, max(noDataNear, noDataFar));
}

float visibility(float assetMm) {
    vec2 ndc = gl_FragCoord.xy / u_ViewportSize * 2.0 - 1.0;
    vec2 uv = (u_DepthUvTransform * vec3(ndc, 1.0)).xy;
    // Small cross blur to soften the blocky edges of the low-res depth map.
    vec2 texel = 1.5 / vec2(textureSize(u_DepthTexture, 0));
    float v = visibilityAt(uv, assetMm) * 2.0;
    v += visibilityAt(uv + vec2(texel.x, 0.0), assetMm);
    v += visibilityAt(uv - vec2(texel.x, 0.0), assetMm);
    v += visibilityAt(uv + vec2(0.0, texel.y), assetMm);
    v += visibilityAt(uv - vec2(0.0, texel.y), assetMm);
    return v / 6.0;
}

void main() {
    vec2 pc = gl_PointCoord - 0.5;
    float r = length(pc) * 2.0;
    if (r > 1.0) discard;
    float n = valueNoise(gl_PointCoord * 3.0 + vec2(v_Seed * 17.0, u_Time * 0.4 + v_Seed * 5.0));
    float alpha = v_Color.a * pow(1.0 - r, 1.6) * mix(0.45, 1.0, n);
    if (alpha < 0.003) discard;
    if (u_Occlusion > 0.5) {
        alpha *= visibility(v_DepthMm);
        if (alpha < 0.003) discard;
    }
    o_FragColor = vec4(v_Color.rgb, alpha);
}
"""
    }
}
