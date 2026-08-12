package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvResourcePressureCacheIntegrationTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void hardResourcePressureProducesOneValidCombinedMinimumTarget() {
        PlaybackAutoContext.SessionToken token = token("hard", 1);
        MpvForwardCacheController forward = new MpvForwardCacheController();
        MpvBackCacheController back = enabledBack(token);
        MpvCacheTargetCoordinator combined = new MpvCacheTargetCoordinator();
        forward.beginSession(token);
        assertTrue(forward.recordBaseline(token, 96 * MIB, false));
        combined.beginSession(token);
        assertTrue(combined.recordBaseline(token, 96 * MIB, 16 * MIB));
        MpvResourcePressureController.Decision pressure = hardDecision();

        MpvForwardCacheController.Decision forwardDecision = forward.evaluate(
                token, token, forwardAssessment(192, 192, 10), pressure,
                MpvForwardCacheController.Trigger.RESOURCE, 10);
        MpvBackCacheController.Decision backDecision = back.evaluate(
                token, token, backAssessment(64, 10), pressure,
                MpvBackCacheController.Trigger.RESOURCE,
                MpvBackCachePolicy.SeekObservation.none(), 10);
        MpvCacheTargetCoordinator.Decision target = combined.evaluate(
                token, forwardDecision.targetBytes(), backDecision.targetBytes());

        assertEquals(24 * MIB, forwardDecision.targetBytes());
        assertEquals(MpvForwardCacheController.Reason.RESOURCE_HARD_PRESSURE,
                forwardDecision.reason());
        assertEquals(0, backDecision.targetBytes());
        assertEquals(MpvBackCacheController.Reason.RESOURCE_HARD_PRESSURE,
                backDecision.reason());
        assertTrue(target.requestsApply());
        assertEquals(24 * MIB, target.targetForwardBytes());
        assertEquals(0, target.targetBackBytes());
    }

    @Test
    public void constrainedResourceBlocksExpansionButAllowsDemandReduction() {
        PlaybackAutoContext.SessionToken token = token("freeze", 2);
        MpvForwardCacheController expanding = new MpvForwardCacheController();
        expanding.beginSession(token);
        assertTrue(expanding.recordBaseline(token, 64 * MIB, false));
        MpvResourcePressureController.Decision pressure = constrainedDecision(64, 0);

        MpvForwardCacheController.Decision expansion = null;
        for (long now : new long[]{0, 5_000, 10_000, 15_000, 20_000}) {
            expansion = expanding.evaluate(
                    token, token, forwardAssessment(192, 192, 0), pressure,
                    MpvForwardCacheController.Trigger.RUNTIME, now);
        }

        assertFalse(expansion.requestsApply());
        assertEquals(MpvForwardCacheController.Reason.RESOURCE_EXPANSION_HOLD,
                expansion.reason());

        MpvForwardCacheController reducing = new MpvForwardCacheController();
        reducing.beginSession(token);
        assertTrue(reducing.recordBaseline(token, 64 * MIB, false));
        reducing.evaluate(token, token, forwardAssessment(24, 192, 0), pressure,
                MpvForwardCacheController.Trigger.RUNTIME, 0);
        reducing.evaluate(token, token, forwardAssessment(24, 192, 0), pressure,
                MpvForwardCacheController.Trigger.RUNTIME, 5_000);
        MpvForwardCacheController.Decision reduction = reducing.evaluate(
                token, token, forwardAssessment(24, 192, 0), pressure,
                MpvForwardCacheController.Trigger.RUNTIME, 10_000);

        assertTrue(reduction.requestsApply());
        assertEquals(48 * MIB, reduction.targetBytes());
        assertEquals(MpvForwardCacheController.Reason.DEMAND_DECREASE_STEP,
                reduction.reason());
    }

    @Test
    public void constrainedResourceKeepsBackSeekEvidenceWithoutAllocating() {
        PlaybackAutoContext.SessionToken token = token("back", 3);
        MpvBackCacheController back = new MpvBackCacheController();
        back.beginSession(token);
        assertTrue(back.recordBaseline(token, 0, false));
        MpvResourcePressureController.Decision pressure = constrainedDecision(64, 0);

        back.evaluate(token, token, backAssessment(64, 0), pressure,
                MpvBackCacheController.Trigger.SEEK, backward(), 0);
        MpvBackCacheController.Decision second = back.evaluate(
                token, token, backAssessment(64, 1_000), pressure,
                MpvBackCacheController.Trigger.SEEK, backward(), 1_000);

        assertFalse(second.requestsApply());
        assertEquals(MpvBackCacheController.Reason.RESOURCE_EXPANSION_HOLD,
                second.reason());
        assertEquals(2, back.snapshot().backwardSeekEvidence());
        assertEquals(16 * MIB, back.snapshot().learnedTargetBytes());
        assertEquals(0, back.snapshot().controlledTargetBytes());
    }

    @Test
    public void rebuildUnderConstraintAdoptsLowerNativeBaselineInsteadOfExpanding() {
        PlaybackAutoContext.SessionToken token = token("rebuild", 4);
        MpvForwardCacheController forward = new MpvForwardCacheController();
        forward.beginSession(token);
        assertTrue(forward.recordBaseline(token, 96 * MIB, false));
        assertTrue(forward.recordBaseline(token, 24 * MIB, true));

        MpvForwardCacheController.Decision decision = forward.evaluate(
                token, token, forwardAssessment(192, 192, 10),
                constrainedDecision(96, 0),
                MpvForwardCacheController.Trigger.REBUILD, 10);

        assertFalse(decision.requestsApply());
        assertEquals(MpvForwardCacheController.Reason.RESOURCE_CONTEXT_HOLD,
                decision.reason());
        assertEquals(24 * MIB, forward.snapshot().controlledTargetBytes());
        assertTrue(forward.snapshot().resourceRecoveryPending());
    }

    @Test
    public void resourceRecoveryRestoresUnknownMediaOnlyToInitialBaseline() {
        PlaybackAutoContext.SessionToken token = token("restore", 5);
        MpvForwardCacheController forward = new MpvForwardCacheController();
        forward.beginSession(token);
        assertTrue(forward.recordBaseline(token, 64 * MIB, false));
        MpvForwardCacheController.Decision shrink = forward.evaluate(
                token, token, unknownMedia(192, 0), hardDecision(),
                MpvForwardCacheController.Trigger.RESOURCE, 0);
        apply(forward, token, shrink, 0);

        MpvForwardCacheController.Decision recover = forward.evaluate(
                token, token, unknownMedia(192, 60_000),
                recoveringDecision(48, 16),
                MpvForwardCacheController.Trigger.RESOURCE, 60_000);

        assertTrue(recover.requestsApply());
        assertEquals(48 * MIB, recover.targetBytes());
        assertEquals(MpvForwardCacheController.Reason.RESOURCE_RECOVERY_STEP,
                recover.reason());
    }

    private static MpvBackCacheController enabledBack(
            PlaybackAutoContext.SessionToken token) {
        MpvBackCacheController back = new MpvBackCacheController();
        back.beginSession(token);
        assertTrue(back.recordBaseline(token, 0, false));
        back.evaluate(token, token, backAssessment(64, 0),
                MpvBackCacheController.Trigger.SEEK, backward(), 0);
        MpvBackCacheController.Decision enable = back.evaluate(
                token, token, backAssessment(64, 1_000),
                MpvBackCacheController.Trigger.SEEK, backward(), 1_000);
        assertTrue(back.beginApply(token, enable));
        back.completeApply(token, enable, true, false, 1_000);
        return back;
    }

    private static void apply(
            MpvForwardCacheController controller,
            PlaybackAutoContext.SessionToken token,
            MpvForwardCacheController.Decision decision,
            long now) {
        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, true, false, now);
    }

    private static MpvResourcePressureController.Decision hardDecision() {
        return resourceDecision(
                MpvResourcePressurePolicy.Level.HARD,
                MpvResourcePressureController.RecoveryClass.HARD,
                24, 0, false, false);
    }

    private static MpvResourcePressureController.Decision constrainedDecision(
            long forwardMib,
            long backMib) {
        return resourceDecision(
                MpvResourcePressurePolicy.Level.CONSTRAINED,
                MpvResourcePressureController.RecoveryClass.CONSTRAINED,
                forwardMib, backMib, false, false);
    }

    private static MpvResourcePressureController.Decision recoveringDecision(
            long forwardMib,
            long backMib) {
        return resourceDecision(
                MpvResourcePressurePolicy.Level.NORMAL,
                MpvResourcePressureController.RecoveryClass.HARD,
                forwardMib, backMib, true, true);
    }

    private static MpvResourcePressureController.Decision resourceDecision(
            MpvResourcePressurePolicy.Level level,
            MpvResourcePressureController.RecoveryClass recoveryClass,
            long forwardMib,
            long backMib,
            boolean expansionAllowed,
            boolean preloadAllowed) {
        return new MpvResourcePressureController.Decision(
                MpvResourcePressureController.Action.HELD,
                MpvResourcePressureController.Trigger.SYSTEM,
                level == MpvResourcePressurePolicy.Level.HARD
                        ? MpvResourcePressureController.Reason.HARD_PRESSURE
                        : MpvResourcePressureController.Reason.CONSTRAINED_HOLD,
                level == MpvResourcePressurePolicy.Level.HARD
                        ? MpvResourcePressurePolicy.Reason.THERMAL_SEVERE
                        : MpvResourcePressurePolicy.Reason.METERED,
                level,
                recoveryClass,
                forwardMib * MIB,
                backMib * MIB,
                expansionAllowed,
                preloadAllowed,
                false,
                false,
                0,
                0,
                1);
    }

    private static MpvForwardCachePolicy.Assessment forwardAssessment(
            long mediaTargetMib,
            long safeTargetMib,
            long memorySampleAt) {
        MpvForwardCachePolicy.BitrateEvidence average =
                new MpvForwardCachePolicy.BitrateEvidence(
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
                MpvForwardCachePolicy.MAX_FORWARD_BYTES,
                safeTargetMib * MIB,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true,
                true,
                memorySampleAt,
                false,
                MpvForwardCachePolicy.Reason.MEDIA_DEMAND);
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
                false,
                -1,
                safeTargetMib * MIB,
                MpvForwardCachePolicy.MAX_FORWARD_BYTES,
                safeTargetMib * MIB,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true,
                true,
                memorySampleAt,
                false,
                MpvForwardCachePolicy.Reason.MAX_CAPACITY);
    }

    private static MpvBackCachePolicy.Assessment backAssessment(
            long safeBackMib,
            long memorySampleAt) {
        return new MpvBackCachePolicy.Assessment(
                true,
                true,
                MpvBackCachePolicy.Reason.SAFE_BUDGET,
                safeBackMib * MIB,
                96 * MIB,
                64 * MIB,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true,
                true,
                memorySampleAt,
                false,
                true);
    }

    private static MpvBackCachePolicy.SeekObservation backward() {
        return MpvBackCachePolicy.observeSeek(true, true, 20_000, 10_000);
    }

    private static PlaybackAutoContext.SessionToken token(
            String trace,
            long generation) {
        long seed = Math.abs((trace == null ? 0 : trace.hashCode()) * 31L + generation);
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(seed + 1, 36)
                        + "-" + Long.toString(generation + 1, 36),
                generation);
    }
}
