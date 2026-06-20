package robotic.slam

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class SlamGLRenderer : GLSurfaceView.Renderer {

    companion object {
        // OpenCV camera coordinates use +Z as forward, while the OpenGL view reads
        // forward more naturally along -Z. Mirror the display Z axis so a physical
        // forward move no longer appears as backward movement in the map.
        private const val DEFAULT_MAP_DISPLAY_SCALE = 2.0f
        private const val DEFAULT_MIRROR_FORWARD_AXIS = true
    }

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var pointBuffer: FloatBuffer? = null
    private var pathVertexBuffer: FloatBuffer? = null

    private var pointCount = 0
    private var pathCount = 0

    private val allPoints = mutableListOf<Float>()
    private val allPath = mutableListOf<Float>() // x, y, z, rx, ry, rz

    private var program = 0
    private var frustumVerticesBuffer: FloatBuffer? = null
    private var frustumIndexBuffer: ShortBuffer? = null
    private var axesBuffer: FloatBuffer? = null

    var angleX: Float = 75f
    var angleY: Float = 0f
    var zoom: Float = 25f
    var cameraFrustumScale: Float = 0.18f
    var mapDisplayScale: Float = DEFAULT_MAP_DISPLAY_SCALE
    var mirrorForwardAxis: Boolean = DEFAULT_MIRROR_FORWARD_AXIS
    var cameraDrawStride: Int = 12
    var showBackground = false

    // 3D History layer toggles
    @Volatile var showFeatures: Boolean = true
    @Volatile var showPath: Boolean = true
    @Volatile var showCameras: Boolean = true
    @Volatile var showAxes: Boolean = true

    init {
        val frustum = floatArrayOf(
            0f, 0f, 0f,
            -0.5f, 0.35f, 1f,
             0.5f, 0.35f, 1f,
             0.5f,-0.35f, 1f,
            -0.5f,-0.35f, 1f
        )
        frustumVerticesBuffer = createFloatBuffer(frustum)

        val indices = shortArrayOf(
            0, 1, 0, 2, 0, 3, 0, 4,
            1, 2, 2, 3, 3, 4, 4, 1
        )
        frustumIndexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }

        val axes = floatArrayOf(
            // X-axis (Red)
            0f, 0f, 0f,  1.0f, 0f, 0f,
            1.0f, 0f, 0f,  0.85f, 0.08f, 0f,
            1.0f, 0f, 0f,  0.85f, -0.08f, 0f,
            // X Label
            1.2f, 0.1f, 0f, 1.4f, -0.1f, 0f,
            1.2f, -0.1f, 0f, 1.4f, 0.1f, 0f,

            // Y-axis (Green) - UP
            0f, 0f, 0f,  0f, 1.0f, 0f,
            0f, 1.0f, 0f,  0.08f, 0.85f, 0f,
            0f, 1.0f, 0f, -0.08f, 0.85f, 0f,
            // Y Label
            -0.1f, 1.4f, 0f, 0f, 1.3f, 0f,
             0.1f, 1.4f, 0f, 0f, 1.3f, 0f,
             0f, 1.3f, 0f, 0f, 1.15f, 0f,

            // Z-axis (Blue) - FORWARD
            0f, 0f, 0f,  0f, 0f, 1.0f,
            0f, 0f, 1.0f,  0.08f, 0f, 0.85f,
            0f, 0f, 1.0f, -0.08f, 0f, 0.85f,
            // Z Label
            -0.1f, 0.1f, 1.3f, 0.1f, 0.1f, 1.3f,
             0.1f, 0.1f, 1.3f, -0.1f, -0.1f, 1.3f,
            -0.1f, -0.1f, 1.3f, 0.1f, -0.1f, 1.3f
        )
        axesBuffer = createFloatBuffer(axes)
    }

    fun clearData() {
        synchronized(this) {
            allPoints.clear()
            allPath.clear()
            pointCount = 0
            pathCount = 0
            pointBuffer = null
            pathVertexBuffer = null
        }
    }

    fun updateData(newPoints: FloatArray, newPath: FloatArray) {
        synchronized(this) {
            if (newPoints.isNotEmpty()) {
                allPoints.addAll(newPoints.toList())
                pointCount = allPoints.size / 6
                pointBuffer = createFloatBuffer(allPoints.toFloatArray())
            }

            if (newPath.isNotEmpty()) {
                allPath.addAll(newPath.toList())
                pathCount = allPath.size / 6
                rebuildPathBuffer()
            }
        }
    }

    private fun rebuildPathBuffer() {
        if (allPath.isEmpty()) {
            pathVertexBuffer = null
            return
        }
        val verts = FloatArray(pathCount * 3)
        for (i in 0 until pathCount) {
            val src = i * 6
            val dst = i * 3
            verts[dst] = allPath[src]
            verts[dst + 1] = allPath[src + 1]
            verts[dst + 2] = allPath[src + 2]
        }
        pathVertexBuffer = createFloatBuffer(verts)
    }

    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(array); position(0) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vertexShaderCode = """
            uniform mat4 uVPMatrix;
            attribute vec4 vPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uVPMatrix * vPosition;
                gl_PointSize = 3.0;
                vColor = aColor;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """.trimIndent()

        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode))
            GLES20.glAttachShader(this, loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode))
            GLES20.glLinkProgram(this)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / max(1, height).toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 1000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, zoom, 0f, 0f, 0f, 0f, 1f, 0f)
        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, angleY, 0f, 1f, 0f)

        val viewRotationMatrix = FloatArray(16)
        Matrix.multiplyMM(viewRotationMatrix, 0, viewMatrix, 0, rotation, 0)
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewRotationMatrix, 0)

        val mapScaleMatrix = FloatArray(16)
        Matrix.setIdentityM(mapScaleMatrix, 0)
        val zScale = if (mirrorForwardAxis) -mapDisplayScale else mapDisplayScale
        Matrix.scaleM(mapScaleMatrix, 0, mapDisplayScale, mapDisplayScale, zScale)

        val mapVPMatrix = FloatArray(16)
        Matrix.multiplyMM(mapVPMatrix, 0, vPMatrix, 0, mapScaleMatrix, 0)

        GLES20.glUseProgram(program)
        val matrixHandle = GLES20.glGetUniformLocation(program, "uVPMatrix")
        val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(program, "aColor")

        synchronized(this) {
            if (showFeatures) {
                pointBuffer?.let {
                    GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mapVPMatrix, 0)
                    GLES20.glEnableVertexAttribArray(posHandle)
                    it.position(0)
                    GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 24, it)
                    GLES20.glEnableVertexAttribArray(colorHandle)
                    it.position(3)
                    GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 24, it)
                    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)
                }
            }

            if (showPath) {
                pathVertexBuffer?.let {
                    GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mapVPMatrix, 0)
                    GLES20.glEnableVertexAttribArray(posHandle)
                    it.position(0)
                    GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, it)
                    GLES20.glDisableVertexAttribArray(colorHandle)
                    GLES20.glVertexAttrib4f(colorHandle, 0.15f, 0.95f, 0.25f, 1.0f)
                    GLES20.glLineWidth(5.0f)
                    GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pathCount)
                }
            }

            if (showCameras && pathCount > 0 && frustumVerticesBuffer != null && frustumIndexBuffer != null) {
                GLES20.glDisableVertexAttribArray(colorHandle)
                GLES20.glVertexAttrib4f(colorHandle, 0.25f, 0.55f, 1.0f, 1.0f)
                GLES20.glLineWidth(2.0f)

                val stride = max(1, cameraDrawStride)
                for (i in 0 until pathCount step stride) {
                    val idx = i * 6
                    if (idx + 5 >= allPath.size) break

                    val modelMatrix = FloatArray(16)
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, allPath[idx], allPath[idx + 1], allPath[idx + 2])
                    Matrix.rotateM(modelMatrix, 0, allPath[idx + 3], 1f, 0f, 0f)
                    Matrix.rotateM(modelMatrix, 0, allPath[idx + 4], 0f, 1f, 0f)
                    Matrix.rotateM(modelMatrix, 0, allPath[idx + 5], 0f, 0f, 1f)
                    Matrix.scaleM(modelMatrix, 0, cameraFrustumScale, cameraFrustumScale, cameraFrustumScale)

                    val mvp = FloatArray(16)
                    Matrix.multiplyMM(mvp, 0, mapVPMatrix, 0, modelMatrix, 0)
                    GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mvp, 0)

                    frustumVerticesBuffer?.position(0)
                    GLES20.glEnableVertexAttribArray(posHandle)
                    GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, frustumVerticesBuffer)
                    frustumIndexBuffer?.position(0)
                    GLES20.glDrawElements(GLES20.GL_LINES, 16, GLES20.GL_UNSIGNED_SHORT, frustumIndexBuffer)
                }
            }

            // Draw orientation axes as a fixed overlay in the bottom right corner
            if (showAxes) {
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                
                // Get current viewport to restore later
                val currentViewport = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, currentViewport, 0)
                
                // Define a small square viewport in the top right
                val overlaySize = (currentViewport[2] * 0.25f).toInt().coerceIn(180, 450)
                val xOffset = currentViewport[2] - overlaySize - 20
                val yOffset = currentViewport[3] - overlaySize - 10
                GLES20.glViewport(xOffset, yOffset, overlaySize, overlaySize)
                
                val axesMVP = FloatArray(16)
                val axesProj = FloatArray(16)
                Matrix.perspectiveM(axesProj, 0, 45f, 1f, 0.1f, 20f)
                
                val axesView = FloatArray(16)
                Matrix.setLookAtM(axesView, 0, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 1f, 0f)
                
                val rot = FloatArray(16)
                Matrix.setIdentityM(rot, 0)
                Matrix.rotateM(rot, 0, angleX, 1f, 0f, 0f)
                Matrix.rotateM(rot, 0, angleY, 0f, 1f, 0f)
                
                val mv = FloatArray(16)
                Matrix.multiplyMM(mv, 0, axesView, 0, rot, 0)
                // Center slightly to account for labels
                Matrix.translateM(mv, 0, -0.2f, -0.2f, 0f)
                
                Matrix.multiplyMM(axesMVP, 0, axesProj, 0, mv, 0)
                GLES20.glUniformMatrix4fv(matrixHandle, 1, false, axesMVP, 0)
                GLES20.glEnableVertexAttribArray(posHandle)
                GLES20.glDisableVertexAttribArray(colorHandle)
                GLES20.glLineWidth(5.0f)
                
                // X - Red
                GLES20.glVertexAttrib4f(colorHandle, 1.0f, 0.2f, 0.2f, 1.0f)
                axesBuffer?.position(0)
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, axesBuffer)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 10) 
                
                // Y - Green (UP)
                GLES20.glVertexAttrib4f(colorHandle, 0.2f, 1.0f, 0.2f, 1.0f)
                axesBuffer?.position(30) 
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, axesBuffer)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 12) 
                
                // Z - Blue (FORWARD)
                GLES20.glVertexAttrib4f(colorHandle, 0.2f, 0.5f, 1.0f, 1.0f)
                axesBuffer?.position(66) 
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, axesBuffer)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 12) 

                // Restore original viewport
                GLES20.glViewport(currentViewport[0], currentViewport[1], currentViewport[2], currentViewport[3])
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            }
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
    }
}
