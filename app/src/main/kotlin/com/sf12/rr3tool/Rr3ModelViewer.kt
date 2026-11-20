package com.sf12.rr3tool

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU-backed RR3 model viewer.
 *
 * The native Monkey Tool parser still assembles the model and decodes its
 * textures. OpenGL ES uploads those assets once and redraws them directly
 * from GPU buffers as the camera moves.
 */
internal class Rr3ModelViewer(
    context: Context,
    private val handle: Long,
    private val onFrameStatus: (String) -> Unit
) : GLSurfaceView(context) {
    @Volatile private var disposed = false
    @Volatile private var glReady = false
    @Volatile private var yaw = 0.6f
    @Volatile private var pitch = 0.25f
    @Volatile private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f

    private val viewerRenderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            if (disposed) return
            glReady = false
            try {
                val status = NativeBridge.nativeInitRr3ViewerGl(handle)
                glReady = status.startsWith("OpenGL ES 3")
                this@Rr3ModelViewer.post {
                    if (!disposed) onFrameStatus(
                        if (glReady) status else status.ifBlank { "GPU renderer initialisation failed." }
                    )
                }
                if (glReady) this@Rr3ModelViewer.requestRender()
            } catch (error: Exception) {
                post {
                    if (!disposed) onFrameStatus(
                        error.message ?: "Could not initialise the OpenGL ES renderer."
                    )
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            if (width > 0 && height > 0) this@Rr3ModelViewer.requestRender()
        }

        override fun onDrawFrame(gl: GL10?) {
            if (disposed || !glReady) return
            try {
                NativeBridge.nativeDrawRr3ViewerGl(
                    handle,
                    this@Rr3ModelViewer.width.coerceAtLeast(1),
                    this@Rr3ModelViewer.height.coerceAtLeast(1),
                    yaw,
                    pitch,
                    zoom
                )
            } catch (error: Exception) {
                glReady = false
                post {
                    if (!disposed) onFrameStatus(
                        error.message ?: "The OpenGL ES renderer stopped unexpectedly."
                    )
                }
            }
        }
    }

    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        setRenderer(viewerRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setBackgroundColor(0xFF0E131A.toInt())
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
                        yaw += (event.x - lastX) * 0.008f
                        pitch = (pitch + (event.y - lastY) * 0.008f).coerceIn(-1.1f, 1.1f)
                    }
                    lastX = event.x
                    lastY = event.y
                    // GLSurfaceView coalesces dirty-frame requests on its GL
                    // thread; no CPU rasterisation or PNG/Bitmap conversion.
                    requestRender()
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    pinchDistance = 0f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    pinchDistance = 0f
                    requestRender()
                    true
                }
                else -> true
            }
        }
    }

    fun resetCamera() {
        yaw = 0.6f
        pitch = 0.25f
        zoom = 1f
        requestRender()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        glReady = false
        // GL object lifetime is bounded by this GLSurfaceView's EGL context.
        // The native session itself can be freed immediately; any in-flight
        // draw holds its own shared_ptr until the frame returns.
        NativeBridge.nativeFreeRr3Viewer(handle)
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}
