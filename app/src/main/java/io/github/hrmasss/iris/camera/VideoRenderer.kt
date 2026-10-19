package io.github.hrmasss.iris.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Copies camera frames to the video encoder through OpenGL, so a recording looks like the
 * preview: upright for a portrait phone and, for front cameras, mirrored unless [unmirror] is set.
 *
 * The camera applies the same rotate-and-mirror transform it applies to the preview to any
 * texture consumer, so drawing with that transform reproduces the preview. All calls must run
 * on [handler]'s thread.
 */
class VideoRenderer(
    output: Surface,
    cameraSize: Size,
    private val unmirror: Boolean,
    handler: Handler,
) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val textureId: Int
    private val texture: SurfaceTexture
    private val transform = FloatArray(16)

    /** Frames are consumed but not encoded until the recorder has started. */
    @Volatile
    var encoding = false

    /** Surface the camera writes into. */
    val inputSurface: Surface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        )
        val config = checkNotNull(configs[0].takeIf { count[0] > 0 }) { "No recordable EGL config" }
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Some GL drivers wrap by default; a cropped camera buffer then shows its far edge as a band.
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(cameraSize.width, cameraSize.height)
            setOnFrameAvailableListener({ draw() }, handler)
        }
        inputSurface = Surface(texture)
    }

    private fun draw() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return
        texture.updateTexImage()
        if (!encoding) return
        texture.getTransformMatrix(transform)

        val size = IntArray(2)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, size, 1)
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, if (unmirror) flippedQuad else quad)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, texture.timestamp)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        encoding = false
        texture.setOnFrameAvailableListener(null)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        inputSurface.release()
        texture.release()
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX = """
            uniform mat4 uTransform;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTransform * aTexCoord).xy;
            }
        """

        const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val flippedQuad = floats(1f, -1f, -1f, -1f, 1f, 1f, -1f, 1f)
        val texCoords = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
