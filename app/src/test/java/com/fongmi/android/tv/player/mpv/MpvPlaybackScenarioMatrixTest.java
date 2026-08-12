package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.scenario.PlaybackScenarioMatrix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvPlaybackScenarioMatrixTest {

    @Test
    public void initialForwardCacheMatchesRepresentativeDeviceAndPathCaps() {
        assertForward(PlaybackScenarioMatrix.Id.LOW_RAM_HLS_VOD,
                MpvAutoControlPolicy.MIN_FORWARD_BYTES);
        assertForward(PlaybackScenarioMatrix.Id.ORDINARY_TV_PROGRESSIVE,
                MpvAutoControlPolicy.CONSERVATIVE_FORWARD_BYTES);
        assertForward(PlaybackScenarioMatrix.Id.FOUR_K_HDR_DASH,
                MpvAutoControlPolicy.BALANCED_FORWARD_BYTES);
        assertForward(PlaybackScenarioMatrix.Id.HLS_LIVE,
                MpvAutoControlPolicy.CONSERVATIVE_FORWARD_BYTES);
        assertForward(PlaybackScenarioMatrix.Id.REMUX_EXTERNAL_LOOPBACK,
                MpvAutoControlPolicy.CONSERVATIVE_FORWARD_BYTES);
    }

    @Test
    public void everyInitialAutomaticDecisionStartsWithZeroBackCache() {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            MpvAutoControlPolicy.Decision decision = initial(scenario);

            assertTrue(scenario.id().name(), decision.requestsApply());
            assertEquals(scenario.id().name(),
                    MpvAutoControlPolicy.INITIAL_BACK_BYTES,
                    decision.backBytes());
        }
    }

    @Test
    public void forwardPolicyNeverExceedsCapacityResourceOrGuard() {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            MpvAutoControlPolicy.Decision initial = initial(scenario);
            MpvForwardCachePolicy.Assessment assessment = forward(
                    scenario, initial.forwardBytes());
            String message = scenario.id().name();

            assertTrue(message, assessment.active());
            assertTrue(message, assessment.safeTargetBytes()
                    <= assessment.capacityTargetBytes());
            assertTrue(message, assessment.safeTargetBytes()
                    <= assessment.resourceTargetBytes());
            assertTrue(message, assessment.safeTargetBytes()
                    <= MpvForwardCachePolicy.MAX_FORWARD_BYTES);
            assertTrue(message, assessment.safeTargetBytes()
                    >= MpvForwardCachePolicy.MIN_FORWARD_BYTES);
        }
    }

    @Test
    public void oneLargeBackwardSeekIsEvidenceButDoesNotEnableBackCache() {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(
                PlaybackScenarioMatrix.Id.SEEK_RANGE_VOD);
        assertTrue(scenario.context(PlaybackAutoContext.Kernel.MPV).active());

        MpvBackCachePolicy.SeekObservation observation =
                MpvBackCachePolicy.observeSeek(
                        true, true, 120_000, 30_000);

        assertTrue(observation.qualifying());
        assertTrue(observation.large());
        assertEquals(90_000, observation.distanceMs());
        assertEquals(0, MpvBackCachePolicy.learnedTier(1));
        assertEquals(MpvBackCachePolicy.MIN_BACK_BYTES,
                MpvBackCachePolicy.learnedTier(2));
    }

    @Test
    public void liveAndOpaqueResourcesDisableBackCacheEligibility() {
        assertBackDisabled(
                PlaybackScenarioMatrix.Id.HLS_LIVE,
                MpvBackCachePolicy.Reason.LIVE_RESOURCE);
        assertBackDisabled(
                PlaybackScenarioMatrix.Id.RTSP_LIVE,
                MpvBackCachePolicy.Reason.LIVE_RESOURCE);
        assertBackDisabled(
                PlaybackScenarioMatrix.Id.REMUX_EXTERNAL_LOOPBACK,
                MpvBackCachePolicy.Reason.OPAQUE_PATH);
    }

    private static void assertForward(
            PlaybackScenarioMatrix.Id id,
            long expectedBytes) {
        MpvAutoControlPolicy.Decision decision = initial(
                PlaybackScenarioMatrix.get(id));

        assertTrue(id.name(), decision.requestsApply());
        assertEquals(id.name(), expectedBytes, decision.forwardBytes());
        assertEquals(id.name(), 0, decision.backBytes());
    }

    private static void assertBackDisabled(
            PlaybackScenarioMatrix.Id id,
            MpvBackCachePolicy.Reason expectedReason) {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(id);
        MpvAutoControlPolicy.Decision initial = initial(scenario);
        MpvForwardCachePolicy.Assessment forward = forward(
                scenario, initial.forwardBytes());
        boolean live = scenario.streamKind() != PlaybackAutoContext.StreamKind.VOD;
        MpvBackCachePolicy.Request request = MpvBackCachePolicy.requestFrom(
                scenario.context(PlaybackAutoContext.Kernel.MPV),
                true,
                true,
                true,
                true,
                live,
                initial.forwardBytes(),
                forward,
                PlaybackScenarioMatrix.NOW_MS);
        MpvBackCachePolicy.Assessment assessment =
                MpvBackCachePolicy.resolve(request);

        assertTrue(id.name(), assessment.active());
        assertFalse(id.name(), assessment.eligible());
        assertTrue(id.name(), assessment.forceZero());
        assertEquals(id.name(), 0, assessment.safeBackBytes());
        assertEquals(id.name(), expectedReason, assessment.reason());
    }

    private static MpvAutoControlPolicy.Decision initial(
            PlaybackScenarioMatrix.Scenario scenario) {
        PlaybackAutoContext context = scenario.context(PlaybackAutoContext.Kernel.MPV);
        return MpvAutoControlPolicy.resolve(MpvAutoControlPolicy.requestFrom(
                context,
                true,
                true,
                true,
                PlaybackScenarioMatrix.NOW_MS));
    }

    private static MpvForwardCachePolicy.Assessment forward(
            PlaybackScenarioMatrix.Scenario scenario,
            long initialBytes) {
        return MpvForwardCachePolicy.assess(
                scenario.context(PlaybackAutoContext.Kernel.MPV),
                true,
                true,
                true,
                initialBytes,
                PlaybackScenarioMatrix.NOW_MS);
    }
}
