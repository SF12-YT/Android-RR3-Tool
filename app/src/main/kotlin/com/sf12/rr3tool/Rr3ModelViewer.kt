package com.sf12.rr3tool

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3 viewer for the geometry packet produced by Monkey Tool.
 * It renders actual RR3 mesh and texture data; no placeholder geometry is generated.
 */
internal class Rr3ModelViewer(
    context: Context,
    packet: ByteArray
) : GLSurfaceView(context) {

    data class Stats(val meshes: Int, val triangles: Int, val textures: Int)

    private val modelRenderer = ModelRenderer(packet)
    val stats: Stats = modelRenderer.stats

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(modelRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setBackgroundColor(0xFF101B29.toInt())

        setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    modelRenderer.beginTouch(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    modelRenderer.beginPinch(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    modelRenderer.moveTouch(event)
                    requestRender()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    fun resetCamera() {
        modelRenderer.resetCamera()
        requestRender()
    }

    private class ModelRenderer(packet: ByteArray) : Renderer {
        private data class TextureData(val width: Int, val height: Int, val pixels: ByteArray)

        private data class MeshData(
            val name: String,
            val material: String,
            val vertices: FloatArray,
            val indices: IntArray,
            val textureIndex: Int,
            val color: FloatArray,
            var vertexBuffer: Int = 0,
            var indexBuffer: Int = 0
        )

        private val textures = ArrayList<TextureData>()
        private val meshes = ArrayList<MeshData>()
        val stats: Stats

        private var program = 0
        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val viewProjection = FloatArray(16)
        private val mvp = FloatArray(16)
        private var textureHandles = IntArray(0)
        private var width = 1
        private var height = 1
        @Volatile private var yaw = 28f
        @Volatile private var pitch = -8f
        @Volatile private var zoom = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var pinchDistance = 0f

        init {
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            require(packet.size >= 16) { "The native 3D viewer returned an incomplete model." }
            val magic = ByteArray(4)
            buffer.get(magic)
            require(String(magic, Charsets.US_ASCII) == "R3V1") {
                "The native 3D viewer returned an unknown model format."
            }
            val meshCount = buffer.int
            val textureCount = buffer.int
            val triangleCount = buffer.int
            require(meshCount in 1..2048 && textureCount in 0..1024) {
                "The RR3 model contains an unsupported number of meshes or textures."
            }

            repeat(textureCount) {
                val textureName = readString(buffer)
                val textureWidth = buffer.int
                val textureHeight = buffer.int
                val byteCount = buffer.int
                require(textureWidth in 1..4096 && textureHeight in 1..4096 &&
                    byteCount == textureWidth * textureHeight * 4 &&
                    byteCount <= buffer.remaining()
                ) { "Texture '$textureName' is incomplete or too large." }
                val pixels = ByteArray(byteCount)
                buffer.get(pixels)
                textures.add(TextureData(textureWidth, textureHeight, pixels))
            }

            repeat(meshCount) {
                val name = readString(buffer)
                val material = readString(buffer)
                val vertexCount = buffer.int
                val indexCount = buffer.int
                val textureIndex = buffer.int
                buffer.int // render flags reserved for later blend-state handling
                require(vertexCount in 1..2_000_000 && indexCount in 3..12_000_000 &&
                    indexCount % 3 == 0 &&
                    (textureIndex == -1 || textureIndex in textures.indices) &&
                    buffer.remaining() >= vertexCount.toLong() * 48L + indexCount.toLong() * 4L
                ) { "Mesh '$name' has invalid geometry data." }

                val color = FloatArray(4) { buffer.float }
                val vertices = FloatArray(vertexCount * 12) { buffer.float }
                val indices = IntArray(indexCount) { buffer.int }
                meshes.add(MeshData(name, material, vertices, indices, textureIndex, color))
            }
            require(buffer.position() == packet.size) {
                "The native model packet contains unexpected trailing data."
            }
            stats = Stats(meshes.size, triangleCount, textures.size)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(0.055f, 0.085f, 0.125f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            for (mesh in meshes) {
                val buffers = IntArray(2)
                GLES30.glGenBuffers(2, buffers, 0)
                mesh.vertexBuffer = buffers[0]
                mesh.indexBuffer = buffers[1]

                val vertexBuffer = ByteBuffer.allocateDirect(mesh.vertices.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                vertexBuffer.put(mesh.vertices).position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vertexBuffer)
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    mesh.vertices.size * 4,
                    vertexBuffer,
                    GLES30.GL_STATIC_DRAW
                )

                val indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 4)
                    .order(ByteOrder.nativeOrder()).asIntBuffer()
                indexBuffer.put(mesh.indices).position(0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indexBuffer)
                GLES30.glBufferData(
                    GLES30.GL_ELEMENT_ARRAY_BUFFER,
                    mesh.indices.size * 4,
                    indexBuffer,
                    GLES30.GL_STATIC_DRAW
                )
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

            textureHandles = IntArray(textures.size)
            if (textureHandles.isNotEmpty()) {
                GLES30.glGenTextures(textureHandles.size, textureHandles, 0)
                textures.forEachIndexed { index, texture ->
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandles[index])
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                    val pixels = ByteBuffer.allocateDirect(texture.pixels.size)
                    pixels.put(texture.pixels).position(0)
                    GLES30.glTexImage2D(
                        GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                        texture.width, texture.height, 0, GLES30.GL_RGBA,
                        GLES30.GL_UNSIGNED_BYTE, pixels
                    )
                    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = max(1, width)
            this.height = max(1, height)
            GLES30.glViewport(0, 0, this.width, this.height)
            Matrix.perspectiveM(projection, 0, 42f, this.width.toFloat() / this.height, 0.1f, 40f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (program == 0 || meshes.isEmpty()) return

            Matrix.setLookAtM(view, 0, 0f, 0f, 3.35f / zoom, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)

            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uLightDirection"), -0.35f, 0.8f, 0.55f)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

            val position = GLES30.glGetAttribLocation(program, "aPosition")
            val normal = GLES30.glGetAttribLocation(program, "aNormal")
            val uv = GLES30.glGetAttribLocation(program, "aUv")
            val color = GLES30.glGetAttribLocation(program, "aColor")
            val useTexture = GLES30.glGetUniformLocation(program, "uUseTexture")
            val materialColor = GLES30.glGetUniformLocation(program, "uMaterialColor")
            val stride = 12 * 4

            for (mesh in meshes) {
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vertexBuffer)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indexBuffer)

                GLES30.glEnableVertexAttribArray(position)
                GLES30.glVertexAttribPointer(position, 3, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glEnableVertexAttribArray(normal)
                GLES30.glVertexAttribPointer(normal, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
                GLES30.glEnableVertexAttribArray(uv)
                GLES30.glVertexAttribPointer(uv, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
                GLES30.glEnableVertexAttribArray(color)
                GLES30.glVertexAttribPointer(color, 4, GLES30.GL_FLOAT, false, stride, 8 * 4)

                GLES30.glUniform4fv(materialColor, 1, mesh.color, 0)
                val hasTexture = mesh.textureIndex >= 0 && mesh.textureIndex < textureHandles.size
                GLES30.glUniform1i(useTexture, if (hasTexture) 1 else 0)
                if (hasTexture) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandles[mesh.textureIndex])
                }
                GLES30.glDrawElements(
                    GLES30.GL_TRIANGLES, mesh.indices.size,
                    GLES30.GL_UNSIGNED_INT, 0
                )
            }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES30.glUseProgram(0)
        }

        fun beginTouch(x: Float, y: Float) {
            lastX = x
            lastY = y
            pinchDistance = 0f
        }

        fun beginPinch(event: MotionEvent) {
            pinchDistance = pointerDistance(event)
        }

        fun moveTouch(event: MotionEvent) {
            if (event.pointerCount >= 2) {
                val distance = pointerDistance(event)
                if (pinchDistance > 0f && distance > 0f) {
                    zoom = (zoom * (distance / pinchDistance)).coerceIn(0.55f, 2.8f)
                }
                pinchDistance = distance
            } else {
                val dx = event.x - lastX
                val dy = event.y - lastY
                yaw = (yaw + dx * 0.55f) % 360f
                pitch = (pitch + dy * 0.45f).coerceIn(-82f, 82f)
            }
            lastX = event.x
            lastY = event.y
        }

        fun resetCamera() {
            yaw = 28f
            pitch = -8f
            zoom = 1f
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return sqrt(dx * dx + dy * dy)
        }

        private fun readString(buffer: ByteBuffer): String {
            val length = buffer.int
            require(length in 0..4096 && length <= buffer.remaining()) {
                "The native 3D viewer returned an invalid mesh name."
            }
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val result = GLES30.glCreateProgram()
            GLES30.glAttachShader(result, vertex)
            GLES30.glAttachShader(result, fragment)
            GLES30.glLinkProgram(result)
            val status = IntArray(1)
            GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            val log = GLES30.glGetProgramInfoLog(result)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            check(status[0] != 0) { "Could not link the 3D viewer shader: $log" }
            return result
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            val log = GLES30.glGetShaderInfoLog(shader)
            if (status[0] == 0) {
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Could not compile a 3D viewer shader: $log")
            }
            return shader
        }

        companion object {
            private const val VERTEX_SHADER = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
    vColor = aColor;
}
"""
            private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;
uniform sampler2D uTexture;
uniform bool uUseTexture;
uniform vec4 uMaterialColor;
uniform vec3 uLightDirection;
out vec4 outColor;
void main() {
    vec4 texel = uUseTexture ? texture(uTexture, vUv) : vec4(1.0);
    vec3 n = normalize(vNormal);
    float diffuse = max(dot(n, normalize(uLightDirection)), 0.0);
    float lighting = 0.30 + diffuse * 0.70;
    vec3 rgb = texel.rgb * vColor.rgb * uMaterialColor.rgb * lighting;
    float alpha = texel.a * vColor.a * uMaterialColor.a;
    outColor = vec4(rgb, alpha);
}
"""
        }
    }
}
