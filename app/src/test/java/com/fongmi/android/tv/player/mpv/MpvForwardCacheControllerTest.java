package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvForwardCacheControllerTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void baselineInitializesControlledAndNativeTargets() {
        MpvForwardCacheController controller = controller("baseline", 1);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        assertTrue(controller.recordBaseline(token, 64 * MIB, false));

        MpvForwardCacheController.Snapshot snapshot = controller.snapshot();
        assertTrue(snapshot.baselineInitialized());
        assertEquals(64 * MIB, snapshot.initialBaselineBytes());
        assertEquals(64 * MIB, snapshot.controlledTargetBytes());
        assertEquals(64 * MIB, snapshot.nativeTargetBytes());
        assertEquals(MpvForwardCacheController.State.ACTIVE, snapshot.state());
    }

    @Test
    public void staleFactsSessionCannotMutateController() {
        MpvForwardCacheController controller = controller("current", 2);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 64 * MIB, false);
        PlaybackAutoContext.SessionToken stale = token("stale", 1);

        MpvForwardCacheController.Decision decision = controller.evaluate(
                token, stale, normal(192, 192, 10),
                MpvForwardCacheController.Trigger.RUNTIME, 10);

        assertEquals(MpvForwardCacheController.Reason.STALE_SESSION, decision.reason());
        assertEquals(0, controller.snapshot().evaluations());
        assertEquals(64 * MIB, controller.snapshot().controlledTargetBytes());
    }

    @Test
    public void expansionRequiresStableDemandAndMovesOneTierPerInterval() {
        MpvForwardCacheController controller = controller("expand", 3);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 64 * MIB, false);

        for (long now : new long[]{0, 5_000, 10_000, 15_000}) {
            MpvForwardCacheController.Decision hold = controller.evaluate(
                    token, token, normal(192, 192, 0),
                    MpvForwardCacheController.Trigger.RUNTIME, now);
            assertFalse(hold.requestsApply());
        }
        MpvForwardCacheController.Decision first = controller.evaluate(
                token, token, normal(192, 192, 0),
                MpvForwardCacheController.Trigger.RUNTIME, 20_000);
        assertEquals(MpvForwardCacheController.Reason.DEMAND_INCREASE_STEP, first.reason());
        assertEquals(96 * MIB, first.targetBytes());
        apply(controller, token, first, 20_000);

        assertFalse(controller.evaluate(token, token, normal(192, 192, 30_000),
                MpvForwardCacheController.Trigger.RUNTIME, 30_000).requestsApply());
        MpvForwardCacheController.Decision second = controller.evaluate(
                token, token, normal(192, 192, 30_000),
                MpvForwardCacheController.Trigger.RUNTIME, 35_000);
        assertEquals(128 * MIB, second.targetBytes());
        apply(controller, token, second, 35_000);

        assertEquals(128 * MIB, controller.snapshot().controlledTargetBytes());
        assertEquals(2, controller.snapshot().applyAttempts());
    }

    @Test
    public void demandDecreaseNeedsConfirmationAndDropsOnlyOneTier() {
        MpvForwardCacheController controller = controller("decrease", 4);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 64 * MIB, false);

        assertFalse(controller.evaluate(token, token, normal(24, 192, 0),
                MpvForwardCacheController.Trigger.RUNTIME, 0).requestsApply());
        assertFalse(controller.evaluate(token, token, normal(24, 192, 0),
                MpvForwardCacheController.Trigger.RUNTIME, 5_000).requestsApply());
        MpvForwardCacheController.Decision decision = controller.evaluate(
                token, token, normal(24, 192, 0),
                MpvForwardCacheController.Trigger.RUNTIME, 10_000);

        assertEquals(MpvForwardCacheController.Reason.DEMAND_DECREASE_STEP,
                decision.reason());
        assertEquals(48 * MIB, decision.targetBytes());
        apply(controller, token, decision, 10_000);
        assertEquals(48 * MIB, controller.snapshot().controlledTargetBytes());
    }

    @Test
    public void criticalPressureImmediatelyRequestsMinimum() {
        MpvForwardCacheController controller = controller("critical", 5);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 96 * MIB, false);

        MpvForwardCacheController.Decision decision = controller.evaluate(
                token, token, critical(0),
                MpvForwardCacheController.Trigger.MEMORY, 0);

        assertTrue(decision.requestsApply());
        assertEquals(24 * MIB, decision.targetBytes());
        assertEquals(MpvForwardCacheController.RecoveryClass.CRITICAL,
                controller.snapshot().recoveryClass());
        apply(controller, token, decision, 0);
    }

    @Test
    public void moderatePressureDropsOneTierWithoutRepeatedRatchet() {
        MpvForwardCacheController controller = controller("moderate", 6);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 96 * MIB, false);

        MpvForwardCacheController.Decision first = controller.evaluate(
                token, token, moderate(10),
                MpvForwardCacheController.Trigger.MEMORY, 10);
        assertEquals(64 * MIB, first.targetBytes());
        apply(controller, token, first, 10);

        MpvForwardCacheController.Decision duplicate = controller.evaluate(
                token, token, moderate(10),
                MpvForwardCacheController.Trigger.RUNTIME, 5_000);
        MpvForwardCacheController.Decision nextSample = controller.evaluate(
                token, token, moderate(30_010),
                MpvForwardCacheController.Trigger.MEMORY, 30_010);

        assertFalse(duplicate.requestsApply());
        assertFalse(nextSample.requestsApply());
        assertEquals(64 * MIB, controller.snapshot().controlledTargetBytes());
    }

    @Test
    public void lowerSafeCapacityTightensImmediately() {
        MpvForwardCacheController controller = controller("capacity", 7);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 96 * MIB, false);

        MpvForwardCacheController.Decision decision = controller.evaluate(
                token, token, normal(192, 48, 10),
                MpvForwardCacheController.Trigger.MEMORY, 10);

        assertEquals(MpvForwardCacheController.Reason.CAPACITY_REDUCTION,
                decision.reason());
        assertEquals(48 * MIB, decision.targetBytes());
        assertEquals(MpvForwardCacheController.RecoveryClass.CAPACITY,
                controller.snapshot().recoveryClass());
    }

    @Test
    public void furtherCapacityDropRefreshesRecoveryCooldownOnce() {
        MpvForwardCacheController controller = controller("capacity-step", 70);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 96 * MIB, false);
        MpvForwardCacheController.Decision first = controller.evaluate(
                token, token, normal(192, 64, 10),
                MpvForwardCacheController.Trigger.MEMORY, 10);
        apply(controller, token, first, 10);

        MpvForwardCacheController.Decision second = controller.evaluate(
                token, token, normal(192, 48, 20),
                MpvForwardCacheController.Trigger.MEMORY, 20);

        assertEquals(48 * MIB, second.targetBytes());
        assertEquals(20, controller.snapshot().lastPressureAtElapsedMs());
    }

    @Test
    public void criticalRecoveryNeedsTwoFreshNormalSamplesAndSixtySeconds() {
        MpvForwardCacheController controller = controller("recover", 8);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 96 * MIB, false);
        MpvForwardCacheController.Decision critical = controller.evaluate(
                token, token, critical(0),
                MpvForwardCacheController.Trigger.MEMORY, 0);
        apply(controller, token, critical, 0);

        MpvForwardCacheController.Decision firstNormal = controller.evaluate(
                token, token, normal(96, 192, 30_000),
                MpvForwardCacheController.Trigger.MEMORY, 30_000);
        assertFalse(firstNormal.requestsApply());
        assertEquals(1, controller.snapshot().normalSamples());

        MpvForwardCacheController.Decision secondNormal = controller.evaluate(
                token, token, normal(96, 192, 60_000),
                MpvForwardCacheController.Trigger.MEMORY, 60_000);
        assertEquals(MpvForwardCacheController.Reason.RECOVERY_STEP,
                secondNormal.reason());
        assertEquals(48 * MIB, secondNormal.targetBytes());
        apply(controller, token, secondNormal, 60_000);

        MpvForwardCacheController.Decision nextTier = controller.evaluate(
                token, token, normal(96, 192, 60_000),
                MpvForwardCacheController.Trigger.RUNTIME, 75_000);
        assertEquals(64 * MIB, nextTier.targetBytes());
    }

    @Test
    public void lowDemandDoesNotClearCriticalCooldownEarly() {
        MpvForwardCacheController controller = controller("critical-low-demand", 80);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 64 * MIB, false);
        MpvForwardCacheController.Decision critical = controller.evaluate(
                token, token, critical(0),
                MpvForwardCacheController.Trigger.MEMORY, 0);
        apply(controller, token, critical, 0);

        MpvForwardCacheController.Decision lowDemand = controller.evaluate(
                token, token, normal(24, 192, 30_000),
                MpvForwardCacheController.Trigger.MEMORY, 30_000);

        assertFalse(lowDemand.requestsApply());
        assertEquals(MpvForwardCacheController.RecoveryClass.CRITICAL,
                controller.snapshot().recoveryClass());
        assertEquals(30_000, lowDemand.cooldownRemainingMs());
    }

    @Test
    public void unknownMediaNeverExpandsAboveInitialBaseline() {
        MpvForwardCacheController controller = controller("unknown", 9);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 64 * MIB, false);

        for (long now : new long[]{0, 5_000, 10_000, 20_000, 40_000}) {
            MpvForwardCacheController.Decision decision = controller.evaluate(
                    token, token, unknownMedia(192, now),
                    MpvForwardCacheController.Trigger.RUNTIME, now);
            assertFalse(decision.requestsApply());
        }
        assertEquals(64 * MIB, controller.snapshot().controlledTargetBytes());
    }

    @Test
    public void rebuildRestagesPreservedRuntimeTargetAfterInitialBaseline() {
        MpvForwardCacheController controller = controller("rebuild", 10);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 96 * MIB, false);
        controller.recordBaseline(token, 48 * MIB, true);

        MpvForwardCacheController.Decision decision = controller.evaluate(
                token, token, normal(192, 192, 10),
                MpvForwardCacheController.Trigger.REBUILD, 10);

        assertEquals(MpvForwardCacheController.Reason.CONTEXT_RESTORE,
                decision.reason());
        assertEquals(96 * MIB, decision.targetBytes());
        apply(controller, token, decision, 10);
        assertEquals(96 * MIB, controller.snapshot().nativeTargetBytes());
    }

    @Test
    public void failedNativeApplyCanRetryWithoutLosingReservationState() {
        MpvForwardCacheController controller = controller("retry", 11);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.recordBaseline(token, 64 * MIB, false);
        MpvForwardCacheController.Decision decision = null;
        for (long now : new long[]{0, 5_000, 10_000, 15_000, 20_000}) {
            decision = controller.evaluate(token, token, normal(192, 192, 0),
                    MpvForwardCacheController.Trigger.RUNTIME, now);
        }
        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, false, false, 20_000);

        MpvForwardCacheController.Decision retry = controller.evaluate(
                token, token, normal(192, 192, 30_000),
                MpvForwardCacheController.Trigger.RUNTIME, 30_000);

        assertTrue(retry.requestsApply());
        assertEquals(96 * MIB, retry.targetBytes());
        assertTrue(controller.beginApply(token, retry));
        assertEquals(2, controller.snapshot().applyAttempts());
    }

    @Test
    public void newAndEndedSessionsClearPriorRuntimeState() {
        MpvForwardCacheController controller = controller("first", 12);
        PlaybackAutoContext.SessionToken first = controller.snapshot().session();
        controller.recordBaseline(first, 64 * MIB, false);
        PlaybackAutoContext.SessionToken second = token("second", 13);

        controller.beginSession(second);
        assertFalse(controller.snapshot().baselineInitialized());
        assertEquals(0, controller.snapshot().applyAttempts());
        assertFalse(controller.recordBaseline(first, 64 * MIB, false));

        controller.endSession(second);
        assertFalse(controller.snapshot().session().active());
        assertEquals(MpvForwardCacheController.State.IDLE, controller.snapshot().state());
    }

    private static MpvForwardCacheController controller(String trace, long generation) {
        MpvForwardCacheController controller = new MpvForwardCacheController();
        controller.beginSession(token(trace, generation));
        return controller;
    }

    private static void apply(
            MpvForwardCacheController controller,
            PlaybackAutoContext.SessionToken token,
            MpvForwardCacheController.Decision decision,
            long now) {
        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, true, false, now);
    }

    private static MpvForwardCachePolicy.Assessment normal(
            long mediaTargetMib,
            long safeTargetMib,
            long memorySampleAt) {
        return assessment(mediaTargetMib, safeTargetMib,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true, true, memorySampleAt, false);
    }

    private static MpvForwardCachePolicy.Assessment moderate(long memorySampleAt) {
        return assessment(192, 192,
                PlaybackAutoContext.MemoryPressure.MODERATE,
                true, true, memorySampleAt, false);
    }

    private static MpvForwardCachePolicy.Assessment critical(long memorySampleAt) {
        return assessment(192, 24,
                PlaybackAutoContext.MemoryPressure.CRITICAL,
                true, true, memorySampleAt, true);
    }

    private static MpvForwardCachePolicy.Assessment unknownMedia(
            long safeTargetMib,
            long memorySampleAt) {
        return new MpvForwardCachePolicy.Assessment(
                true,
                MpvForwardCachePolicy.Reason.ACTIVE,
                64 * MIB,
                MpvForwardCachePolicy.BitrateEvidence.unknown(),
                MpvForwardCachePolicy.BitrateEvidence.unknown(),
                0, 0, 0,
                false, -1,
                safeTargetMib * MIB,
                192 * MIB,
                safeTargetMib * MIB,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true, true, memorySampleAt, false,
                MpvForwardCachePolicy.Reason.MAX_CAPACITY);
    }

    private static MpvForwardCachePolicy.Assessment assessment(
            long mediaTargetMib,
            long safeTargetMib,
            PlaybackAutoContext.MemoryPressure pressure,
            boolean pressureUsable,
            boolean snapshotUsable,
            long memorySampleAt,
            boolean critical) {
        MpvForwardCachePolicy.BitrateEvidence average = new MpvForwardCachePolicy.BitrateEvidence(
                20_000_000L,
                MpvForwardCachePolicy.Source.TRACK_FORMAT,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        return new MpvForwardCachePolicy.Assessment(
                true,
                MpvForwardCachePolicy.Reason.ACTIVE,
                64 * MIB,
                average,
                MpvForwardCachePolicy.BitrateEvidence.unknown(),
                1, 0, 1,
                true,
                mediaTargetMib * MIB,
                safeTargetMib * MIB,
                192 * MIB,
                safeTargetMib * MIB,
                pressure,
                pressureUsable,
                snapshotUsable,
                memorySampleAt,
                critical,
                MpvForwardCachePolicy.Reason.MEDIA_DEMAND);
    }

    private static PlaybackAutoContext.SessionToken token(String trace, long generation) {
        long seed = Math.abs((trace == null ? 0 : trace.hashCode()) * 31L + generation);
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(seed + 1, 36) + "-" + Long.toString(generation + 1, 36),
                generation);
    }
}
