package com.fongmi.android.tv.player.lut;

import android.content.Context;

import androidx.media3.common.Effect;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;

import java.util.Collections;
import java.util.List;

public final class LutPipelineWarmupEffect implements GlEffect {

    private static final List<Effect> EFFECTS = Collections.singletonList(new LutPipelineWarmupEffect());

    public static List<Effect> create() {
        return EFFECTS;
    }

    private LutPipelineWarmupEffect() {
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new PassthroughShaderProgram();
    }

    @Override
    public boolean isNoOp(int inputWidth, int inputHeight) {
        return false;
    }
}
