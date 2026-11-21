package com.sf12.rr3tool

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Full-screen RR3 model surface. Meshes and textures are uploaded once by the
 * native renderer; camera changes redraw the existing GPU buffers directly.
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
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f

    init {
        setBackgroundColor(0xFF263442.toInt())
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
                rendererReady = status.startsWith("OpenGL ES 3")
                post {
                    if (!disposed.get()) {
                        onFrameStatus(
                            if (rendererReady) status
                            else "GPU viewer unavailable: $status"
                        )
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
                    NativeBridge.nativeDrawRr3ViewerGl(
                        handle, surfaceWidth, surfaceHeight, yaw, pitch, zoom
                    )
                } catch (error: Exception) {
                    rendererReady = false
                    post {
                        if (!disposed.get()) {
                            onFrameStatus(error.message ?: "The GPU viewer failed while drawing.")
                        }
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
            // A detached/paused GLSurfaceView is about to lose its EGL context;
            // freeing the native session is safe and the driver releases its GL objects.
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
