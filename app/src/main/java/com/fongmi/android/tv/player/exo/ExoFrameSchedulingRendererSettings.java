package com.fongmi.android.tv.player.exo;

import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;

/** Applies one immutable frame scheduling decision to every MediaCodec renderer path. */
final class ExoFrameSchedulingRendererSettings {

    private final long earlySchedulingThresholdUs;
    private final boolean durationToProgressEnabled;

    private ExoFrameSchedulingRendererSettings(
            long earlySchedulingThresholdUs,
            boolean durationToProgressEnabled) {
        this.earlySchedulingThresholdUs = earlySchedulingThresholdUs;
        this.durationToProgressEnabled = durationToProgressEnabled;
    }

    static ExoFrameSchedulingRendererSettings from(
            ExoFrameSchedulingExperimentPolicy.Decision decision) {
        ExoFrameSchedulingExperimentPolicy.RendererSettings settings =
                decision == null
                        ? new ExoFrameSchedulingExperimentPolicy.RendererSettings(
                        ExoFrameSchedulingExperimentPolicy
                                .BASELINE_EARLY_SCHEDULING_THRESHOLD_US,
                        false)
                        : decision.rendererSettings();
        return new ExoFrameSchedulingRendererSettings(
                settings.earlySchedulingThresholdUs(),
                settings.durationToProgressEnabled());
    }

    DefaultRenderersFactory apply(DefaultRenderersFactory factory) {
        factory.setVideoRendererEarlySchedulingThresholdUs(
                earlySchedulingThresholdUs);
        factory.setEnableMediaCodecVideoRendererDurationToProgressUs(
                durationToProgressEnabled);
        return factory;
    }

    MediaCodecVideoRenderer.Builder apply(
            MediaCodecVideoRenderer.Builder builder) {
        return builder
                .setEarlySchedulingThresholdUs(earlySchedulingThresholdUs)
                .setEnableDurationToProgressUs(durationToProgressEnabled);
    }

    long earlySchedulingThresholdUs() {
        return earlySchedulingThresholdUs;
    }

    boolean durationToProgressEnabled() {
        return durationToProgressEnabled;
    }
}
