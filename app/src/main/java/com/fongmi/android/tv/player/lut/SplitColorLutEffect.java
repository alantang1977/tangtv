package com.fongmi.android.tv.player.lut;

import android.content.Context;
import android.opengl.GLES20;
import android.os.SystemClock;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.ColorLut;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

public class SplitColorLutEffect implements GlEffect {

    private static final int SLIDE_MS = 420;

    private final ColorLut colorLut;
    private final int holdMs;

    public SplitColorLutEffect(ColorLut colorLut, int holdSeconds) {
        this.colorLut = colorLut;
        this.holdMs = Math.max(1, holdSeconds) * 1000;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new SplitColorLutShaderProgram(context, colorLut, holdMs, useHdr);
    }

    private static class SplitColorLutShaderProgram extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
                "#version 100\n" +
                "attribute vec4 aFramePosition;\n" +
                "uniform mat4 uTransformationMatrix;\n" +
                "uniform mat4 uTexTransformationMatrix;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uTransformationMatrix * aFramePosition;\n" +
                "  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n" +
                "  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#version 100\n" +
                "precision highp float;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform sampler2D uColorLut;\n" +
                "uniform float uColorLutLength;\n" +
                "uniform float uSplitEdge;\n" +
                "uniform float uLineWidth;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "vec3 applyLookup(vec3 color) {\n" +
                "  float redCoord = color.r * (uColorLutLength - 1.0);\n" +
                "  float redCoordLow = clamp(floor(redCoord), 0.0, uColorLutLength - 2.0);\n" +
                "  float lowerY = (0.5 + redCoordLow * uColorLutLength + color.g * (uColorLutLength - 1.0)) / (uColorLutLength * uColorLutLength);\n" +
                "  float upperY = lowerY + 1.0 / uColorLutLength;\n" +
                "  float x = (0.5 + color.b * (uColorLutLength - 1.0)) / uColorLutLength;\n" +
                "  vec3 lowerRgb = texture2D(uColorLut, vec2(x, lowerY)).rgb;\n" +
                "  vec3 upperRgb = texture2D(uColorLut, vec2(x, upperY)).rgb;\n" +
                "  return mix(lowerRgb, upperRgb, redCoord - redCoordLow);\n" +
                "}\n" +
                "void main() {\n" +
                "  vec4 inputColor = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "  vec3 lutColor = applyLookup(inputColor.rgb);\n" +
                "  float mask = smoothstep(uSplitEdge - uLineWidth, uSplitEdge + uLineWidth, vTexSamplingCoord.x);\n" +
                "  vec3 color = mix(inputColor.rgb, lutColor, mask);\n" +
                "  float line = 1.0 - smoothstep(0.0, uLineWidth, abs(vTexSamplingCoord.x - uSplitEdge));\n" +
                "  line *= step(0.001, uSplitEdge) * step(uSplitEdge, 0.999);\n" +
                "  gl_FragColor.rgb = mix(color, vec3(1.0), line * 0.72);\n" +
                "  gl_FragColor.a = inputColor.a;\n" +
                "}\n";

        private final ColorLut colorLut;
        private final GlProgram glProgram;
        private final int holdMs;
        private GlShaderProgram lutShaderProgram;
        private int width;
        private long startMs;

        private SplitColorLutShaderProgram(Context context, ColorLut colorLut, int holdMs, boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 1);
            if (useHdr) throw new VideoFrameProcessingException("SplitColorLutEffect does not support HDR colors.");
            this.colorLut = colorLut;
            this.holdMs = holdMs;
            this.startMs = -1;
            try {
                this.lutShaderProgram = colorLut.toGlShaderProgram(context, false);
                this.glProgram = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
                float[] identity = GlUtil.create4x4IdentityMatrix();
                glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
                glProgram.setFloatsUniform("uTransformationMatrix", identity);
                glProgram.setFloatsUniform("uTexTransformationMatrix", identity);
            } catch (GlUtil.GlException e) {
                releaseLutShaderProgram();
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            this.width = inputWidth;
            return new Size(inputWidth, inputHeight);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                if (startMs < 0) startMs = SystemClock.elapsedRealtime();
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                glProgram.setSamplerTexIdUniform("uColorLut", colorLut.getLutTextureId(presentationTimeUs), 1);
                glProgram.setFloatUniform("uColorLutLength", colorLut.getLength(presentationTimeUs));
                glProgram.setFloatUniform("uSplitEdge", getSplitEdge());
                glProgram.setFloatUniform("uLineWidth", width > 0 ? Math.max(1.2f / width, 0.0015f) : 0.002f);
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } catch (RuntimeException | GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                super.release();
                releaseLutShaderProgram();
                glProgram.delete();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        private void releaseLutShaderProgram() throws VideoFrameProcessingException {
            if (lutShaderProgram != null) {
                lutShaderProgram.release();
                lutShaderProgram = null;
            } else {
                try {
                    colorLut.release();
                } catch (GlUtil.GlException e) {
                    throw new VideoFrameProcessingException(e);
                }
            }
        }

        private float getSplitEdge() {
            long elapsed = SystemClock.elapsedRealtime() - startMs;
            if (elapsed <= holdMs) return 0.5f;
            float progress = Math.min(1f, (elapsed - holdMs) / (float) SLIDE_MS);
            return 0.5f * (1f - progress);
        }
    }
}
