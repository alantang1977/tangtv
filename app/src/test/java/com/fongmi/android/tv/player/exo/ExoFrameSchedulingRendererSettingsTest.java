package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoFrameSchedulingRendererSettingsTest {

    @Test
    public void oneSettingsObjectCarriesBothRendererFactors() {
        ExoFrameSchedulingExperimentPolicy.Decision decision = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_100_DURATION_ON);
        ExoFrameSchedulingRendererSettings settings =
                ExoFrameSchedulingRendererSettings.from(decision);

        assertEquals(100_000L, settings.earlySchedulingThresholdUs());
        assertTrue(settings.durationToProgressEnabled());
    }

    @Test
    public void nullDecisionFailsBackToMedia3Threshold() {
        ExoFrameSchedulingRendererSettings settings =
                ExoFrameSchedulingRendererSettings.from(null);

        assertEquals(50_000L, settings.earlySchedulingThresholdUs());
        assertFalse(settings.durationToProgressEnabled());
    }

    private static ExoFrameSchedulingExperimentPolicy.Decision decision(
            ExoFrameSchedulingExperimentPolicy.Unit unit) {
        String device = ExoFrameSchedulingExperimentIdentity.deviceDigest(
                "f", "v", "m", 1, "1", "media3");
        return ExoFrameSchedulingExperimentPolicy.decide(
                new ExoFrameSchedulingExperimentPolicy.Input(
                        true, true, true, true, false, true,
                        ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                                new ExoFrameSchedulingExperimentPolicy
                                        .RawAssignment(1, device, unit.id()),
                                device)));
    }
}
