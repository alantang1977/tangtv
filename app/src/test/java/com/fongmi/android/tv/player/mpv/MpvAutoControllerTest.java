package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAutoControllerTest {

    @Test
    public void commitsAndCompletesBaselineWithinCurrentSession() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken token = token("trace-a", 1);
        controller.beginSession(token);

        MpvAutoControlPolicy.Decision decision = controller.evaluate(
                token, token, unknownMemoryRequest(true));

        assertEquals(MpvAutoController.State.BASELINE_READY, controller.snapshot().state());
        assertTrue(controller.beginApply(token, decision));
        assertEquals(MpvAutoController.State.APPLYING, controller.snapshot().state());
        controller.completeApply(token, decision, true);
        MpvAutoController.Snapshot snapshot = controller.snapshot();
        assertEquals(MpvAutoController.State.APPLIED, snapshot.state());
        assertEquals(1, snapshot.applyAttempts());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, snapshot.appliedForwardBytes());
        assertEquals(0, snapshot.appliedBackBytes());
        assertTrue(snapshot.lastApplySucceeded());
    }

    @Test
    public void staleFactsSessionCannotMutateCurrentController() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken current = token("trace-current", 2);
        PlaybackAutoContext.SessionToken stale = token("trace-stale", 1);
        controller.beginSession(current);

        MpvAutoControlPolicy.Decision decision = controller.evaluate(
                current, stale, unknownMemoryRequest(true));

        assertEquals(MpvAutoControlPolicy.Reason.STALE_SESSION, decision.reason());
        assertEquals(MpvAutoController.State.IDLE, controller.snapshot().state());
        assertEquals(0, controller.snapshot().evaluationCount());
    }

    @Test
    public void configPriorityMovesControllerToSuppressedState() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken token = token("trace-config", 3);
        controller.beginSession(token);

        MpvAutoControlPolicy.Decision decision = controller.evaluate(
                token, token, unknownMemoryRequest(false));

        assertEquals(MpvAutoControlPolicy.Reason.CONFIG_PRIORITY, decision.reason());
        assertEquals(MpvAutoController.State.SUPPRESSED, controller.snapshot().state());
        assertFalse(controller.beginApply(token, decision));
    }

    @Test
    public void failedNativeApplyConsumesOnlyItsAttempt() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken token = token("trace-failed", 4);
        controller.beginSession(token);
        MpvAutoControlPolicy.Decision decision = controller.evaluate(
                token, token, unknownMemoryRequest(true));

        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, false);

        assertEquals(MpvAutoController.State.FAILED, controller.snapshot().state());
        assertEquals(1, controller.snapshot().applyAttempts());
        assertEquals(-1, controller.snapshot().appliedForwardBytes());
        assertFalse(controller.snapshot().lastApplySucceeded());
    }

    @Test
    public void stagedBaselineIsDistinctFromNativeAppliedState() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken token = token("trace-staged", 8);
        controller.beginSession(token);
        MpvAutoControlPolicy.Decision decision = controller.evaluate(
                token, token, unknownMemoryRequest(true));

        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, true, true);

        assertEquals(MpvAutoController.State.STAGED, controller.snapshot().state());
        assertTrue(controller.snapshot().lastApplySucceeded());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES,
                controller.snapshot().appliedForwardBytes());
    }

    @Test
    public void rebuildingWithinSessionCanReapplySameBaseline() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken token = token("trace-rebuild", 5);
        controller.beginSession(token);
        MpvAutoControlPolicy.Decision first = controller.evaluate(
                token, token, unknownMemoryRequest(true));
        assertTrue(controller.beginApply(token, first));
        controller.completeApply(token, first, true);

        MpvAutoControlPolicy.Decision second = controller.evaluate(
                token, token, unknownMemoryRequest(true));
        assertTrue(controller.beginApply(token, second));
        controller.completeApply(token, second, true);

        assertEquals(2, controller.snapshot().evaluationCount());
        assertEquals(2, controller.snapshot().applyAttempts());
        assertEquals(MpvAutoController.State.APPLIED, controller.snapshot().state());
    }

    @Test
    public void newSessionAndEndSessionClearPriorState() {
        MpvAutoController controller = new MpvAutoController();
        PlaybackAutoContext.SessionToken first = token("trace-first", 6);
        PlaybackAutoContext.SessionToken second = token("trace-second", 7);
        controller.beginSession(first);
        MpvAutoControlPolicy.Decision decision = controller.evaluate(
                first, first, unknownMemoryRequest(true));
        assertTrue(controller.beginApply(first, decision));
        controller.completeApply(first, decision, true);

        controller.beginSession(second);
        assertEquals(MpvAutoController.State.IDLE, controller.snapshot().state());
        assertEquals(0, controller.snapshot().applyAttempts());
        assertFalse(controller.beginApply(first, decision));

        controller.endSession(second);
        assertFalse(controller.snapshot().session().active());
        assertEquals(MpvAutoController.State.IDLE, controller.snapshot().state());
    }

    private static PlaybackAutoContext.SessionToken token(String trace, long generation) {
        long seed = Math.abs((trace == null ? 0 : trace.hashCode()) * 31L + generation);
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(seed + 1, 36) + "-" + Long.toString(generation + 1, 36),
                generation);
    }

    private static MpvAutoControlPolicy.Request unknownMemoryRequest(boolean performancePriority) {
        return new MpvAutoControlPolicy.Request(
                true,
                true,
                performancePriority,
                false,
                PlaybackAutoContext.MemoryPressure.UNKNOWN,
                false,
                PlaybackAutoContext.MemorySnapshot.unknown(),
                true,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                true,
                PlaybackAutoContext.StreamKind.VOD,
                true,
                PlaybackAutoContext.PathKind.REMOTE,
                true,
                PlaybackAutoContext.PathKind.REMOTE,
                true,
                PlaybackAutoContext.UpstreamState.VISIBLE);
    }
}
