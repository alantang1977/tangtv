package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoRtspLiveLagPolicyTest {

    @Test
    public void nonAutomaticOrNonRtspSessionsNeverRecover() {
        ExoRtspLiveLagPolicy.Decision manual = ExoRtspLiveLagPolicy.resolve(
                request(false, true, true, true, 40_000, 3, 0, 0, 0));
        ExoRtspLiveLagPolicy.Decision hls = ExoRtspLiveLagPolicy.resolve(
                request(true, true, false, true, 40_000, 3, 0, 0, 0));

        assertEquals(ExoRtspLiveLagPolicy.Action.HOLD, manual.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.NOT_AUTOMATIC_EXO, manual.reason());
        assertEquals(ExoRtspLiveLagPolicy.Action.HOLD, hls.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.NOT_RTSP, hls.reason());
    }

    @Test
    public void rtspVodAndPausedPlaybackStayUntouched() {
        ExoRtspLiveLagPolicy.Request vod = request(true, true, true, false,
                40_000, 3, 0, 0, 0);
        ExoRtspLiveLagPolicy.Request paused = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, false, true, false, false,
                true, true, 40_000, 0, 10_000, 0, 3, 0,
                0, 0, 0, ExoRtspLiveLagPolicy.Action.HOLD, -1, 10_000);

        assertEquals(ExoRtspLiveLagPolicy.Reason.NOT_LIVE,
                ExoRtspLiveLagPolicy.resolve(vod).reason());
        assertEquals(ExoRtspLiveLagPolicy.Reason.INACTIVE,
                ExoRtspLiveLagPolicy.resolve(paused).reason());
    }

    @Test
    public void confirmationWindowRejectsShortLagSpike() {
        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(
                request(true, true, true, true, 40_000, 2, 5_000, 0, 0));

        assertEquals(ExoRtspLiveLagPolicy.Action.HOLD, decision.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.CONFIRMING, decision.reason());
        assertFalse(decision.confirmed());
    }

    @Test
    public void reliableLiveEdgeSeeksBeforeRebuild() {
        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(
                request(true, true, true, true, 40_000, 3, 0, 0, 0));

        assertEquals(ExoRtspLiveLagPolicy.Action.SEEK_LIVE_EDGE, decision.action());
        assertEquals(ExoRtspLiveLagPolicy.Trigger.LAG_EXCEEDED, decision.trigger());
        assertTrue(decision.requestsRecovery());
    }

    @Test
    public void unreliableEdgeRebuildsInsteadOfGuessingSeekTarget() {
        ExoRtspLiveLagPolicy.Request request = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, true, true, false, false,
                false, true, 40_000, 0, 10_000, 0, 3, 0,
                0, 0, 0, ExoRtspLiveLagPolicy.Action.HOLD, -1, 10_000);

        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(request);

        assertEquals(ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, decision.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.REBUILD_LAG, decision.reason());
    }

    @Test
    public void ineffectiveSeekFallsBackToRebuildAfterCooldown() {
        ExoRtspLiveLagPolicy.Request request = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, true, true, false, false,
                true, true, 50_000, 0, 10_000, 0, 3, 30_000,
                1, 0, 1, ExoRtspLiveLagPolicy.Action.SEEK_LIVE_EDGE, 10_000, 40_000);

        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(request);

        assertEquals(ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, decision.action());
    }

    @Test
    public void seekAndRebuildUseDifferentCooldowns() {
        ExoRtspLiveLagPolicy.Request seekCooldown = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, true, true, false, false,
                true, true, 50_000, 0, 10_000, 0, 3, 10_000,
                1, 0, 1, ExoRtspLiveLagPolicy.Action.SEEK_LIVE_EDGE, 10_000, 20_000);
        ExoRtspLiveLagPolicy.Request rebuildCooldown = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, true, true, false, false,
                false, false, 50_000, 0, 10_000, 0, 3, 60_000,
                0, 1, 1, ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, 10_000, 70_000);

        assertEquals(20_000,
                ExoRtspLiveLagPolicy.resolve(seekCooldown).cooldownRemainingMs());
        assertEquals(30_000,
                ExoRtspLiveLagPolicy.resolve(rebuildCooldown).cooldownRemainingMs());
    }

    @Test
    public void unknownLagCanOnlyUseConfirmedLargeBufferGrowth() {
        ExoRtspLiveLagPolicy.Request request = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, true, true, false, false,
                true, true, -1, Long.MIN_VALUE, 100_000, 1_000,
                3, 0, 0, 0, 0, ExoRtspLiveLagPolicy.Action.HOLD, -1, 10_000);

        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(request);

        assertEquals(ExoRtspLiveLagPolicy.Trigger.BUFFER_GROWING, decision.trigger());
        assertEquals(ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, decision.action());
    }

    @Test
    public void retryBudgetStopsConnectionStorm() {
        ExoRtspLiveLagPolicy.Request request = new ExoRtspLiveLagPolicy.Request(
                true, true, true, true, true, true, false, false,
                true, true, 50_000, 0, 10_000, 0, 3, 0,
                1, 2, 3, ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, 0, 100_000);

        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(request);

        assertEquals(ExoRtspLiveLagPolicy.Action.HOLD, decision.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.RETRY_LIMIT, decision.reason());
    }

    private static ExoRtspLiveLagPolicy.Request request(
            boolean automatic,
            boolean exo,
            boolean rtsp,
            boolean live,
            long lagMs,
            int samples,
            long riskSinceMs,
            int seekAttempts,
            int rebuildAttempts) {
        return new ExoRtspLiveLagPolicy.Request(
                automatic, exo, rtsp, live, true, true, false, false,
                true, true, lagMs, 0, 10_000, 0, samples, riskSinceMs,
                seekAttempts, rebuildAttempts, seekAttempts + rebuildAttempts,
                ExoRtspLiveLagPolicy.Action.HOLD, -1, 10_000);
    }
}
