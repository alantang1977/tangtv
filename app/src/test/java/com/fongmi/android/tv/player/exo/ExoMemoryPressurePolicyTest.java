package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoMemoryPressurePolicyTest {

    @Test
    public void criticalPressureImmediatelyUsesMinimumTierAndStopsOptionalWork() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(96),
                mib(96),
                observation(PlaybackAutoContext.MemoryPressure.CRITICAL, true, true, 1_000));

        assertEquals(mib(16), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.CRITICAL, decision.mode());
        assertEquals(ExoMemoryPressurePolicy.RecoveryClass.CRITICAL, decision.recoveryClass());
        assertTrue(decision.preloadPaused());
        assertTrue(decision.backBufferSuppressed());
        assertFalse(decision.expansionAllowed());
    }

    @Test
    public void moderatePressureDropsOneTierWithoutRepeatedRatchet() {
        ExoMemoryPressurePolicy.Decision first = resolve(
                null,
                mib(96),
                mib(96),
                observation(PlaybackAutoContext.MemoryPressure.MODERATE, true, true, 1_000));
        ExoMemoryPressurePolicy.Decision repeated = resolve(
                first,
                mib(96),
                mib(96),
                observation(PlaybackAutoContext.MemoryPressure.MODERATE, true, true, 31_000));

        assertEquals(mib(64), first.effectiveTargetBytes());
        assertEquals(mib(64), repeated.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.MODERATE, repeated.mode());
    }

    @Test
    public void freshCapacityReductionClampsEvenWhenPressureLabelIsNormal() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(128),
                mib(48),
                observation(PlaybackAutoContext.MemoryPressure.NORMAL, true, true, 2_000));

        assertEquals(mib(48), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.CAPACITY_LIMITED, decision.mode());
        assertEquals(ExoMemoryPressurePolicy.RecoveryClass.CAPACITY, decision.recoveryClass());
        assertTrue(decision.degraded());
    }

    @Test
    public void stableCapacityRecoveryCanRiseOnlyTowardCurrentSafeCeiling() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(128),
                mib(48),
                normal(0));

        decision = resolve(decision, mib(128), mib(96), normal(30_000));
        assertEquals(mib(48), decision.effectiveTargetBytes());
        assertEquals(1, decision.normalSamples());

        decision = resolve(decision, mib(128), mib(96), normal(60_000));
        assertEquals(mib(64), decision.effectiveTargetBytes());
        decision = resolve(decision, mib(128), mib(96), normal(90_000));

        assertEquals(mib(96), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.RECOVERING, decision.mode());
        assertTrue(decision.preloadPaused());
    }

    @Test
    public void criticalRecoveryRequiresFreshWindowThenRisesOneTierPerSample() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(96),
                mib(96),
                observation(PlaybackAutoContext.MemoryPressure.CRITICAL, true, true, 0));

        decision = resolve(decision, mib(96), mib(96), normal(30_000));
        assertEquals(mib(16), decision.effectiveTargetBytes());
        assertEquals(1, decision.normalSamples());

        decision = resolve(decision, mib(96), mib(96), normal(60_000));
        assertEquals(mib(24), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.RECOVERING, decision.mode());

        decision = resolve(decision, mib(96), mib(96), normal(90_000));
        assertEquals(mib(48), decision.effectiveTargetBytes());
        decision = resolve(decision, mib(96), mib(96), normal(120_000));
        assertEquals(mib(64), decision.effectiveTargetBytes());
        decision = resolve(decision, mib(96), mib(96), normal(150_000));

        assertEquals(mib(96), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.NORMAL, decision.mode());
        assertEquals(ExoMemoryPressurePolicy.Reason.RECOVERED, decision.reason());
        assertFalse(decision.preloadPaused());
        assertTrue(decision.expansionAllowed());
    }

    @Test
    public void moderateRecoveryUsesShorterCooldownButStillNeedsTwoSamples() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(96),
                mib(96),
                observation(PlaybackAutoContext.MemoryPressure.MODERATE, true, true, 0));
        decision = resolve(decision, mib(96), mib(96), normal(1_000));
        assertEquals(mib(64), decision.effectiveTargetBytes());

        decision = resolve(decision, mib(96), mib(96), normal(31_000));

        assertEquals(mib(96), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.NORMAL, decision.mode());
    }

    @Test
    public void unknownAndStaleFactsCannotUnlockADegradedSession() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(64),
                mib(64),
                observation(PlaybackAutoContext.MemoryPressure.CRITICAL, true, true, 100_000));
        decision = resolve(decision, mib(64), mib(64), normal(130_000));
        assertEquals(1, decision.normalSamples());

        decision = resolve(
                decision,
                mib(64),
                mib(64),
                observation(PlaybackAutoContext.MemoryPressure.UNKNOWN, false, false, 160_000));
        assertEquals(0, decision.normalSamples());
        assertEquals(ExoMemoryPressurePolicy.Reason.UNKNOWN_HOLD, decision.reason());

        ExoMemoryPressurePolicy.Decision stale = resolve(
                decision,
                mib(64),
                mib(64),
                normal(120_000));
        assertEquals(decision.effectiveTargetBytes(), stale.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Reason.STALE_HOLD, stale.reason());
        assertTrue(stale.degraded());
    }

    @Test
    public void equalTimestampCriticalEventStillDegradesConservatively() {
        ExoMemoryPressurePolicy.Decision normal = resolve(
                null,
                mib(96),
                mib(96),
                normal(100_000));

        ExoMemoryPressurePolicy.Decision critical = resolve(
                normal,
                mib(96),
                mib(96),
                observation(
                        PlaybackAutoContext.MemoryPressure.CRITICAL,
                        true,
                        true,
                        100_000));

        assertEquals(mib(16), critical.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.CRITICAL, critical.mode());
        assertTrue(critical.degraded());
    }

    @Test
    public void baselineGrowthCannotBypassSameSampleCapacityLimit() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(64),
                mib(64),
                normal(100_000));

        decision = resolve(
                decision,
                mib(96),
                mib(64),
                normal(100_000));

        assertEquals(mib(64), decision.effectiveTargetBytes());
        assertEquals(ExoMemoryPressurePolicy.Mode.CAPACITY_LIMITED, decision.mode());
        assertTrue(decision.degraded());
    }

    @Test
    public void baselineGrowthIsHeldDuringRecoveryAndReductionClampsImmediately() {
        ExoMemoryPressurePolicy.Decision decision = resolve(
                null,
                mib(64),
                mib(64),
                observation(PlaybackAutoContext.MemoryPressure.CRITICAL, true, true, 10_000));

        decision = resolve(
                decision,
                mib(128),
                mib(128),
                ExoMemoryPressurePolicy.Observation.unknown());
        assertEquals(mib(16), decision.effectiveTargetBytes());
        assertEquals(mib(128), decision.baselineTargetBytes());

        decision = resolve(
                decision,
                mib(16),
                mib(16),
                ExoMemoryPressurePolicy.Observation.unknown());
        assertEquals(mib(16), decision.effectiveTargetBytes());
        assertTrue(decision.degraded());
    }

    private static ExoMemoryPressurePolicy.Decision resolve(
            ExoMemoryPressurePolicy.Decision previous,
            int baseline,
            int safe,
            ExoMemoryPressurePolicy.Observation observation) {
        return ExoMemoryPressurePolicy.resolve(previous, baseline, safe, observation);
    }

    private static ExoMemoryPressurePolicy.Observation normal(long sampledAt) {
        return observation(
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true,
                true,
                sampledAt);
    }

    private static ExoMemoryPressurePolicy.Observation observation(
            PlaybackAutoContext.MemoryPressure pressure,
            boolean pressureUsable,
            boolean snapshotUsable,
            long sampledAt) {
        return new ExoMemoryPressurePolicy.Observation(
                pressure,
                pressureUsable,
                snapshotUsable,
                sampledAt);
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }
}
