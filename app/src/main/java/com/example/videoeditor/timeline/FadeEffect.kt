package com.example.videoeditor.timeline

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect

/**
 * Multiplies every pixel's RGB by an alpha ramp computed from presentation time, producing
 * a fade-to-black at the tail of a clip and/or a fade-from-black at its head.
 *
 * NOTE on Media3 API surface: the exact base-class method signatures for custom
 * GlShaderPrograms have shifted across Media3 releases (this targets the 1.4.x
 * `BaseGlShaderProgram` shape). If the pinned Media3 version in build.gradle.kts
 * doesn't match, expect to adjust `drawFrame`'s signature — the GL/shader logic
 * itself won't need to change.
 *
 * @param fadeInDurationUs   fade-from-black length at the start of this clip (0 = no fade-in)
 * @param fadeOutStartUs     when, in this clip's own timeline, the fade-out begins
 * @param fadeOutDurationUs  fade-to-black length (0 = no fade-out)
 */
@UnstableApi
class FadeEffect(
    private val fadeInDurationUs: Long,
    private val fadeOutStartUs: Long,
    private val fadeOutDurationUs: Long
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        return FadeShaderProgram(useHdr, fadeInDurationUs, fadeOutStartUs, fadeOutDurationUs)
    }

    private class FadeShaderProgram(
        useHdr: Boolean,
        private val fadeInDurationUs: Long,
        private val fadeOutStartUs: Long,
        private val fadeOutDurationUs: Long
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private var glProgram: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (glProgram == null) {
                glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            }
            val alpha = computeAlpha(presentationTimeUs)

            val program = glProgram!!
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatUniform("uAlpha", alpha)
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

        /** 0 = black, 1 = full brightness. Ramps in at the head, ramps out at the tail. */
        private fun computeAlpha(presentationTimeUs: Long): Float {
            var alpha = 1f
            if (fadeInDurationUs > 0 && presentationTimeUs < fadeInDurationUs) {
                alpha = (presentationTimeUs.toFloat() / fadeInDurationUs).coerceIn(0f, 1f)
            }
            if (fadeOutDurationUs > 0 && presentationTimeUs > fadeOutStartUs) {
                val intoFade = presentationTimeUs - fadeOutStartUs
                val fadeOutAlpha = 1f - (intoFade.toFloat() / fadeOutDurationUs).coerceIn(0f, 1f)
                alpha = minOf(alpha, fadeOutAlpha)
            }
            return alpha
        }

        override fun release() {
            super.release()
            glProgram?.delete()
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

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexSampler;
                uniform float uAlpha;
                varying vec2 vTexCoords;
                void main() {
                    vec4 color = texture2D(uTexSampler, vTexCoords);
                    gl_FragColor = vec4(color.rgb * uAlpha, color.a);
                }
            """
        }
    }
}
