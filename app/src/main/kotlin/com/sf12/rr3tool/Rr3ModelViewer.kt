package com.sf12.rr3tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Shows the supplied 2:1 equirectangular environment as a reference panorama.
 * This is a visual backdrop; a JPEG does not provide HDR lighting or reflections.
 */
private class PanoramaStripView(context: Context) : View(context) {
    private val image: Bitmap? = try {
        context.assets.open("rr3_environment_360.jpg").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    @Volatile var yaw: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    @Volatile var environmentEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(UiPalette.background)
        val panorama = image ?: return
        if (!environmentEnabled || width <= 0 || height <= 0) return

        val visibleSourceWidth = (panorama.height.toFloat() * width.toFloat() / height.toFloat())
            .toInt().coerceIn(1, panorama.width)
        val centre = (((yaw / (2f * Math.PI.toFloat())) * panorama.width).toInt() % panorama.width +
            panorama.width) % panorama.width
        val start = (centre - visibleSourceWidth / 2 + panorama.width) % panorama.width
        val firstWidth = minOf(visibleSourceWidth, panorama.width - start)
        val firstDestinationWidth = width.toFloat() * firstWidth / visibleSourceWidth.toFloat()

        canvas.drawBitmap(
            panorama,
            Rect(start, 0, start + firstWidth, panorama.height),
            RectF(0f, 0f, firstDestinationWidth, height.toFloat()),
            paint
        )
        val remainingWidth = visibleSourceWidth - firstWidth
        if (remainingWidth > 0) {
            canvas.drawBitmap(
                panorama,
                Rect(0, 0, remainingWidth, panorama.height),
                RectF(firstDestinationWidth, 0f, width.toFloat(), height.toFloat()),
                paint
            )
        }
    }
}

internal class Rr3ModelViewer(
    context: Context,
    private val handle: Long,
    private val onFrameStatus: (String) -> Unit
) : FrameLayout(context) {
    private val panoramaView = PanoramaStripView(context)
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
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f

    init {
        setBackgroundColor(UiPalette.background)
        addView(panoramaView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glSurface.setEGLContextClientVersion(3)
        glSurface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurface.holder.setFormat(PixelFormat.TRANSLUCENT)
        // Media overlay keeps the panorama below GL but leaves normal Android chrome above it.
        glSurface.setZOrderMediaOverlay(true)
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
                if (rendererReady) {
                    NativeBridge.nativeUpdateRr3ViewerCamera(handle, cameraPanX, cameraPanY, cameraFovY)
                }
                post {
                    if (!disposed.get()) {
                        onFrameStatus(if (rendererReady) status else "GPU viewer unavailable: $status")
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
                    panoramaView.yaw = yaw
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
        panoramaView.yaw = yaw
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
        panoramaView.yaw = yaw
        requestGpuFrame()
    }

    fun setCameraZoom(nextZoom: Float) {
        zoom = nextZoom.coerceIn(0.55f, 3f)
        requestGpuFrame()
    }

    fun setEnvironmentEnabled(enabled: Boolean) {
        panoramaView.environmentEnabled = enabled
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
