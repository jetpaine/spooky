package com.jetpaine.spooky

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A procedural "smoke shadow" humanoid. A stick-figure skeleton driven by a simple walk cycle is
 * fleshed out with a few hundred soft, dark particles, plus wisps that peel off and rise.
 *
 * This is pure CPU logic; [ShadowFigureRenderer] draws [particleData] as point sprites.
 */
class ShadowFigure(heightMeters: Float = 1.75f) {

    /** Path markers (one per waypoint) are drawn when true. */
    var showMarkers = true

    val particleData = FloatArray(MAX_PARTICLES * FLOATS_PER_PARTICLE)
    var particleCount = 0
        private set
    var visible = false
        private set
    val position = FloatArray(3)

    private val s = heightMeters / 1.75f
    private val rng = Random(1234)

    private var yaw = 0f
    private var targetIndex = 0
    private var gaitPhase = 0f
    private var gaitAmount = 0f
    private var time = 0f

    // Joints in figure-local space (x right, y up, z forward).
    private val joints = Array(JOINT_COUNT) { FloatArray(3) }

    // Body particles: attached to a bone at parameter t with a fixed local offset.
    private val bodyBone = IntArray(BODY_PARTICLES)
    private val bodyT = FloatArray(BODY_PARTICLES)
    private val bodyOffset = FloatArray(BODY_PARTICLES * 3)
    private val bodySize = FloatArray(BODY_PARTICLES)
    private val bodySeed = FloatArray(BODY_PARTICLES)

    // Wisps: world-space particles that detach from the body and drift upward.
    private val wispAlive = BooleanArray(WISP_PARTICLES)
    private val wispPos = FloatArray(WISP_PARTICLES * 3)
    private val wispVel = FloatArray(WISP_PARTICLES * 3)
    private val wispAge = FloatArray(WISP_PARTICLES)
    private val wispLife = FloatArray(WISP_PARTICLES)
    private val wispSeed = FloatArray(WISP_PARTICLES)

    private val tmp = FloatArray(3)

    init {
        // Distribute body particles over bones in proportion to bone volume in the rest pose.
        computeSkeleton(0f, 0f, 0f)
        val weights = FloatArray(BONES.size) { b ->
            val a = joints[BONES[b][0]]
            val c = joints[BONES[b][1]]
            val len = max(hypot(hypot(c[0] - a[0], c[1] - a[1]), c[2] - a[2]), 0.05f * s)
            len * BONE_RADIUS[b] * BONE_RADIUS[b]
        }
        val total = weights.sum()
        for (i in 0 until BODY_PARTICLES) {
            var pick = rng.nextFloat() * total
            var bone = 0
            while (bone < BONES.size - 1 && pick > weights[bone]) {
                pick -= weights[bone]
                bone++
            }
            bodyBone[i] = bone
            bodyT[i] = rng.nextFloat()
            randomInUnitSphere(tmp)
            // Bias toward the bone so the core is dense and the edges are fuzzy.
            val r = BONE_RADIUS[bone] * s
            bodyOffset[i * 3] = tmp[0] * r
            bodyOffset[i * 3 + 1] = tmp[1] * r
            bodyOffset[i * 3 + 2] = tmp[2] * r
            bodySize[i] = (0.07f + 0.06f * rng.nextFloat()) * s
            bodySeed[i] = rng.nextFloat()
        }
        for (w in 0 until WISP_PARTICLES) wispSeed[w] = rng.nextFloat()
    }

    fun reset() {
        visible = false
        particleCount = 0
    }

    /**
     * Advance the simulation.
     * @param waypoints world-space ground points; the figure walks a loop through them.
     * @param cameraPos world-space camera position; an idle figure turns to face it.
     */
    fun update(dt: Float, waypoints: List<FloatArray>, cameraPos: FloatArray) {
        time += dt
        if (waypoints.isEmpty()) {
            reset()
            return
        }
        if (!visible) {
            waypoints[0].copyInto(position)
            yaw = atan2(cameraPos[0] - position[0], cameraPos[2] - position[2])
            targetIndex = 0
            for (w in 0 until WISP_PARTICLES) {
                wispAlive[w] = false
                wispAge[w] = -rng.nextFloat() * 2.5f // Stagger the first wisps.
            }
            visible = true
        }

        val speed = move(dt, waypoints, cameraPos)
        gaitAmount += (speed / WALK_SPEED - gaitAmount) * min(1f, dt * 5f)
        gaitPhase = (gaitPhase + speed * dt / STRIDE * TWO_PI) % TWO_PI
        computeSkeleton(gaitPhase, gaitAmount, time)

        var n = 0
        n = emitBody(n)
        n = emitWisps(n, dt)
        if (showMarkers) n = emitMarkers(n, waypoints)
        n = emitEyes(n) // Last, so they draw on top of the smoke.
        particleCount = n
    }

    /** Steers toward the current waypoint; returns the forward speed used this frame. */
    private fun move(dt: Float, waypoints: List<FloatArray>, cameraPos: FloatArray): Float {
        val count = waypoints.size
        if (targetIndex >= count) targetIndex = 0
        var target = waypoints[targetIndex]
        var dist = hypot(target[0] - position[0], target[2] - position[2])
        if (count > 1 && dist < ARRIVE_DIST) {
            targetIndex = (targetIndex + 1) % count
            target = waypoints[targetIndex]
            dist = hypot(target[0] - position[0], target[2] - position[2])
        }

        var speed = 0f
        if (dist >= ARRIVE_DIST) {
            val desired = atan2(target[0] - position[0], target[2] - position[2])
            turnToward(desired, TURN_RATE * dt)
            val facing = max(0f, cos(wrapAngle(desired - yaw)))
            speed = WALK_SPEED * facing
            val step = min(speed * dt, dist)
            position[0] += sin(yaw) * step
            position[2] += cos(yaw) * step
        } else {
            // Idle: slowly turn to stare at the viewer.
            turnToward(atan2(cameraPos[0] - position[0], cameraPos[2] - position[2]), 1.2f * dt)
        }
        position[1] += (target[1] - position[1]) * min(1f, dt * 3f)
        return speed
    }

    private fun turnToward(desired: Float, maxStep: Float) {
        yaw = wrapAngle(yaw + wrapAngle(desired - yaw).coerceIn(-maxStep, maxStep))
    }

    private fun computeSkeleton(phase: Float, amount: Float, t: Float) {
        val thigh = 0.46f * s
        val shin = 0.46f * s
        val upperArm = 0.34f * s
        val forearm = 0.34f * s
        val hipHalf = 0.1f * s

        // Legs relative to the hip joint. The knee bends while the leg swings forward.
        val kneeY = FloatArray(2)
        val kneeZ = FloatArray(2)
        val footY = FloatArray(2)
        val footZ = FloatArray(2)
        for (side in 0..1) {
            val ph = phase + side * PI.toFloat()
            val thighAngle = 0.42f * amount * sin(ph)
            val bend = 0.08f + 0.75f * amount * max(0f, cos(ph))
            val shinAngle = thighAngle - bend
            kneeY[side] = -thigh * cos(thighAngle)
            kneeZ[side] = thigh * sin(thighAngle)
            footY[side] = kneeY[side] - shin * cos(shinAngle)
            footZ[side] = kneeZ[side] + shin * sin(shinAngle)
        }
        // Keep the lowest foot on the ground; this also produces the walking bob.
        val hipY = 0.03f * s - min(footY[0], footY[1])
        val sway = 0.025f * s * amount * sin(phase)

        set(PELVIS, sway, hipY, 0f)
        for (side in 0..1) {
            val sign = if (side == 0) -1f else 1f
            val hip = if (side == 0) L_HIP else R_HIP
            set(hip, sway + sign * hipHalf, hipY, 0f)
            set(hip + 1, sway + sign * hipHalf, hipY + kneeY[side], kneeZ[side])
            set(hip + 2, sway + sign * hipHalf * 0.9f, hipY + footY[side], footZ[side])
        }

        // Hunched spine, leaning further forward when walking.
        val lean = 0.14f + 0.08f * amount
        val breathe = 0.008f * s * sin(t * 1.7f)
        val p = joints[PELVIS]
        set(CHEST, p[0], p[1] + 0.40f * s * cos(lean) + breathe, p[2] + 0.40f * s * sin(lean))
        val c = joints[CHEST]
        set(NECK, c[0], c[1] + 0.17f * s * cos(lean + 0.3f), c[2] + 0.17f * s * sin(lean + 0.3f))
        val nk = joints[NECK]
        set(HEAD, nk[0] + 0.02f * s * sin(t * 0.7f), nk[1] + 0.12f * s, nk[2] + 0.05f * s)

        // Long, dangling arms swinging opposite to the leg on the same side.
        for (side in 0..1) {
            val sign = if (side == 0) -1f else 1f
            val shoulder = if (side == 0) L_SHOULDER else R_SHOULDER
            val ph = phase + side * PI.toFloat()
            val swing = -0.38f * amount * sin(ph) + 0.05f * sin(t * 1.3f + side * 2f)
            set(shoulder, c[0] + sign * 0.2f * s, c[1] + 0.06f * s, c[2] - 0.02f * s)
            val sh = joints[shoulder]
            set(shoulder + 1, sh[0] + sign * 0.03f * s, sh[1] - upperArm * cos(swing), sh[2] + upperArm * sin(swing))
            val el = joints[shoulder + 1]
            val fore = swing + 0.3f + 0.15f * amount
            set(shoulder + 2, el[0] + sign * 0.01f * s, el[1] - forearm * cos(fore), el[2] + forearm * sin(fore))
        }
        val h = joints[HEAD]
        set(HEAD_TOP, h[0], h[1] + 0.08f * s, h[2] + 0.01f * s)
    }

    private fun emitBody(start: Int): Int {
        var n = start
        for (i in 0 until BODY_PARTICLES) {
            val bone = BONES[bodyBone[i]]
            val a = joints[bone[0]]
            val b = joints[bone[1]]
            val t = bodyT[i]
            val seed = bodySeed[i]
            val wob = 0.02f * s
            val lx = a[0] + (b[0] - a[0]) * t + bodyOffset[i * 3] + wob * sin(time * 1.3f + seed * 40f)
            val ly = a[1] + (b[1] - a[1]) * t + bodyOffset[i * 3 + 1] + wob * sin(time * 1.7f + seed * 70f)
            val lz = a[2] + (b[2] - a[2]) * t + bodyOffset[i * 3 + 2] + wob * sin(time * 1.1f + seed * 90f)
            // Legs dissolve into smoke near the ground.
            val fade = 0.3f + 0.7f * smoothstep(0f, 0.5f * s, ly)
            toWorld(lx, ly, lz, tmp)
            put(n++, tmp[0], tmp[1], tmp[2], bodySize[i], 0.02f, 0.02f, 0.03f, 0.17f * fade, seed)
        }
        return n
    }

    private fun emitWisps(start: Int, dt: Float): Int {
        var n = start
        for (w in 0 until WISP_PARTICLES) {
            wispAge[w] += dt
            if (!wispAlive[w] && wispAge[w] < 0f) continue
            if (!wispAlive[w] || wispAge[w] >= wispLife[w]) spawnWisp(w)
            val o = w * 3
            val seed = wispSeed[w]
            wispVel[o] += 0.08f * sin(time * 0.9f + seed * 30f) * dt
            wispVel[o + 2] += 0.08f * cos(time * 0.7f + seed * 50f) * dt
            wispPos[o] += wispVel[o] * dt
            wispPos[o + 1] += wispVel[o + 1] * dt
            wispPos[o + 2] += wispVel[o + 2] * dt
            val life = wispAge[w] / wispLife[w]
            val alpha = 0.10f * sin(PI.toFloat() * life)
            val size = (0.08f + 0.22f * life) * s
            put(n++, wispPos[o], wispPos[o + 1], wispPos[o + 2], size, 0.05f, 0.05f, 0.06f, alpha, seed)
        }
        return n
    }

    private fun spawnWisp(w: Int) {
        // Start at the current world position of a random body particle (already emitted this frame).
        val src = rng.nextInt(BODY_PARTICLES) * FLOATS_PER_PARTICLE
        val o = w * 3
        wispPos[o] = particleData[src]
        wispPos[o + 1] = particleData[src + 1]
        wispPos[o + 2] = particleData[src + 2]
        wispVel[o] = (rng.nextFloat() - 0.5f) * 0.12f
        wispVel[o + 1] = 0.15f + 0.25f * rng.nextFloat()
        wispVel[o + 2] = (rng.nextFloat() - 0.5f) * 0.12f
        wispAge[w] = 0f
        wispLife[w] = 1.6f + 1.6f * rng.nextFloat()
        wispAlive[w] = true
    }

    private fun emitMarkers(start: Int, waypoints: List<FloatArray>): Int {
        var n = start
        for (i in 0 until min(waypoints.size, MAX_MARKERS)) {
            val p = waypoints[i]
            val pulse = 0.4f + 0.2f * sin(time * 3f + i)
            put(n++, p[0], p[1] + 0.02f, p[2], 0.06f, 0.3f, 0.9f, 1f, pulse, i * 0.13f)
        }
        return n
    }

    private fun emitEyes(start: Int): Int {
        var n = start
        val blink = if (time % 4.7f < 0.15f) 0.05f else 1f
        val flicker = (0.85f + 0.15f * sin(time * 23f)) * blink
        val h = joints[HEAD]
        // Halos first, then the hot cores.
        for (pass in 0..1) {
            for (side in 0..1) {
                val sign = if (side == 0) -1f else 1f
                toWorld(h[0] + sign * 0.045f * s, h[1] + 0.015f * s, h[2] + 0.09f * s, tmp)
                if (pass == 0) {
                    put(n++, tmp[0], tmp[1], tmp[2], 0.11f * s, 0.9f, 0.05f, 0.02f, 0.25f * flicker, 0.5f)
                } else {
                    put(n++, tmp[0], tmp[1], tmp[2], 0.03f * s, 1f, 0.35f, 0.2f, 0.95f * flicker, 0.9f)
                }
            }
        }
        return n
    }

    private fun toWorld(x: Float, y: Float, z: Float, out: FloatArray) {
        val c = cos(yaw)
        val sn = sin(yaw)
        out[0] = position[0] + x * c + z * sn
        out[1] = position[1] + y
        out[2] = position[2] - x * sn + z * c
    }

    private fun set(joint: Int, x: Float, y: Float, z: Float) {
        joints[joint][0] = x
        joints[joint][1] = y
        joints[joint][2] = z
    }

    private fun put(
        i: Int, x: Float, y: Float, z: Float, size: Float,
        r: Float, g: Float, b: Float, a: Float, seed: Float,
    ) {
        val o = i * FLOATS_PER_PARTICLE
        particleData[o] = x
        particleData[o + 1] = y
        particleData[o + 2] = z
        particleData[o + 3] = size
        particleData[o + 4] = r
        particleData[o + 5] = g
        particleData[o + 6] = b
        particleData[o + 7] = a
        particleData[o + 8] = seed
    }

    private fun randomInUnitSphere(out: FloatArray) {
        while (true) {
            val x = rng.nextFloat() * 2f - 1f
            val y = rng.nextFloat() * 2f - 1f
            val z = rng.nextFloat() * 2f - 1f
            val d2 = x * x + y * y + z * z
            if (d2 <= 1f && d2 > 1e-6f) {
                // Remap radius r -> r^1.5 so density is highest near the bone and thins outward.
                val k = d2.pow(0.25f)
                out[0] = x * k; out[1] = y * k; out[2] = z * k
                return
            }
        }
    }

    companion object {
        /** xyz, size (m), rgba, seed */
        const val FLOATS_PER_PARTICLE = 9
        private const val BODY_PARTICLES = 420
        private const val WISP_PARTICLES = 200
        private const val EYE_PARTICLES = 4
        private const val MAX_MARKERS = 16
        const val MAX_PARTICLES = BODY_PARTICLES + WISP_PARTICLES + EYE_PARTICLES + MAX_MARKERS

        private const val WALK_SPEED = 0.8f // m/s
        private const val TURN_RATE = 2.5f // rad/s
        private const val STRIDE = 1.3f // m per full gait cycle
        private const val ARRIVE_DIST = 0.15f
        private const val TWO_PI = (2.0 * PI).toFloat()

        private const val PELVIS = 0
        private const val CHEST = 1
        private const val NECK = 2
        private const val HEAD = 3
        private const val HEAD_TOP = 4
        private const val L_SHOULDER = 5 // +1 elbow, +2 hand
        private const val R_SHOULDER = 8
        private const val L_HIP = 11 // +1 knee, +2 foot
        private const val R_HIP = 14
        private const val JOINT_COUNT = 17

        private val BONES = arrayOf(
            intArrayOf(PELVIS, CHEST),
            intArrayOf(CHEST, NECK),
            intArrayOf(NECK, HEAD),
            intArrayOf(HEAD, HEAD_TOP),
            intArrayOf(L_SHOULDER, R_SHOULDER),
            intArrayOf(L_SHOULDER, L_SHOULDER + 1),
            intArrayOf(L_SHOULDER + 1, L_SHOULDER + 2),
            intArrayOf(R_SHOULDER, R_SHOULDER + 1),
            intArrayOf(R_SHOULDER + 1, R_SHOULDER + 2),
            intArrayOf(L_HIP, R_HIP),
            intArrayOf(L_HIP, L_HIP + 1),
            intArrayOf(L_HIP + 1, L_HIP + 2),
            intArrayOf(R_HIP, R_HIP + 1),
            intArrayOf(R_HIP + 1, R_HIP + 2),
        )
        private val BONE_RADIUS = floatArrayOf(
            0.17f, 0.12f, 0.08f, 0.12f, 0.08f,
            0.06f, 0.05f, 0.06f, 0.05f,
            0.11f, 0.08f, 0.06f, 0.08f, 0.06f,
        )

        private fun wrapAngle(a: Float): Float {
            var r = a % TWO_PI
            if (r > PI) r -= TWO_PI
            if (r < -PI) r += TWO_PI
            return r
        }

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
