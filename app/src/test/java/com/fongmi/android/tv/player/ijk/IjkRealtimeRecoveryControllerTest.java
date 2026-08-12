package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkRealtimeRecoveryControllerTest {

    private static final PlaybackAutoContext.SessionToken SESSION_A =
            new PlaybackAutoContext.SessionToken("p-a-1", 1);
    private static final PlaybackAutoContext.SessionToken SESSION_B =
            new PlaybackAutoContext.SessionToken("p-b-1", 2);
    private static final IjkBufferPolicy.Config REALTIME =
            new IjkBufferPolicy.Config(4, 100, 300, 1_000);

    @Test
    public void sustainedBacklogNeedsThreeSamplesAndTenSeconds() {
        IjkRealtimeRecoveryController controller = controller();

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.CONFIRMING,
                controller.evaluate(input(SESSION_A, 0, 15_000,
                        1_000_000, 500)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.CONFIRMING,
                controller.evaluate(input(SESSION_A, 5_000, 15_000,
                        1_000_000, 500)).reason());
        IjkRealtimeRecoveryPolicy.Decision decision = controller.evaluate(
                input(SESSION_A, 10_000, 15_000, 1_000_000, 500));

        assertTrue(decision.requestsRecovery());
        assertEquals(3, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void shortSpikeClearsWhenQueueReturnsHealthy() {
        IjkRealtimeRecoveryController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 15_000, 1_000_000, 500));

        IjkRealtimeRecoveryPolicy.Decision decision = controller.evaluate(
                input(SESSION_A, 5_000, 1_000, 500_000, 100));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.HEALTHY,
                decision.reason());
        assertEquals(0, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void durationGrowthIsConfirmedAcrossFreshSamples() {
        IjkRealtimeRecoveryController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 4_000, 500_000, 100));
        controller.evaluate(input(SESSION_A, 5_000, 7_000, 800_000, 200));
        controller.evaluate(input(SESSION_A, 10_000, 10_000, 1_100_000, 300));
        controller.evaluate(input(SESSION_A, 15_000, 13_000, 1_400_000, 400));
        IjkRealtimeRecoveryPolicy.Decision decision = controller.evaluate(
                input(SESSION_A, 20_000, 14_000, 1_500_000, 450));

        assertTrue(decision.requestsRecovery());
        assertTrue(decision.durationGrowthMsPerSecond() >= 500);
        assertEquals(3, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void pauseAndReloadInProgressDiscardOldEvidence() {
        IjkRealtimeRecoveryController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 15_000, 1_000_000, 500));
        controller.evaluate(input(SESSION_A, 5_000, 15_000, 1_000_000, 500));

        IjkRealtimeRecoveryPolicy.Decision paused = controller.evaluate(
                input(SESSION_A, 7_000, 15_000, 1_000_000, 500,
                        false, false, PlaybackAutoContext.Protocol.RTSP));
        IjkRealtimeRecoveryPolicy.Decision pending = controller.evaluate(
                input(SESSION_A, 8_000, 15_000, 1_000_000, 500,
                        true, true, PlaybackAutoContext.Protocol.RTSP));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.INACTIVE,
                paused.reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.ACTION_PENDING,
                pending.reason());
        assertEquals(0, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void userSeekSuppressesRecoveryAndRequiresNewEvidence() {
        IjkRealtimeRecoveryController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 15_000, 1_000_000, 500));
        controller.evaluate(input(SESSION_A, 5_000, 15_000, 1_000_000, 500));
        controller.onUserSeek(SESSION_A, 6_000);

        IjkRealtimeRecoveryPolicy.Decision suppressed = controller.evaluate(
                input(SESSION_A, 10_000, 20_000, 1_500_000, 700));
        IjkRealtimeRecoveryPolicy.Decision afterWindow = controller.evaluate(
                input(SESSION_A, 22_000, 20_000, 1_500_000, 700));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.USER_SEEK,
                suppressed.reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.CONFIRMING,
                afterWindow.reason());
        assertEquals(1, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void reservedActionBlocksCallbacksAndTracksCompletion() {
        IjkRealtimeRecoveryController controller = controller();
        IjkRealtimeRecoveryPolicy.Decision decision = confirmed(controller);

        assertTrue(controller.beginAction(SESSION_A, decision, 10_000));
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.ACTION_PENDING,
                controller.evaluate(input(SESSION_A, 15_000, 20_000,
                        2_000_000, 900)).reason());
        controller.completeAction(SESSION_A, true);

        IjkRealtimeRecoveryController.Snapshot snapshot = controller.snapshot();
        assertEquals(1, snapshot.recoveryAttempts());
        assertEquals(1, snapshot.successfulRecoveries());
        assertEquals(0, snapshot.failedRecoveries());
        assertFalse(snapshot.actionPending());
    }

    @Test
    public void failedActionAndNewSessionResetAreBoundedBySession() {
        IjkRealtimeRecoveryController controller = controller();
        IjkRealtimeRecoveryPolicy.Decision decision = confirmed(controller);
        assertTrue(controller.beginAction(SESSION_A, decision, 10_000));
        controller.completeAction(SESSION_A, false);

        assertEquals(1, controller.snapshot().failedRecoveries());
        controller.beginSession(SESSION_B);
        assertEquals(0, controller.snapshot().recoveryAttempts());
        controller.endSession(SESSION_A);
        assertEquals(SESSION_B, controller.snapshot().session());
    }

    @Test
    public void staleSessionAndOutOfOrderSamplesCannotTrigger() {
        IjkRealtimeRecoveryController controller = controller();
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.STALE_SESSION,
                controller.evaluate(input(SESSION_B, 0, 15_000,
                        1_000_000, 500)).reason());
        controller.evaluate(input(SESSION_A, 10_000, 15_000,
                1_000_000, 500));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.STALE_SAMPLE,
                controller.evaluate(input(SESSION_A, 5_000, 15_000,
                        1_000_000, 500)).reason());
    }

    @Test
    public void longSamplingGapRestartsConfirmationWindow() {
        IjkRealtimeRecoveryController controller = controller();
        controller.evaluate(input(SESSION_A, 0, 15_000, 1_000_000, 500));
        controller.evaluate(input(SESSION_A, 5_000, 15_000, 1_000_000, 500));

        IjkRealtimeRecoveryPolicy.Decision afterGap = controller.evaluate(
                input(SESSION_A, 25_001, 15_000, 1_000_000, 500));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.CONFIRMING,
                afterGap.reason());
        assertEquals(1, controller.snapshot().consecutiveRiskSamples());
        assertEquals(25_001, controller.snapshot().riskSinceMs());
    }

    @Test
    public void rtmpIsEligibleButHlsAndUnknownQueueAreNot() {
        IjkRealtimeRecoveryController controller = controller();
        IjkRealtimeRecoveryPolicy.Decision rtmp = controller.evaluate(
                input(SESSION_A, 0, 15_000, 1_000_000, 500,
                        true, false, PlaybackAutoContext.Protocol.RTMP));
        IjkRealtimeRecoveryPolicy.Decision hls = controller.evaluate(
                input(SESSION_A, 5_000, 15_000, 1_000_000, 500,
                        true, false, PlaybackAutoContext.Protocol.HLS));
        IjkRealtimeRecoveryPolicy.Decision unknown = controller.evaluate(
                new IjkRealtimeRecoveryController.Input(
                        SESSION_A, true, true, true,
                        PlaybackAutoContext.Protocol.RTSP,
                        true, true, true, false, false,
                        IjkRealtimeRecoveryPolicy.QueueSnapshot.unknown(),
                        REALTIME, 10_000));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.CONFIRMING,
                rtmp.reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.NOT_REALTIME_PROTOCOL,
                hls.reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.EVIDENCE_UNKNOWN,
                unknown.reason());
    }

    private static IjkRealtimeRecoveryController controller() {
        IjkRealtimeRecoveryController controller =
                new IjkRealtimeRecoveryController();
        controller.beginSession(SESSION_A);
        return controller;
    }

    private static IjkRealtimeRecoveryPolicy.Decision confirmed(
            IjkRealtimeRecoveryController controller) {
        controller.evaluate(input(SESSION_A, 0, 15_000, 1_000_000, 500));
        controller.evaluate(input(SESSION_A, 5_000, 15_000, 1_000_000, 500));
        return controller.evaluate(input(SESSION_A, 10_000, 15_000,
                1_000_000, 500));
    }

    private static IjkRealtimeRecoveryController.Input input(
            PlaybackAutoContext.SessionToken session,
            long now,
            long duration,
            long bytes,
            long packets) {
        return input(session, now, duration, bytes, packets,
                true, false, PlaybackAutoContext.Protocol.RTSP);
    }

    private static IjkRealtimeRecoveryController.Input input(
            PlaybackAutoContext.SessionToken session,
            long now,
            long duration,
            long bytes,
            long packets,
            boolean active,
            boolean reloadPending,
            PlaybackAutoContext.Protocol protocol) {
        return new IjkRealtimeRecoveryController.Input(
                session, true, true, true, protocol,
                active, true, true, false, reloadPending,
                new IjkRealtimeRecoveryPolicy.QueueSnapshot(
                        true, false, true, 0, duration,
                        0, bytes, 0, packets),
                REALTIME, now);
    }
}
