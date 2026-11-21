package com.sf12.rr3tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * The environment is uploaded to the same GLES context as the car. This avoids
 * SurfaceView z-order/alpha composition problems and keeps the panorama behind
 * the model regardless of Android's window compositor.
 */
internal class Rr3ModelViewer(
    context: Context,
    private val handle: Long,
    private val onFrameStatus: (String) -> Unit
) : FrameLayout(context) {
    private val glSurface = GLSurfaceView(context)
    private val disposed = AtomicBoolean(false)
    @Volatile private var rendererReady = false
    @Volatile private var hostPaused = false
    @Volatile private var yaw = 0.6f
    @Volatile private var pitch = 0.25f
    @Volatile private var zoom = 1f
    @Volatile private var surfaceWidth = 1
    @Volatile private var surfaceHeight = 1
    @Volatile private var cameraPanX = 0f
    @Volatile private var cameraPanY = 0f
    @Volatile private var cameraFovY = 0.75f
    @Volatile private var cameraPoseEnabled = false
    @Volatile private var cameraEye = FloatArray(3)
    @Volatile private var cameraTarget = FloatArray(3)
    @Volatile private var environmentEnabled = true
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f

    init {
        setBackgroundColor(UiPalette.background)
        glSurface.setEGLContextClientVersion(3)
        glSurface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurface.preserveEGLContextOnPause = true
        glSurface.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                rendererReady = false
                val status = try {
                    NativeBridge.nativeInitRr3ViewerGl(handle)
                } catch (error: Exception) {
                    error.message ?: "Could not initialise the OpenGL ES 3 viewer."
                }
                var environmentStatus = ""
                if (status.startsWith("OpenGL ES 3")) {
                    try {
                        val bitmap = context.assets.open("rr3_environment_360.jpg").use {
                            BitmapFactory.decodeStream(it)
                        } ?: throw IllegalStateException("The panorama image could not be decoded.")
                        val rgba = ByteArray(bitmap.width * bitmap.height * 4)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        var target = 0
                        for (pixel in pixels) {
                            rgba[target++] = ((pixel shr 16) and 0xff).toByte()
                            rgba[target++] = ((pixel shr 8) and 0xff).toByte()
                            rgba[target++] = (pixel and 0xff).toByte()
                            rgba[target++] = ((pixel ushr 24) and 0xff).toByte()
                        }
                        NativeBridge.nativeSetRr3ViewerEnvironment(handle, bitmap.width, bitmap.height, rgba)
                        bitmap.recycle()
                        NativeBridge.nativeUpdateRr3ViewerEnvironment(handle, environmentEnabled)
                        environmentStatus = " · panorama loaded"
                    } catch (error: Exception) {
                        environmentStatus = " · panorama unavailable: ${error.message ?: "decode/upload failed"}"
                    }
                    NativeBridge.nativeUpdateRr3ViewerCamera(handle, cameraPanX, cameraPanY, cameraFovY)
                    if (cameraPoseEnabled) {
                        NativeBridge.nativeSetRr3ViewerCameraPose(
                            handle, cameraEye[0], cameraEye[1], cameraEye[2],
                            cameraTarget[0], cameraTarget[1], cameraTarget[2], true
                        )
                    }
                }
                rendererReady = status.startsWith("OpenGL ES 3")
                post {
                    if (!disposed.get()) {
                        onFrameStatus(if (rendererReady) status + environmentStatus else "GPU viewer unavailable: $status")
                    }
                }
                if (rendererReady && !disposed.get()) glSurface.requestRender()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                surfaceWidth = width.coerceAtLeast(1)
                surfaceHeight = height.coerceAtLeast(1)
                if (rendererReady && width > 0 && height > 0) glSurface.requestRender()
            }

            override fun onDrawFrame(gl: GL10?) {
                if (disposed.get() || !rendererReady) return
                try {
                    NativeBridge.nativeDrawRr3ViewerGl(handle, surfaceWidth, surfaceHeight, yaw, pitch, zoom)
                } catch (error: Exception) {
                    rendererReady = false
                    post {
                        if (!disposed.get()) onFrameStatus(error.message ?: "The GPU viewer failed while drawing.")
                    }
                }
            }
        })
        glSurface.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        addView(glSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    pinchDistance = 0f
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinchDistance = pointerDistance(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (pinchDistance > 0f && distance > 0f) {
                            zoom = (zoom * distance / pinchDistance).coerceIn(0.55f, 3.0f)
                        }
                        pinchDistance = distance
                    } else {
                        yaw -= (event.x - lastX) * 0.008f
                        pitch = (pitch + (event.y - lastY) * 0.008f).coerceIn(-1.1f, 1.1f)
                    }
                    lastX = event.x
                    lastY = event.y
                    requestGpuFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    pinchDistance = 0f
                    requestGpuFrame()
                    true
                }
                else -> true
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestGpuFrame()
    }

    fun resetCamera() {
        yaw = 0.6f
        pitch = 0.25f
        zoom = 1f
        cameraPoseEnabled = false
        if (rendererReady) {
            glSurface.queueEvent {
                if (!disposed.get()) {
                    NativeBridge.nativeSetRr3ViewerCameraPose(handle, 0f, 0f, 0f, 0f, 0f, 0f, false)
                }
            }
        }
        requestGpuFrame()
    }

    fun setCameraPose(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        targetX: Float, targetY: Float, targetZ: Float
    ) {
        cameraEye = floatArrayOf(eyeX, eyeY, eyeZ)
        cameraTarget = floatArrayOf(targetX, targetY, targetZ)
        cameraPoseEnabled = true
        if (rendererReady) {
            glSurface.queueEvent {
                if (!disposed.get()) {
                    NativeBridge.nativeSetRr3ViewerCameraPose(
                        handle, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, true
                    )
                }
            }
        }
        requestGpuFrame()
    }

    fun setCameraTuning(panX: Float, panY: Float, fovY: Float) {
        cameraPanX = panX.coerceIn(-5f, 5f)
        cameraPanY = panY.coerceIn(-5f, 5f)
        cameraFovY = fovY.coerceIn(0.15f, 2.6f)
        if (rendererReady) {
            glSurface.queueEvent {
                if (!disposed.get()) {
                    NativeBridge.nativeUpdateRr3ViewerCamera(handle, cameraPanX, cameraPanY, cameraFovY)
                }
            }
        }
        requestGpuFrame()
    }

    fun setCameraAngles(nextYaw: Float, nextPitch: Float) {
        yaw = nextYaw
        pitch = nextPitch.coerceIn(-1.1f, 1.1f)
        requestGpuFrame()
    }

    fun setCameraZoom(nextZoom: Float) {
        zoom = nextZoom.coerceIn(0.55f, 3f)
        requestGpuFrame()
    }

    fun setEnvironmentEnabled(enabled: Boolean) {
        environmentEnabled = enabled
        if (rendererReady) {
            glSurface.queueEvent {
                if (!disposed.get()) NativeBridge.nativeUpdateRr3ViewerEnvironment(handle, enabled)
            }
        }
        requestGpuFrame()
    }

    fun refresh() {
        requestGpuFrame()
    }

    fun pauseRendering() {
        if (hostPaused || disposed.get()) return
        hostPaused = true
        glSurface.onPause()
    }

    fun resumeRendering() {
        if (!hostPaused || disposed.get()) {
            requestGpuFrame()
            return
        }
        hostPaused = false
        glSurface.onResume()
        requestGpuFrame()
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        rendererReady = false
        if (glSurface.isAttachedToWindow && !hostPaused) {
            glSurface.queueEvent {
                try {
                    NativeBridge.nativeReleaseRr3ViewerGl(handle)
                } finally {
                    NativeBridge.nativeFreeRr3Viewer(handle)
                }
            }
        } else {
            NativeBridge.nativeFreeRr3Viewer(handle)
        }
    }

    private fun requestGpuFrame() {
        if (!disposed.get() && rendererReady && !hostPaused) glSurface.requestRender()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}
