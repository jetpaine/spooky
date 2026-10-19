package com.jetpaine.spooky

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var occlusionButton: Button
    private lateinit var depthButton: Button

    private var session: Session? = null
    private var installRequested = false
    @Volatile private var depthSupported = false

    private val backgroundRenderer = BackgroundRenderer()
    private val depthTexture = DepthTexture()
    private val shadowRenderer = ShadowFigureRenderer()
    private val figure = ShadowFigure()

    // UI thread -> GL thread.
    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()
    @Volatile private var occlusionEnabled = true
    @Volatile private var showDepth = false
    @Volatile private var clearRequested = false

    // GL thread only.
    private val waypoints = mutableListOf<Anchor>()
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportChanged = false
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val depthUvTransform = FloatArray(9)
    private var lastFrameNs = 0L
    private var lastStatusNs = 0L
    private var elapsed = 0f
    private var fps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById<GLSurfaceView>(R.id.surface_view)
        statusText = findViewById<TextView>(R.id.status_text)
        occlusionButton = findViewById<Button>(R.id.occlusion_button)
        depthButton = findViewById<Button>(R.id.depth_button)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(3)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                pendingTaps.add(floatArrayOf(e.x, e.y))
                return true
            }
        })
        surfaceView.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

        occlusionButton.setOnClickListener {
            occlusionEnabled = !occlusionEnabled
            updateButtons()
        }
        depthButton.setOnClickListener {
            showDepth = !showDepth
            updateButtons()
        }
        findViewById<Button>(R.id.clear_button).setOnClickListener { clearRequested = true }
        updateButtons()
    }

    private fun updateButtons() {
        occlusionButton.text = if (occlusionEnabled) "Occlusion: ON" else "Occlusion: OFF"
        depthButton.text = if (showDepth) "Depth view: ON" else "Depth view: OFF"
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                val status = ArCoreApk.getInstance().requestInstall(this, !installRequested)
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    // Play Store install flow is showing; onResume runs again when it returns.
                    installRequested = true
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
                    return
                }
                session = Session(this).also { configureSession(it) }
            } catch (e: UnavailableException) {
                fail("ARCore unavailable: ${e.javaClass.simpleName}", e)
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            fail("Camera not available. Try restarting the app.", e)
            session = null
            return
        }
        surfaceView.onResume()
        viewportChanged = true
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun configureSession(session: Session) {
        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config = Config(session)
        config.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        session.configure(config)
        if (!depthSupported) {
            Toast.makeText(this, "This device does not support the ARCore Depth API; occlusion disabled.", Toast.LENGTH_LONG).show()
        }
    }

    private fun fail(message: String, e: Exception) {
        Log.e(TAG, message, e)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        statusText.text = message
    }

    // ---- GLSurfaceView.Renderer (GL thread) ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        depthTexture.createOnGlThread()
        shadowRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (viewportChanged) {
            session.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            viewportChanged = false
        }
        try {
            session.setCameraTextureName(backgroundRenderer.cameraTextureId)
            val frame = session.update()
            val camera = frame.camera

            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNs = now
            elapsed += dt
            if (dt > 0f) fps += (1f / dt - fps) * 0.05f

            if (clearRequested) {
                clearRequested = false
                waypoints.forEach { it.detach() }
                waypoints.clear()
            }
            handleTaps(frame)

            val depthReady = depthSupported && depthTexture.update(frame)
            backgroundRenderer.draw(frame, depthTexture, showDepth && depthReady)

            val cam = camera.pose
            val cameraPos = floatArrayOf(cam.tx(), cam.ty(), cam.tz())
            val points = waypoints
                .filter { it.trackingState != TrackingState.STOPPED }
                .map { val p = it.pose; floatArrayOf(p.tx(), p.ty(), p.tz()) }
            figure.update(dt, points, cameraPos)

            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
                camera.getViewMatrix(viewMatrix, 0)
                DepthTexture.computeNdcToUvTransform(frame, depthUvTransform)
                shadowRenderer.draw(
                    figure, viewMatrix, projMatrix, viewportWidth, viewportHeight,
                    depthTexture, depthUvTransform, occlusionEnabled && depthReady, elapsed
                )
            }

            if (now - lastStatusNs > 250_000_000L) {
                lastStatusNs = now
                postStatus(frame, depthReady, cameraPos)
            }
        } catch (t: Throwable) {
            // Don't crash the GL thread on a transient ARCore error.
            Log.e(TAG, "Exception on the GL thread", t)
        }
    }

    private fun handleTaps(frame: Frame) {
        while (true) {
            val tap = pendingTaps.poll() ?: return
            if (frame.camera.trackingState != TrackingState.TRACKING) continue
            val hit = frame.hitTest(tap[0], tap[1]).firstOrNull { h ->
                when (val t = h.trackable) {
                    is Plane -> t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(h.hitPose)
                    // Depth hits work on any surface before planes are found; keep the ground-ish ones.
                    is DepthPoint -> h.hitPose.yAxis[1] > 0.8f
                    else -> false
                }
            }
            if (hit == null) {
                runOnUiThread {
                    Toast.makeText(this, "No ground found there yet; move the phone around a bit", Toast.LENGTH_SHORT).show()
                }
                continue
            }
            if (waypoints.size >= MAX_WAYPOINTS) waypoints.removeAt(0).detach()
            waypoints.add(hit.createAnchor())
        }
    }

    private fun postStatus(frame: Frame, depthReady: Boolean, cameraPos: FloatArray) {
        val camera = frame.camera
        val tracking = if (camera.trackingState == TrackingState.TRACKING) {
            "TRACKING"
        } else {
            "${camera.trackingState} (${camera.trackingFailureReason})"
        }
        val depth = when {
            !depthSupported -> "not supported on this device"
            !depthReady -> "waiting (move the phone)"
            else -> "${depthTexture.width}x${depthTexture.height}"
        }
        val distance = if (figure.visible) {
            val p = figure.position
            val d = Math.hypot(Math.hypot((p[0] - cameraPos[0]).toDouble(), (p[1] - cameraPos[1]).toDouble()), (p[2] - cameraPos[2]).toDouble())
            String.format(Locale.US, "%.2f m", d)
        } else {
            "-"
        }
        val text = buildString {
            appendLine("Tracking: $tracking")
            appendLine("Depth: $depth")
            appendLine("Waypoints: ${waypoints.size}   Figure: $distance   ${fps.toInt()} fps")
            append(
                if (waypoints.isEmpty()) "Tap the ground to summon the shadow."
                else "Tap more points to set its walking loop."
            )
        }
        runOnUiThread { statusText.text = text }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }

    private companion object {
        const val TAG = "Spooky"
        const val CAMERA_REQUEST = 1
        const val MAX_WAYPOINTS = 8
    }
}
