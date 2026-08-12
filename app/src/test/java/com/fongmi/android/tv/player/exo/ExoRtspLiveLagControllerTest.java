package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoRtspLiveLagControllerTest {

    private static final PlaybackAutoContext.SessionToken SESSION_A =
            new PlaybackAutoContext.SessionToken("p-a-1", 1);
    private static final PlaybackAutoContext.SessionToken SESSION_B =
            new PlaybackAutoContext.SessionToken("p-b-1", 2);

    @Test
    public void sustainedLagNeedsThreeSamplesAndTenSeconds() {
        ExoRtspLiveLagController controller = controller();

        assertEquals(ExoRtspLiveLagPolicy.Reason.CONFIRMING,
                controller.evaluate(input(SESSION_A, 0, 40_000, 10_000, true, true)).reason());
        assertEquals(ExoRtspLiveLagPolicy.Reason.CONFIRMING,
                controller.evaluate(input(SESSION_A, 5_000, 41_000, 10_000, true, true)).reason());
        ExoRtspLiveLagPolicy.Decision decision =
                controller.evaluate(input(SESSION_A, 10_000, 42_000, 10_000, true, true));

        assertEquals(ExoRtspLiveLagPolicy.Action.SEEK_LIVE_EDGE, decision.action());
        assertEquals(3, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void shortSpikeClearsWhenLagReturnsHealthy() {
        ExoRtspLiveLagController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 40_000, 10_000, true, true));

        ExoRtspLiveLagPolicy.Decision healthy =
                controller.evaluate(input(SESSION_A, 5_000, 8_000, 10_000, true, true));

        assertEquals(ExoRtspLiveLagPolicy.Action.HOLD, healthy.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.HEALTHY, healthy.reason());
        assertEquals(0, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void lagGrowthBelowAbsoluteLimitIsDetectedConservatively() {
        ExoRtspLiveLagController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 15_000, 10_000, false, false));
        controller.evaluate(input(SESSION_A, 5_000, 18_000, 10_000, false, false));
        controller.evaluate(input(SESSION_A, 10_000, 21_000, 10_000, false, false));
        controller.evaluate(input(SESSION_A, 15_000, 24_000, 10_000, false, false));
        ExoRtspLiveLagPolicy.Decision decision =
                controller.evaluate(input(SESSION_A, 20_000, 27_000, 10_000, false, false));

        assertEquals(ExoRtspLiveLagPolicy.Trigger.LAG_GROWING, decision.trigger());
        assertEquals(ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, decision.action());
        assertTrue(decision.lagGrowthMsPerSecond() >= 500);
    }

    @Test
    public void unknownLiveOffsetUsesOnlyLargeSustainedBufferGrowth() {
        ExoRtspLiveLagController controller = controller();
        controller.evaluate(input(SESSION_A, 0, -1, 90_000, false, false));
        controller.evaluate(input(SESSION_A, 5_000, -1, 95_000, false, false));
        controller.evaluate(input(SESSION_A, 10_000, -1, 100_000, false, false));
        controller.evaluate(input(SESSION_A, 15_000, -1, 105_000, false, false));
        ExoRtspLiveLagPolicy.Decision decision =
                controller.evaluate(input(SESSION_A, 20_000, -1, 110_000, false, false));

        assertEquals(ExoRtspLiveLagPolicy.Trigger.BUFFER_GROWING, decision.trigger());
        assertEquals(ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, decision.action());
        assertTrue(decision.bufferGrowthMsPerSecond() >= 500);
    }

    @Test
    public void actionIsReservedAndCommittedBeforeCallbacks() {
        ExoRtspLiveLagController controller = controller();
        ExoRtspLiveLagPolicy.Decision decision = confirmed(controller, 0);

        assertEquals(ExoRtspLiveLagPolicy.Action.SEEK_LIVE_EDGE, decision.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.ACTION_PENDING,
                controller.evaluate(input(SESSION_A, 15_000, 50_000, 10_000, true, true)).reason());
        assertTrue(controller.beginAction(SESSION_A, decision.action(), 15_000));
        controller.completeAction(SESSION_A, decision.action(), true);

        ExoRtspLiveLagController.Snapshot snapshot = controller.snapshot();
        assertEquals(1, snapshot.seekAttempts());
        assertEquals(1, snapshot.recoveryAttempts());
        assertTrue(snapshot.lastActionSucceeded());
    }

    @Test
    public void failedSeekStillFallsBackWithoutLooping() {
        ExoRtspLiveLagController controller = controller();
        ExoRtspLiveLagPolicy.Decision seek = confirmed(controller, 0);
        assertTrue(controller.beginAction(SESSION_A, seek.action(), 10_000));
        controller.completeAction(SESSION_A, seek.action(), false);

        controller.evaluate(input(SESSION_A, 40_000, 50_000, 10_000, true, true));
        controller.evaluate(input(SESSION_A, 45_000, 50_000, 10_000, true, true));
        ExoRtspLiveLagPolicy.Decision fallback =
                controller.evaluate(input(SESSION_A, 50_000, 50_000, 10_000, true, true));

        assertEquals(ExoRtspLiveLagPolicy.Action.REBUILD_SESSION, fallback.action());
    }

    @Test
    public void userSeekClearsEvidenceAndSuppressesImmediateRecovery() {
        ExoRtspLiveLagController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 40_000, 10_000, true, true));
        controller.evaluate(input(SESSION_A, 5_000, 40_000, 10_000, true, true));
        controller.onUserSeek(SESSION_A, 6_000);

        ExoRtspLiveLagPolicy.Decision decision =
                controller.evaluate(input(SESSION_A, 10_000, 50_000, 10_000, true, true));

        assertEquals(ExoRtspLiveLagPolicy.Action.HOLD, decision.action());
        assertEquals(ExoRtspLiveLagPolicy.Reason.USER_SEEK, decision.reason());
        assertEquals(0, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void pauseAndDiscontinuityDiscardOldEvidence() {
        ExoRtspLiveLagController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 40_000, 10_000, true, true));
        controller.evaluate(input(SESSION_A, 5_000, 40_000, 10_000, true, true));

        ExoRtspLiveLagPolicy.Decision paused = controller.evaluate(
                new ExoRtspLiveLagController.Input(SESSION_A, true, true, true, true,
                        false, true, false, false, true, true,
                        50_000, 10_000, 7_000));
        controller.onPositionDiscontinuity(SESSION_A);

        assertEquals(ExoRtspLiveLagPolicy.Reason.INACTIVE, paused.reason());
        assertEquals(0, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void oldSessionCannotSpendNewSessionBudget() {
        ExoRtspLiveLagController controller = controller();

        ExoRtspLiveLagPolicy.Decision stale =
                controller.evaluate(input(SESSION_B, 0, 40_000, 10_000, true, true));

        assertEquals(ExoRtspLiveLagPolicy.Reason.STALE_SESSION, stale.reason());
        assertEquals(0, controller.snapshot().recoveryAttempts());
    }

    @Test
    public void newSessionResetsRetriesAndSamples() {
        ExoRtspLiveLagController controller = controller();
        ExoRtspLiveLagPolicy.Decision seek = confirmed(controller, 0);
        assertTrue(controller.beginAction(SESSION_A, seek.action(), 10_000));

        controller.beginSession(SESSION_B);

        assertEquals(SESSION_B, controller.snapshot().session());
        assertEquals(0, controller.snapshot().seekAttempts());
        assertEquals(0, controller.snapshot().recoveryAttempts());
        assertFalse(controller.snapshot().lastActionSucceeded());
    }

    private static ExoRtspLiveLagController controller() {
        ExoRtspLiveLagController controller = new ExoRtspLiveLagController();
        controller.beginSession(SESSION_A);
        return controller;
    }

    private static ExoRtspLiveLagPolicy.Decision confirmed(
            ExoRtspLiveLagController controller, long baseMs) {
        controller.evaluate(input(SESSION_A, baseMs, 40_000, 10_000, true, true));
        controller.evaluate(input(SESSION_A, baseMs + 5_000, 41_000, 10_000, true, true));
        return controller.evaluate(input(
                SESSION_A, baseMs + 10_000, 42_000, 10_000, true, true));
    }

    private static ExoRtspLiveLagController.Input input(
            PlaybackAutoContext.SessionToken session,
            long nowMs,
            long lagMs,
            long bufferedMs,
            boolean liveEdgeReliable,
            boolean seekAvailable) {
        return new ExoRtspLiveLagController.Input(
                session, true, true, true, true, true, true,
                false, false, liveEdgeReliable, seekAvailable,
                lagMs, bufferedMs, nowMs);
    }
}
