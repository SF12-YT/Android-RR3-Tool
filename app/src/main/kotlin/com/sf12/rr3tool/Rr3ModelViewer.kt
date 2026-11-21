package com.sf12.rr3tool

import android.content.Context
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Android front end for Monkey Tool's native RR3 software renderer.
 * Model loading, camera framing, texture sampling and rasterisation all stay native.
 */
internal class Rr3ModelViewer(
    context: Context,
    private val handle: Long,
    private val onFrameStatus: (String) -> Unit
) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val rendering = AtomicBoolean(false)
    @Volatile private var renderRequested = false
    @Volatile private var previewRequested = false
    @Volatile private var lastFrameStartNanos = 0L
    @Volatile private var disposed = false
    @Volatile private var yaw = 0.6f
    @Volatile private var pitch = 0.25f
    @Volatile private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f

    init {
        setBackgroundColor(0xFF263442.toInt())
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
                    requestFrame(interactive = true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    pinchDistance = 0f
                    requestFrame(interactive = false)
                    true
                }
                else -> true
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) requestFrame()
    }

    fun resetCamera() {
        yaw = 0.6f
        pitch = 0.25f
        zoom = 1f
        requestFrame()
    }

    /** Redraw after the activity returns to the foreground. */
    fun refresh() {
        requestFrame(interactive = false)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        executor.execute { NativeBridge.nativeFreeRr3Viewer(handle) }
        executor.shutdown()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun requestFrame(interactive: Boolean = false) {
        if (disposed || width <= 0 || height <= 0) return
        previewRequested = interactive
        renderRequested = true
        if (!rendering.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (renderRequested && !disposed) {
                    renderRequested = false
                    var usePreview = previewRequested
                    // Keep drag rendering to at most about 20 fps. Any newer touch
                    // input replaces the pending camera state instead of queuing frames.
                    if (usePreview && lastFrameStartNanos != 0L) {
                        val remaining = 50_000_000L - (System.nanoTime() - lastFrameStartNanos)
                        if (remaining > 0L) {
                            val waitMs = ((remaining + 999_999L) / 1_000_000L).coerceAtMost(50L)
                            Thread.sleep(waitMs)
                        }
                        usePreview = previewRequested
                    }
                    lastFrameStartNanos = System.nanoTime()
                    val sourceWidth = width.coerceAtLeast(1)
                    val sourceHeight = height.coerceAtLeast(1)
                    val maxWidth = if (usePreview) 480 else 720
                    val maxHeight = if (usePreview) 360 else 540
                    val scale = minOf(
                        1f,
                        maxWidth.toFloat() / sourceWidth,
                        maxHeight.toFloat() / sourceHeight
                    )
                    val frameWidth = (sourceWidth * scale).toInt().coerceIn(160, maxWidth)
                    val frameHeight = (sourceHeight * scale).toInt().coerceIn(160, maxHeight)
                    val png = NativeBridge.nativeRenderRr3ViewerFrame(
                        handle, frameWidth, frameHeight, yaw, pitch, zoom, usePreview
                    ) ?: throw IllegalStateException(
                        NativeBridge.nativeGetRr3ViewerError().ifBlank {
                            "The RR3 model renderer could not produce an image."
                        }
                    )
                    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                        ?: throw IllegalStateException("The rendered RR3 preview image could not be decoded.")
                    post {
                        if (!disposed) {
                            image.setImageBitmap(bitmap)
                            onFrameStatus(
                                (if (usePreview) "Fast preview " else "Rendered ") +
                                    bitmap.width + " × " + bitmap.height
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                post { if (!disposed) onFrameStatus(error.message ?: "The 3D frame could not be rendered.") }
            } finally {
                rendering.set(false)
                if (renderRequested && !disposed) requestFrame(previewRequested)
            }
        }
    }
}