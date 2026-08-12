package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;

/** Immutable player-build settings that keep one frame scheduling unit truthful. */
public record ExoFrameSchedulingPlayerSettings(
        ExoFrameSchedulingExperimentPolicy.Decision decision,
        boolean dynamicSchedulingEnabled,
        int codecQueueMode) {

    public ExoFrameSchedulingPlayerSettings {
        decision = decision == null
                ? ExoFrameSchedulingExperimentPolicy.stableDecision(
                false, false, false)
                : decision;
        codecQueueMode = normalizeCodecQueueMode(codecQueueMode);
    }

    public static ExoFrameSchedulingPlayerSettings capture(int decode) {
        boolean dynamicSchedulingEnabled = PlaybackPerformanceSetting
                .isDynamicSchedulingEnabled();
        int codecQueueMode = ExoPerformanceSetting.getCodecQueueMode();
        return new ExoFrameSchedulingPlayerSettings(
                ExoUtil.resolveFrameSchedulingDecision(
                        decode,
                        dynamicSchedulingEnabled,
                        codecQueueMode),
                dynamicSchedulingEnabled,
                codecQueueMode);
    }

    public ExoFrameSchedulingPlayerSettings withDecision(
            ExoFrameSchedulingExperimentPolicy.Decision decision) {
        return new ExoFrameSchedulingPlayerSettings(
                decision, dynamicSchedulingEnabled, codecQueueMode);
    }

    public boolean samePlayerConfiguration(
            ExoFrameSchedulingPlayerSettings other) {
        return other != null
                && decision.sameRendererSettings(other.decision)
                && dynamicSchedulingEnabled
                == other.dynamicSchedulingEnabled
                && codecQueueMode == other.codecQueueMode;
    }

    public String codecQueueModeLabel() {
        return switch (codecQueueMode) {
            case ExoPerformanceSetting.CODEC_QUEUE_ASYNC -> "async";
            case ExoPerformanceSetting.CODEC_QUEUE_SYNC -> "sync";
            default -> "auto";
        };
    }

    private static int normalizeCodecQueueMode(int value) {
        return value == ExoPerformanceSetting.CODEC_QUEUE_ASYNC
                || value == ExoPerformanceSetting.CODEC_QUEUE_SYNC
                ? value : ExoPerformanceSetting.CODEC_QUEUE_AUTO;
    }
}
