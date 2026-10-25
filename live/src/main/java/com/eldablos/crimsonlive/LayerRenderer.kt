package com.eldablos.crimsonlive

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the wallpaper with OpenGL. The video holds three layers side by side (background, foreground,
 * foreground mask); this splits them apart and stacks them, shifting the background and foreground by
 * different amounts to create depth. Must be created and used on one thread.
 */
class LayerRenderer(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }

    /** The texture the video decoder draws into, for a SurfaceTexture. */
    val textureId: Int

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        )
        val config = checkNotNull(configs[0]) { "No EGL config" }
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = link(VERTEX, FRAGMENT)
    }

    /**
     * Draws one frame. [bg] and [fg] shift each layer, in fractions of the picture; [width] and [height]
     * are the screen size, used to crop the video to the screen's shape.
     */
    fun draw(texMatrix: FloatArray, bgX: Float, bgY: Float, fgX: Float, fgY: Float, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        // Show the middle 1/OVERSCAN of the picture, cropped to the screen's shape.
        var sx = 1f / OVERSCAN
        var sy = 1f / OVERSCAN
        val screenAspect = width.toFloat() / height
        if (screenAspect > PANEL_ASPECT) sy *= PANEL_ASPECT / screenAspect else sx *= screenAspect / PANEL_ASPECT

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), sx, sy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uBg"), bgX, bgY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFg"), fgX, fgY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)

        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Link: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        /** How much bigger than the screen the video was rendered (render-live.js / live.html OVERSCAN). */
        const val OVERSCAN = 1.12f
        private const val PANEL_ASPECT = 1080f / 2340f

        private const val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTex;
            uniform mat4 uTexMatrix;
            uniform vec2 uScale;
            uniform vec2 uBg;
            uniform vec2 uFg;
            varying vec2 vUv;

            // Samples one of the three side-by-side layers: 0 background, 1 foreground, 2 mask.
            vec4 layer(float panel, vec2 uv) {
                uv = clamp(uv, 0.002, 0.998);
                vec2 p = vec2((panel + uv.x) / 3.0, uv.y);
                return texture2D(uTex, (uTexMatrix * vec4(p, 0.0, 1.0)).xy);
            }

            void main() {
                vec2 c = (vUv - 0.5) * uScale + 0.5;
                vec3 bg = layer(0.0, c + uBg).rgb;
                vec3 fg = layer(1.0, c + uFg).rgb;
                float a = layer(2.0, c + uFg).r;
                // The foreground was rendered over black, so its glow adds and its bands cover.
                gl_FragColor = vec4(bg * (1.0 - a) + fg, 1.0);
            }
        """
    }
}
