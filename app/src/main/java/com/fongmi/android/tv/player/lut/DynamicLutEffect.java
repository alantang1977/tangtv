package com.fongmi.android.tv.player.lut;

import android.content.Context;
import android.opengl.GLES20;
import android.os.SystemClock;

import androidx.media3.common.Effect;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.ColorLut;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DynamicLutEffect implements GlEffect {

    private static final int SLIDE_MS = 420;

    private final List<Effect> effects = Collections.singletonList(this);
    private final Queue<ColorLut> retired = new ConcurrentLinkedQueue<>();
    private volatile State state = State.off(0);
    private int serial;

    public List<Effect> effects() {
        return effects;
    }

    public synchronized void set(ColorLut colorLut, boolean preview, int previewSeconds) {
        State previous = state;
        state = new State(colorLut, preview, Math.max(1, previewSeconds) * 1000, SystemClock.elapsedRealtime(), ++serial);
        retire(previous.colorLut);
    }

    public synchronized void clear() {
        State previous = state;
        state = State.off(++serial);
        retire(previous.colorLut);
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new DynamicLutShaderProgram(context, this, useHdr);
    }

    @Override
    public boolean isNoOp(int inputWidth, int inputHeight) {
        return false;
    }

    private State getState() {
        return state;
    }

    private List<ColorLut> drainRetired() {
        List<ColorLut> items = new ArrayList<>();
        ColorLut lut;
        while ((lut = retired.poll()) != null) items.add(lut);
        return items;
    }

    private void retire(ColorLut colorLut) {
        if (colorLut != null) retired.add(colorLut);
    }

    private static class State {
        private final ColorLut colorLut;
        private final boolean preview;
        private final int holdMs;
        private final long startMs;
        private final int serial;

        private State(ColorLut colorLut, boolean preview, int holdMs, long startMs, int serial) {
            this.colorLut = colorLut;
            this.preview = preview;
            this.holdMs = holdMs;
            this.startMs = startMs;
            this.serial = serial;
        }

        private static State off(int serial) {
            return new State(null, false, 0, 0, serial);
        }
    }

    private static class DynamicLutShaderProgram extends BaseGlShaderProgram {

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
                "uniform float uMode;\n" +
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
                "  if (uMode < 0.5) {\n" +
                "    gl_FragColor = inputColor;\n" +
                "    return;\n" +
                "  }\n" +
                "  vec3 lutColor = applyLookup(inputColor.rgb);\n" +
                "  float mask = uMode > 1.5 ? 1.0 : smoothstep(uSplitEdge - uLineWidth, uSplitEdge + uLineWidth, vTexSamplingCoord.x);\n" +
                "  vec3 color = mix(inputColor.rgb, lutColor, mask);\n" +
                "  float line = 1.0 - smoothstep(0.0, uLineWidth, abs(vTexSamplingCoord.x - uSplitEdge));\n" +
                "  line *= step(0.5, uMode) * step(uMode, 1.5) * step(0.001, uSplitEdge) * step(uSplitEdge, 0.999);\n" +
                "  gl_FragColor.rgb = mix(color, vec3(1.0), line * 0.72);\n" +
                "  gl_FragColor.a = inputColor.a;\n" +
                "}\n";

        private final DynamicLutEffect effect;
        private final Context context;
        private final GlProgram glProgram;
        private final Set<ColorLut> released = Collections.newSetFromMap(new IdentityHashMap<>());
        private ColorLut activeColorLut;
        private GlShaderProgram lutShaderProgram;
        private int width;
        private int loggedSerial = Integer.MIN_VALUE;

        private DynamicLutShaderProgram(Context context, DynamicLutEffect effect, boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 1);
            if (useHdr) throw new VideoFrameProcessingException("DynamicLutEffect does not support HDR colors.");
            this.effect = effect;
            this.context = context;
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
                float[] identity = GlUtil.create4x4IdentityMatrix();
                glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
                glProgram.setFloatsUniform("uTransformationMatrix", identity);
                glProgram.setFloatsUniform("uTexTransformationMatrix", identity);
            } catch (GlUtil.GlException e) {
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
                State state = effect.getState();
                switchColorLut(state.colorLut);
                releaseRetired(state.colorLut);
                logState(state, presentationTimeUs);
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                glProgram.setFloatUniform("uSplitEdge", 0f);
                glProgram.setFloatUniform("uLineWidth", width > 0 ? Math.max(1.2f / width, 0.0015f) : 0.002f);
                if (state.colorLut == null) {
                    glProgram.setSamplerTexIdUniform("uColorLut", inputTexId, 1);
                    glProgram.setFloatUniform("uColorLutLength", 2f);
                    glProgram.setFloatUniform("uMode", 0f);
                } else {
                    glProgram.setSamplerTexIdUniform("uColorLut", state.colorLut.getLutTextureId(presentationTimeUs), 1);
                    glProgram.setFloatUniform("uColorLutLength", state.colorLut.getLength(presentationTimeUs));
                    glProgram.setFloatUniform("uMode", state.preview ? 1f : 2f);
                    glProgram.setFloatUniform("uSplitEdge", getSplitEdge(state));
                }
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
                releaseActiveColorLut();
                releaseRetired(null);
                glProgram.delete();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        private void switchColorLut(ColorLut colorLut) throws VideoFrameProcessingException {
            if (activeColorLut == colorLut) return;
            releaseActiveColorLut();
            activeColorLut = colorLut;
            if (activeColorLut != null) lutShaderProgram = activeColorLut.toGlShaderProgram(context, false);
        }

        private void releaseActiveColorLut() throws VideoFrameProcessingException {
            if (activeColorLut == null) return;
            try {
                if (lutShaderProgram != null) lutShaderProgram.release();
                else activeColorLut.release();
                released.add(activeColorLut);
                activeColorLut = null;
                lutShaderProgram = null;
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        private void releaseRetired(ColorLut current) throws GlUtil.GlException {
            for (ColorLut colorLut : effect.drainRetired()) {
                if (colorLut == null || colorLut == current || released.contains(colorLut)) continue;
                colorLut.release();
                released.add(colorLut);
            }
        }

        private void logState(State state, long presentationTimeUs) {
            if (!SpiderDebug.isEnabled() || loggedSerial == state.serial) return;
            loggedSerial = state.serial;
            SpiderDebug.log("lut-gl", "draw serial=%d mode=%s preview=%s width=%d pts=%d lut=%s", state.serial, state.colorLut == null ? "off" : "lut", state.preview, width, presentationTimeUs, state.colorLut == null ? "none" : state.colorLut.getClass().getSimpleName());
        }

        private float getSplitEdge(State state) {
            if (!state.preview) return 0f;
            long elapsed = SystemClock.elapsedRealtime() - state.startMs;
            if (elapsed <= state.holdMs) return 0.5f;
            float progress = Math.min(1f, (elapsed - state.holdMs) / (float) SLIDE_MS);
            return 0.5f * (1f - progress);
        }
    }
}
