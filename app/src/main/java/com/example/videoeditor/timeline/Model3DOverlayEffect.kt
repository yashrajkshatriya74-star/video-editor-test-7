package com.example.videoeditor.timeline

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect

/**
 * Composites pre-rendered 3D model frames (from [Model3DFrameRenderer], rendered against a
 * solid green background) on top of the video frame, keying out the green the same way
 * [com.example.videoeditor.chroma.ChromaKeyRenderer] does.
 *
 * This deliberately avoids Media3's `OverlayEffect`/`BitmapOverlay`/`StaticOverlaySettings`
 * convenience classes — an earlier version used those and they didn't match this project's
 * pinned Media3 version (unresolved-reference build failures). This version instead builds
 * directly on [GlEffect]/[BaseGlShaderProgram], the same low-level base classes [FadeEffect]
 * already uses successfully in this project, plus plain `android.opengl.*` texture upload
 * calls — both are proven to compile here, so this rewrite has a real foundation instead of
 * guessing at another unfamiliar high-level API.
 */
@UnstableApi
class Model3DOverlayEffect(
    private val frames: List<Model3DFrameRenderer.RenderedFrame>
) : GlEffect {

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): BaseGlShaderProgram {
        return Model3DShaderProgram(useHdr, frames)
    }

    private class Model3DShaderProgram(
        useHdr: Boolean,
        private val frames: List<Model3DFrameRenderer.RenderedFrame>
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private var glProgram: GlProgram? = null
        private var modelTexId: Int = 0
        private var lastUploadedFrameIndex: Int = -1

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (frames.isEmpty()) {
                // Nothing to overlay — just pass the video frame through unchanged.
                passthroughDraw(inputTexId)
                return
            }

            if (glProgram == null) {
                glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                val textureIds = IntArray(1)
                GLES20.glGenTextures(1, textureIds, 0)
                modelTexId = textureIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, modelTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }

            val nearestIndex = nearestFrameIndex(presentationTimeUs)
            if (nearestIndex != lastUploadedFrameIndex) {
                uploadBitmap(frames[nearestIndex].bitmap)
                lastUploadedFrameIndex = nearestIndex
            }

            val program = glProgram!!
            program.use()
            program.setSamplerTexIdUniform("uVideoTex", inputTexId, /* texUnitIndex= */ 0)
            program.setSamplerTexIdUniform("uModelTex", modelTexId, /* texUnitIndex= */ 1)
            // Three separate float uniforms instead of a single vec3/array uniform —
            // setFloatUniform (single float) is already proven to work in FadeEffect.kt;
            // a float-array uniform setter is not something I've confirmed exists on
            // this GlProgram API, so this avoids introducing another unverified call.
            program.setFloatUniform("uKeyColorR", 0f)
            program.setFloatUniform("uKeyColorG", 1f)
            program.setFloatUniform("uKeyColorB", 0f)
            program.setFloatUniform("uThreshold", 0.4f)
            program.setFloatUniform("uSmoothing", 0.1f)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.setBufferAttribute(
                "aTexCoords",
                GlUtil.getTextureCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun passthroughDraw(inputTexId: Int) {
            if (glProgram == null) glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER_PASSTHROUGH)
            val program = glProgram!!
            program.use()
            program.setSamplerTexIdUniform("uVideoTex", inputTexId, 0)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.setBufferAttribute(
                "aTexCoords",
                GlUtil.getTextureCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun nearestFrameIndex(presentationTimeUs: Long): Int {
            var bestIndex = 0
            var bestDiff = Long.MAX_VALUE
            for (i in frames.indices) {
                val diff = kotlin.math.abs(frames[i].presentationTimeUs - presentationTimeUs)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIndex = i
                }
            }
            return bestIndex
        }

        private fun uploadBitmap(bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, modelTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        override fun release() {
            super.release()
            glProgram?.delete()
            if (modelTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(modelTexId), 0)
            }
        }

        companion object {
            private const val VERTEX_SHADER = """
                attribute vec4 aFramePosition;
                attribute vec4 aTexCoords;
                varying vec2 vTexCoords;
                void main() {
                    gl_Position = aFramePosition;
                    vTexCoords = aTexCoords.xy;
                }
            """

            // Keys out the model frame's solid green background, then blends the
            // remaining (non-green) model pixels on top of the video frame —
            // same distance+smoothstep technique as ChromaKeyRenderer's shader.
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uVideoTex;
                uniform sampler2D uModelTex;
                uniform float uKeyColorR;
                uniform float uKeyColorG;
                uniform float uKeyColorB;
                uniform float uThreshold;
                uniform float uSmoothing;
                varying vec2 vTexCoords;
                void main() {
                    vec4 videoColor = texture2D(uVideoTex, vTexCoords);
                    vec4 modelColor = texture2D(uModelTex, vTexCoords);
                    vec3 keyColor = vec3(uKeyColorR, uKeyColorG, uKeyColorB);
                    float dist = distance(modelColor.rgb, keyColor);
                    float alpha = smoothstep(uThreshold, uThreshold + uSmoothing, dist);
                    vec3 finalColor = mix(videoColor.rgb, modelColor.rgb, alpha);
                    gl_FragColor = vec4(finalColor, videoColor.a);
                }
            """

            private const val FRAGMENT_SHADER_PASSTHROUGH = """
                precision mediump float;
                uniform sampler2D uVideoTex;
                varying vec2 vTexCoords;
                void main() {
                    gl_FragColor = texture2D(uVideoTex, vTexCoords);
                }
            """
        }
    }
}
