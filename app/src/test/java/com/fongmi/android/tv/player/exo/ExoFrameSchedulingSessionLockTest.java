package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoFrameSchedulingSessionLockTest {

    @Test
    public void rollbackWithDifferentThresholdWaitsForRendererRebuild() {
        ExoFrameSchedulingExperimentPolicy.Decision experiment = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_75_DURATION_ON);
        ExoFrameSchedulingExperimentPolicy.Decision stable =
                ExoFrameSchedulingExperimentPolicy.stableDecision(
                        true, true, false);
        ExoFrameSchedulingSessionLock lock =
                new ExoFrameSchedulingSessionLock(experiment);

        assertTrue(lock.requiresRendererRebuild(stable));
        assertFalse(lock.lockForNextPlayback(stable));
        assertTrue(lock.sessionDecision().experimentApplied());

        lock.onRendererRebuilt(stable);
        assertFalse(lock.sessionDecision().experimentApplied());
        assertFalse(lock.requiresRendererRebuild(stable));
    }

    @Test
    public void metadataCanSwitchAtPlaybackBoundaryWithoutRedundantRebuild() {
        ExoFrameSchedulingExperimentPolicy.Decision experiment = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_50_DURATION_ON);
        ExoFrameSchedulingExperimentPolicy.Decision stable =
                ExoFrameSchedulingExperimentPolicy.stableDecision(
                        true, true, false);
        ExoFrameSchedulingSessionLock lock =
                new ExoFrameSchedulingSessionLock(experiment);

        assertFalse(lock.requiresRendererRebuild(stable));
        assertTrue(lock.lockForNextPlayback(stable));
        assertFalse(lock.sessionDecision().experimentApplied());
        assertTrue(lock.rendererDecision().experimentApplied());
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
