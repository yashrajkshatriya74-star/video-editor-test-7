package com.example.videoeditor.chroma

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real-time chroma key (green/blue screen) renderer.
 *
 * Renders camera/video frames (via SurfaceTexture, OES external texture) through a
 * fragment shader that keys out pixels close to [keyColor] within [threshold],
 * with [smoothing] to soften the matte edge instead of a hard cutout.
 *
 * Usage: feed this a SurfaceTexture from your video decoder or CameraX, then
 * this GLSurfaceView.Renderer draws the composited (background + keyed foreground) result.
 */
class ChromaKeyRenderer(
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    // Tunable at runtime (e.g. from UI sliders)
    var keyColor = floatArrayOf(0f, 1f, 0f) // default: green screen
    var threshold = 0.4f
    var smoothing = 0.1f

    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private var program = 0

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    // Chroma key math: convert to YCbCr-ish distance from key color, then
    // smoothstep the alpha so edges aren't jagged.
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform vec3 uKeyColor;
        uniform float uThreshold;
        uniform float uSmoothing;

        void main() {
            vec4 color = texture2D(sTexture, vTexCoord);
            float dist = distance(color.rgb, uKeyColor);
            float alpha = smoothstep(uThreshold, uThreshold + uSmoothing, dist);
            gl_FragColor = vec4(color.rgb, color.a * alpha);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        surfaceTexture = SurfaceTexture(textureId)
        onSurfaceReady(surfaceTexture)

        program = buildProgram(vertexShaderCode, fragmentShaderCode)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture.updateTexImage()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val texMatrix = FloatArray(16)
        surfaceTexture.getTransformMatrix(texMatrix)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uKeyColor"), 1, keyColor, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uThreshold"), threshold)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSmoothing"), smoothing)

        drawFullScreenQuad()
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    private fun drawFullScreenQuad() {
        val buffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadVertices); position(0)
            }

        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)

        buffer.position(2)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
