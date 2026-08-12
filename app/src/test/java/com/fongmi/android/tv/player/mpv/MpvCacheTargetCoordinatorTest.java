package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvCacheTargetCoordinatorTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void forwardUpdatePreservesApprovedBackTarget() {
        MpvCacheTargetCoordinator coordinator = coordinator("forward", 1, 64, 32);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();

        MpvCacheTargetCoordinator.Decision decision =
                coordinator.evaluate(token, 96 * MIB, 32 * MIB);
        apply(coordinator, token, decision);

        assertEquals(96 * MIB, coordinator.snapshot().nativeForwardBytes());
        assertEquals(32 * MIB, coordinator.snapshot().nativeBackBytes());
    }

    @Test
    public void backUpdatePreservesForwardTarget() {
        MpvCacheTargetCoordinator coordinator = coordinator("back", 2, 96, 0);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();

        MpvCacheTargetCoordinator.Decision decision =
                coordinator.evaluate(token, 96 * MIB, 32 * MIB);
        apply(coordinator, token, decision);

        assertEquals(96 * MIB, coordinator.snapshot().nativeForwardBytes());
        assertEquals(32 * MIB, coordinator.snapshot().nativeBackBytes());
    }

    @Test
    public void sameCombinedTargetDoesNotDuplicateNativeCommit() {
        MpvCacheTargetCoordinator coordinator = coordinator("stable", 3, 64, 16);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();

        MpvCacheTargetCoordinator.Decision decision =
                coordinator.evaluate(token, 64 * MIB, 16 * MIB);

        assertFalse(decision.requestsApply());
        assertEquals(0, coordinator.snapshot().applyAttempts());
    }

    @Test
    public void failedCombinedCommitLeavesBothNativeTargetsUntouchedAndRetries() {
        MpvCacheTargetCoordinator coordinator = coordinator("failure", 4, 64, 0);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();
        MpvCacheTargetCoordinator.Decision decision =
                coordinator.evaluate(token, 96 * MIB, 32 * MIB);
        assertTrue(coordinator.beginApply(token, decision));
        coordinator.completeApply(token, decision, false, false);

        assertEquals(64 * MIB, coordinator.snapshot().nativeForwardBytes());
        assertEquals(0, coordinator.snapshot().nativeBackBytes());

        MpvCacheTargetCoordinator.Decision retry =
                coordinator.evaluate(token, 96 * MIB, 32 * MIB);
        assertTrue(retry.requestsApply());
    }

    @Test
    public void pendingCommitRejectsDuplicateEvaluation() {
        MpvCacheTargetCoordinator coordinator = coordinator("pending", 5, 64, 0);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();
        MpvCacheTargetCoordinator.Decision first =
                coordinator.evaluate(token, 96 * MIB, 16 * MIB);
        assertTrue(coordinator.beginApply(token, first));

        MpvCacheTargetCoordinator.Decision duplicate =
                coordinator.evaluate(token, 96 * MIB, 16 * MIB);

        assertFalse(duplicate.requestsApply());
        assertEquals(MpvCacheTargetCoordinator.Reason.APPLY_PENDING, duplicate.reason());
    }

    @Test
    public void invalidBackGreaterThanForwardIsRejectedBeforeNativeCall() {
        MpvCacheTargetCoordinator coordinator = coordinator("invalid", 6, 24, 0);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();

        MpvCacheTargetCoordinator.Decision invalid =
                coordinator.evaluate(token, 24 * MIB, 32 * MIB);

        assertFalse(invalid.requestsApply());
        assertEquals(MpvCacheTargetCoordinator.Reason.INVALID_TARGET, invalid.reason());
    }

    @Test
    public void staleSessionCannotChangeSharedTargets() {
        MpvCacheTargetCoordinator coordinator = coordinator("current", 7, 64, 0);
        PlaybackAutoContext.SessionToken token = coordinator.snapshot().session();
        PlaybackAutoContext.SessionToken stale = token("stale", 1);

        MpvCacheTargetCoordinator.Decision decision =
                coordinator.evaluate(stale, 96 * MIB, 16 * MIB);

        assertEquals(MpvCacheTargetCoordinator.Reason.STALE_SESSION, decision.reason());
        assertEquals(token, coordinator.snapshot().session());
        assertEquals(64 * MIB, coordinator.snapshot().nativeForwardBytes());
    }

    private static MpvCacheTargetCoordinator coordinator(
            String trace,
            long sequence,
            long forwardMib,
            long backMib) {
        MpvCacheTargetCoordinator coordinator = new MpvCacheTargetCoordinator();
        PlaybackAutoContext.SessionToken token = token(trace, sequence);
        coordinator.beginSession(token);
        assertTrue(coordinator.recordBaseline(
                token, forwardMib * MIB, backMib * MIB));
        return coordinator;
    }

    private static void apply(
            MpvCacheTargetCoordinator coordinator,
            PlaybackAutoContext.SessionToken token,
            MpvCacheTargetCoordinator.Decision decision) {
        assertTrue(coordinator.beginApply(token, decision));
        coordinator.completeApply(token, decision, true, false);
    }

    private static PlaybackAutoContext.SessionToken token(String trace, long sequence) {
        long seed = Math.abs((trace == null ? 0 : trace.hashCode()) * 31L + sequence);
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(seed + 1, 36) + "-" + Long.toString(sequence + 1, 36),
                sequence);
    }
}
