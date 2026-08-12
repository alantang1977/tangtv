package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvBackCacheControllerTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void firstBackwardSeekOnlyRecordsEvidenceAndSecondEnablesSixteenMiB() {
        MpvBackCacheController controller = controller("seek", 1);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        MpvBackCacheController.Decision first = controller.evaluate(
                token, token, normal(64, 0), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 0);
        assertFalse(first.requestsApply());
        assertEquals(MpvBackCacheController.Reason.FIRST_BACKWARD_SEEK, first.reason());
        assertEquals(1, controller.snapshot().backwardSeekEvidence());
        assertEquals(0, controller.snapshot().learnedTargetBytes());

        MpvBackCacheController.Decision second = controller.evaluate(
                token, token, normal(64, 1_000), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 1_000);
        assertTrue(second.requestsApply());
        assertEquals(16 * MIB, second.targetBytes());
        apply(controller, token, second, 1_000);
    }

    @Test
    public void largeRepeatedSeekAcceleratesLearningButStillExpandsOneTierAtATime() {
        MpvBackCacheController controller = controller("large", 2);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.evaluate(token, token, normal(64, 0),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 0);

        MpvBackCacheController.Decision large = controller.evaluate(
                token, token, normal(64, 1_000), MpvBackCacheController.Trigger.SEEK,
                largeBackward(), 1_000);

        assertEquals(3, controller.snapshot().backwardSeekEvidence());
        assertEquals(32 * MIB, controller.snapshot().learnedTargetBytes());
        assertEquals(16 * MIB, large.targetBytes());
    }

    @Test
    public void expansionUsesThirtySecondStepCooldownThroughThirtyTwoAndSixtyFour() {
        MpvBackCacheController controller = controller("tiers", 3);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.evaluate(token, token, normal(64, 0),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 0);
        MpvBackCacheController.Decision sixteen = controller.evaluate(
                token, token, normal(64, 1_000), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 1_000);
        apply(controller, token, sixteen, 1_000);

        MpvBackCacheController.Decision earlyThirtyTwo = controller.evaluate(
                token, token, normal(64, 2_000), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 2_000);
        assertFalse(earlyThirtyTwo.requestsApply());
        assertEquals(MpvBackCacheController.Reason.EXPANSION_COOLDOWN,
                earlyThirtyTwo.reason());

        MpvBackCacheController.Decision thirtyTwo = controller.evaluate(
                token, token, normal(64, 31_000), MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(), 31_000);
        assertEquals(32 * MIB, thirtyTwo.targetBytes());
        apply(controller, token, thirtyTwo, 31_000);

        controller.evaluate(token, token, normal(64, 32_000),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 32_000);
        MpvBackCacheController.Decision sixtyFour = controller.evaluate(
                token, token, normal(64, 61_000), MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(), 61_000);
        assertEquals(64 * MIB, sixtyFour.targetBytes());
    }

    @Test
    public void forwardSeekSmallJitterAndItemChangeNeverCount() {
        MpvBackCacheController controller = controller("ignore", 4);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        for (MpvBackCachePolicy.SeekObservation seek : new MpvBackCachePolicy.SeekObservation[]{
                MpvBackCachePolicy.observeSeek(true, true, 10_000, 20_000),
                MpvBackCachePolicy.observeSeek(true, true, 20_000, 18_000),
                MpvBackCachePolicy.observeSeek(true, false, 20_000, 10_000),
                MpvBackCachePolicy.observeSeek(false, true, 20_000, 10_000)}) {
            controller.evaluate(token, token, normal(64, 0),
                    MpvBackCacheController.Trigger.SEEK, seek, 0);
        }

        assertEquals(0, controller.snapshot().backwardSeekEvidence());
        assertEquals(0, controller.snapshot().controlledTargetBytes());
    }

    @Test
    public void moderateAndCriticalPressureImmediatelyResetBackToZero() {
        MpvBackCacheController moderate = enabled("moderate", 5);
        PlaybackAutoContext.SessionToken moderateToken = moderate.snapshot().session();
        MpvBackCacheController.Decision moderateReset = moderate.evaluate(
                moderateToken, moderateToken,
                reset(MpvBackCachePolicy.Reason.MODERATE_PRESSURE,
                        PlaybackAutoContext.MemoryPressure.MODERATE, 2_000),
                MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(), 2_000);
        assertEquals(0, moderateReset.targetBytes());
        assertEquals(MpvBackCacheController.RecoveryClass.MODERATE,
                moderate.snapshot().recoveryClass());

        MpvBackCacheController critical = enabled("critical", 6);
        PlaybackAutoContext.SessionToken criticalToken = critical.snapshot().session();
        MpvBackCacheController.Decision criticalReset = critical.evaluate(
                criticalToken, criticalToken,
                reset(MpvBackCachePolicy.Reason.CRITICAL_PRESSURE,
                        PlaybackAutoContext.MemoryPressure.CRITICAL, 2_000),
                MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(), 2_000);
        assertEquals(0, criticalReset.targetBytes());
        assertEquals(MpvBackCacheController.RecoveryClass.CRITICAL,
                critical.snapshot().recoveryClass());
    }

    @Test
    public void criticalRecoveryNeedsTwoFreshNormalSamplesAndSixtySeconds() {
        MpvBackCacheController controller = enabled("recover", 7);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        MpvBackCacheController.Decision reset = controller.evaluate(
                token, token,
                reset(MpvBackCachePolicy.Reason.CRITICAL_PRESSURE,
                        PlaybackAutoContext.MemoryPressure.CRITICAL, 2_000),
                MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(), 2_000);
        apply(controller, token, reset, 2_000);

        MpvBackCacheController.Decision first = controller.evaluate(
                token, token, normal(64, 30_000), MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(), 30_000);
        assertFalse(first.requestsApply());
        assertEquals(1, controller.snapshot().normalSamples());

        MpvBackCacheController.Decision recovered = controller.evaluate(
                token, token, normal(64, 62_000), MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(), 62_000);
        assertTrue(recovered.requestsApply());
        assertEquals(16 * MIB, recovered.targetBytes());
    }

    @Test
    public void capacityDropShrinksImmediatelyAndPreservesLearnedDemand() {
        MpvBackCacheController controller = enabledThirtyTwo("capacity", 8);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        MpvBackCacheController.Decision decision = controller.evaluate(
                token, token, normal(16, 40_000), MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(), 40_000);

        assertEquals(16 * MIB, decision.targetBytes());
        assertEquals(32 * MIB, controller.snapshot().learnedTargetBytes());
        assertEquals(MpvBackCacheController.RecoveryClass.CAPACITY,
                controller.snapshot().recoveryClass());
    }

    @Test
    public void tenMinutesWithoutBackwardSeekClearsLearnedBehaviorAndBackTarget() {
        MpvBackCacheController controller = enabled("idle", 81);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        MpvBackCacheController.Decision expired = controller.evaluate(
                token, token, normal(64, 601_000),
                MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(), 601_000);

        assertTrue(expired.requestsApply());
        assertEquals(MpvBackCacheController.Reason.BEHAVIOR_EXPIRED, expired.reason());
        assertEquals(0, expired.targetBytes());
        assertEquals(0, controller.snapshot().backwardSeekEvidence());
        assertEquals(0, controller.snapshot().learnedTargetBytes());
    }

    @Test
    public void backwardSeekAfterIdleWindowStartsFreshEvidenceWindow() {
        MpvBackCacheController controller = controller("fresh-window", 82);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.evaluate(token, token, normal(64, 0),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 0);

        MpvBackCacheController.Decision fresh = controller.evaluate(
                token, token, normal(64, 600_001),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 600_001);

        assertFalse(fresh.requestsApply());
        assertEquals(1, controller.snapshot().backwardSeekEvidence());
        assertEquals(0, controller.snapshot().learnedTargetBytes());
    }

    @Test
    public void liveTransitionClearsBehaviorAndBackTarget() {
        MpvBackCacheController controller = enabled("live", 9);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        MpvBackCacheController.Decision decision = controller.evaluate(
                token, token, disabled(MpvBackCachePolicy.Reason.LIVE_RESOURCE),
                MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(), 2_000);

        assertEquals(0, decision.targetBytes());
        assertEquals(0, controller.snapshot().backwardSeekEvidence());
        assertEquals(0, controller.snapshot().learnedTargetBytes());
    }

    @Test
    public void sameSessionRebuildRestoresControlledBackTarget() {
        MpvBackCacheController controller = enabled("rebuild", 10);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        assertTrue(controller.recordBaseline(token, 0, true));
        assertEquals(0, controller.snapshot().nativeTargetBytes());
        assertEquals(16 * MIB, controller.snapshot().controlledTargetBytes());

        MpvBackCacheController.Decision restore = controller.evaluate(
                token, token, normal(64, 10_000), MpvBackCacheController.Trigger.REBUILD,
                MpvBackCachePolicy.SeekObservation.none(), 10_000);

        assertEquals(MpvBackCacheController.Reason.CONTEXT_RESTORE, restore.reason());
        assertEquals(16 * MIB, restore.targetBytes());
    }

    @Test
    public void failedApplyRetriesWithoutLeakingControlledTarget() {
        MpvBackCacheController controller = controller("retry", 11);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.evaluate(token, token, normal(64, 0),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 0);
        MpvBackCacheController.Decision decision = controller.evaluate(
                token, token, normal(64, 1_000), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 1_000);
        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, false, false, 1_000);
        assertEquals(0, controller.snapshot().controlledTargetBytes());

        MpvBackCacheController.Decision retry = controller.evaluate(
                token, token, normal(64, 2_000), MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(), 2_000);
        assertTrue(retry.requestsApply());
        assertEquals(16 * MIB, retry.targetBytes());
    }

    @Test
    public void staleSessionCannotMutateEvidenceOrTarget() {
        MpvBackCacheController controller = controller("current", 12);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        PlaybackAutoContext.SessionToken stale = token("stale", 1);

        MpvBackCacheController.Decision decision = controller.evaluate(
                token, stale, normal(64, 0), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 0);

        assertEquals(MpvBackCacheController.Reason.STALE_SESSION, decision.reason());
        assertEquals(0, controller.snapshot().backwardSeekEvidence());
        assertEquals(0, controller.snapshot().evaluations());
    }

    private static MpvBackCacheController controller(String trace, long sequence) {
        MpvBackCacheController controller = new MpvBackCacheController();
        PlaybackAutoContext.SessionToken token = token(trace, sequence);
        controller.beginSession(token);
        controller.recordBaseline(token, 0, false);
        return controller;
    }

    private static MpvBackCacheController enabled(String trace, long sequence) {
        MpvBackCacheController controller = controller(trace, sequence);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.evaluate(token, token, normal(64, 0),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 0);
        MpvBackCacheController.Decision sixteen = controller.evaluate(
                token, token, normal(64, 1_000), MpvBackCacheController.Trigger.SEEK,
                backward(10_000), 1_000);
        apply(controller, token, sixteen, 1_000);
        return controller;
    }

    private static MpvBackCacheController enabledThirtyTwo(String trace, long sequence) {
        MpvBackCacheController controller = enabled(trace, sequence);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        controller.evaluate(token, token, normal(64, 2_000),
                MpvBackCacheController.Trigger.SEEK, backward(10_000), 2_000);
        MpvBackCacheController.Decision thirtyTwo = controller.evaluate(
                token, token, normal(64, 31_000), MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(), 31_000);
        apply(controller, token, thirtyTwo, 31_000);
        return controller;
    }

    private static void apply(
            MpvBackCacheController controller,
            PlaybackAutoContext.SessionToken token,
            MpvBackCacheController.Decision decision,
            long now) {
        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, true, false, now);
    }

    private static MpvBackCachePolicy.SeekObservation backward(long distanceMs) {
        return MpvBackCachePolicy.observeSeek(
                true, true, distanceMs + 10_000, 10_000);
    }

    private static MpvBackCachePolicy.SeekObservation largeBackward() {
        return MpvBackCachePolicy.observeSeek(true, true, 90_000, 10_000);
    }

    private static MpvBackCachePolicy.Assessment normal(long safeMib, long sampledAt) {
        return new MpvBackCachePolicy.Assessment(
                true,
                true,
                safeMib == 0
                        ? MpvBackCachePolicy.Reason.NO_TOTAL_HEADROOM
                        : MpvBackCachePolicy.Reason.SAFE_BUDGET,
                safeMib * MIB,
                128 * MIB,
                64 * MIB,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true,
                true,
                sampledAt,
                safeMib == 0,
                true);
    }

    private static MpvBackCachePolicy.Assessment reset(
            MpvBackCachePolicy.Reason reason,
            PlaybackAutoContext.MemoryPressure pressure,
            long sampledAt) {
        return new MpvBackCachePolicy.Assessment(
                true, true, reason, 0,
                64 * MIB, 64 * MIB,
                pressure, true, true, sampledAt,
                true, false);
    }

    private static MpvBackCachePolicy.Assessment disabled(MpvBackCachePolicy.Reason reason) {
        return new MpvBackCachePolicy.Assessment(
                true, false, reason, 0,
                64 * MIB, 64 * MIB,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true, true, 2_000,
                true, false);
    }

    private static PlaybackAutoContext.SessionToken token(String trace, long sequence) {
        long seed = Math.abs((trace == null ? 0 : trace.hashCode()) * 31L + sequence);
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(seed + 1, 36) + "-" + Long.toString(sequence + 1, 36),
                sequence);
    }
}
