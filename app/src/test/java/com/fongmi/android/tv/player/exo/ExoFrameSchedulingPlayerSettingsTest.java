package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.setting.ExoPerformanceSetting;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoFrameSchedulingPlayerSettingsTest {

    @Test
    public void dynamicSchedulingChangeRequiresPlayerRebuild() {
        ExoFrameSchedulingExperimentPolicy.Decision decision = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_50_DURATION_OFF,
                true,
                false);
        ExoFrameSchedulingPlayerSettings enabled = settings(
                decision, true, ExoPerformanceSetting.CODEC_QUEUE_AUTO);
        ExoFrameSchedulingPlayerSettings disabled = settings(
                decision, false, ExoPerformanceSetting.CODEC_QUEUE_AUTO);

        assertFalse(enabled.samePlayerConfiguration(disabled));
    }

    @Test
    public void codecQueueModeChangeRequiresPlayerRebuild() {
        ExoFrameSchedulingExperimentPolicy.Decision decision = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_50_DURATION_OFF,
                true,
                false);
        ExoFrameSchedulingPlayerSettings automatic = settings(
                decision, true, ExoPerformanceSetting.CODEC_QUEUE_AUTO);
        ExoFrameSchedulingPlayerSettings asynchronous = settings(
                decision, true, ExoPerformanceSetting.CODEC_QUEUE_ASYNC);

        assertFalse(automatic.samePlayerConfiguration(asynchronous));
    }

    @Test
    public void metadataOnlyChangeCanReuseIdenticalPlayerConfiguration() {
        ExoFrameSchedulingExperimentPolicy.Decision experiment = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_50_DURATION_ON,
                true,
                false);
        ExoFrameSchedulingExperimentPolicy.Decision stable =
                ExoFrameSchedulingExperimentPolicy.stableDecision(
                        true, true, false);
        ExoFrameSchedulingPlayerSettings current = settings(
                experiment, true, ExoPerformanceSetting.CODEC_QUEUE_AUTO);
        ExoFrameSchedulingPlayerSettings next = settings(
                stable, true, ExoPerformanceSetting.CODEC_QUEUE_AUTO);

        assertTrue(current.samePlayerConfiguration(next));
    }

    private static ExoFrameSchedulingPlayerSettings settings(
            ExoFrameSchedulingExperimentPolicy.Decision decision,
            boolean dynamic,
            int codecQueueMode) {
        return new ExoFrameSchedulingPlayerSettings(
                decision, dynamic, codecQueueMode);
    }

    private static ExoFrameSchedulingExperimentPolicy.Decision decision(
            ExoFrameSchedulingExperimentPolicy.Unit unit,
            boolean dynamic,
            boolean synchronous) {
        String device = ExoFrameSchedulingExperimentIdentity.deviceDigest(
                "f", "v", "m", 1, "1", "media3");
        return ExoFrameSchedulingExperimentPolicy.decide(
                new ExoFrameSchedulingExperimentPolicy.Input(
                        true, true, true, dynamic, synchronous, true,
                        ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                                new ExoFrameSchedulingExperimentPolicy
                                        .RawAssignment(1, device, unit.id()),
                                device)));
    }
}
