package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoThroughputEstimatorTest {

    private static final PlaybackAutoContext.SessionToken SESSION =
            new PlaybackAutoContext.SessionToken("p-throughput-1", 1);
    private static final ExoThroughputPathPolicy.Decision TRUSTED =
            new ExoThroughputPathPolicy.Decision(
                    ExoThroughputPathPolicy.Trust.TRUSTED,
                    PlaybackAutoContext.Confidence.HIGH,
                    ExoThroughputPathPolicy.Reason.DIRECT_REMOTE);
    private static final ExoThroughputPathPolicy.Decision LIMITED =
            new ExoThroughputPathPolicy.Decision(
                    ExoThroughputPathPolicy.Trust.LIMITED,
                    PlaybackAutoContext.Confidence.LOW,
                    ExoThroughputPathPolicy.Reason.PRELOAD_CONTENTION);

    @Test
    public void stableTrustedSamplesRiseOnlyAfterLongWindow() {
        ExoThroughputEstimator estimator = estimator(4_000_000L);

        observe(estimator, 4_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 8_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 12_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        ExoThroughputEstimator.Snapshot early = estimator.snapshot();
        observe(estimator, 16_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 20_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        ExoThroughputEstimator.Snapshot ready = estimator.snapshot();

        assertEquals(4_000_000L, early.effectiveEstimateBitsPerSecond());
        assertEquals(ExoThroughputEstimator.Action.INCREASE, ready.action());
        assertTrue(ready.effectiveEstimateBitsPerSecond() > 4_000_000L);
        assertTrue(ready.effectiveEstimateBitsPerSecond() < 8_000_000L);
        assertTrue(ready.longSampleCount() >= ExoThroughputEstimator.MIN_UPGRADE_SAMPLES);
    }

    @Test
    public void suddenDropUsesShortWindowImmediately() {
        ExoThroughputEstimator estimator = estimator(10_000_000L);
        observe(estimator, 2_000, 10_000_000L, 10_000_000L, TRUSTED, false);
        observe(estimator, 5_000, 10_000_000L, 10_000_000L, TRUSTED, false);
        observe(estimator, 8_000, 2_000_000L, 2_000_000L, TRUSTED, false);

        ExoThroughputEstimator.Snapshot snapshot = estimator.snapshot();

        assertEquals(ExoThroughputEstimator.Action.DECREASE, snapshot.action());
        assertEquals(2_000_000L, snapshot.effectiveEstimateBitsPerSecond());
        assertEquals(ExoThroughputEstimator.Reason.FAST_DECREASE, snapshot.reason());
    }

    @Test
    public void recoveryAfterDropRebuildsTrustedWindowAndRisesInSteps() {
        ExoThroughputEstimator estimator = estimator(10_000_000L);
        observe(estimator, 2_000, 2_000_000L, 2_000_000L, TRUSTED, false);
        assertEquals(2_000_000L, estimator.snapshot().effectiveEstimateBitsPerSecond());

        observe(estimator, 6_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 10_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 14_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 18_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 22_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        ExoThroughputEstimator.Snapshot recovered = estimator.snapshot();

        assertEquals(ExoThroughputEstimator.Action.INCREASE, recovered.action());
        assertTrue(recovered.effectiveEstimateBitsPerSecond() > 2_000_000L);
        assertTrue(recovered.effectiveEstimateBitsPerSecond() <= 2_300_000L);
    }

    @Test
    public void oneHighOutlierCannotDriveUpgrade() {
        ExoThroughputEstimator estimator = estimator(8_000_000L);
        observe(estimator, 2_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 5_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 8_000, 80_000_000L, 8_000_000L, TRUSTED, false);

        assertEquals(8_000_000L, estimator.snapshot().effectiveEstimateBitsPerSecond());
        assertFalse(estimator.snapshot().action() == ExoThroughputEstimator.Action.INCREASE);
    }

    @Test
    public void oneLowOutlierIsBoundedAndDoesNotPermanentlyBlockRecovery() {
        ExoThroughputEstimator estimator = estimator(8_000_000L);
        observe(estimator, 2_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 5_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 8_000, 4_000_000L, 8_000_000L, TRUSTED, false);
        long lowered = estimator.snapshot().effectiveEstimateBitsPerSecond();

        assertTrue(lowered > 4_000_000L);
        assertTrue(lowered < 8_000_000L);

        observe(estimator, 12_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 16_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 20_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 24_000, 8_000_000L, 8_000_000L, TRUSTED, false);
        observe(estimator, 28_000, 8_000_000L, 8_000_000L, TRUSTED, false);

        assertEquals(ExoThroughputEstimator.Action.INCREASE, estimator.snapshot().action());
        assertTrue(estimator.snapshot().effectiveEstimateBitsPerSecond() > lowered);
    }

    @Test
    public void preloadContendedSamplesCanTightenButCannotUpgrade() {
        ExoThroughputEstimator estimator = estimator(4_000_000L);
        observe(estimator, 4_000, 8_000_000L, 8_000_000L, LIMITED, true);
        observe(estimator, 8_000, 8_000_000L, 8_000_000L, LIMITED, true);
        observe(estimator, 12_000, 8_000_000L, 8_000_000L, LIMITED, true);
        observe(estimator, 16_000, 8_000_000L, 8_000_000L, LIMITED, true);

        assertEquals(4_000_000L, estimator.snapshot().effectiveEstimateBitsPerSecond());
        assertEquals(0, estimator.snapshot().longSampleCount());
        assertEquals(ExoThroughputEstimator.Reason.PRELOAD_CONTENTION,
                estimator.snapshot().reason());

        observe(estimator, 20_000, 1_000_000L, 4_000_000L, LIMITED, true);
        assertEquals(ExoThroughputEstimator.Action.DECREASE, estimator.snapshot().action());
    }

    @Test
    public void shortOutOfOrderAndOldSessionSamplesAreRejected() {
        ExoThroughputEstimator estimator = estimator(4_000_000L);
        ExoThroughputEstimator.Update shortSample = estimator.observe(
                SESSION, 1_000, 1_024, 20, 4_000_000L, TRUSTED, false, true);
        observe(estimator, 2_000, 4_000_000L, 4_000_000L, TRUSTED, false);
        ExoThroughputEstimator.Update outOfOrder = estimator.observe(
                SESSION, 1_500, bytesFor(4_000_000L), 1_000,
                4_000_000L, TRUSTED, false, true);
        PlaybackAutoContext.SessionToken old =
                new PlaybackAutoContext.SessionToken("p-throughput-old", 9);
        ExoThroughputEstimator.Update stale = estimator.observe(
                old, 3_000, bytesFor(4_000_000L), 1_000,
                4_000_000L, TRUSTED, false, true);

        assertFalse(shortSample.accepted());
        assertEquals(ExoThroughputEstimator.Reason.SAMPLE_TOO_SHORT, shortSample.reason());
        assertFalse(outOfOrder.accepted());
        assertEquals(ExoThroughputEstimator.Reason.OUT_OF_ORDER, outOfOrder.reason());
        assertFalse(stale.accepted());
        assertEquals(ExoThroughputEstimator.Reason.STALE_SESSION, stale.reason());
    }

    @Test
    public void expiredWindowCanTightenToRawButCannotInventAnUpgrade() {
        ExoThroughputEstimator estimator = estimator(8_000_000L);
        observe(estimator, 1_000, 8_000_000L, 8_000_000L, TRUSTED, false);

        estimator.observe(
                SESSION,
                61_001,
                1_024,
                20,
                2_000_000L,
                TRUSTED,
                false,
                true);

        assertEquals(0, estimator.snapshot().sampleCount());
        assertEquals(2_000_000L, estimator.snapshot().effectiveEstimateBitsPerSecond());
        assertEquals(ExoThroughputEstimator.Reason.SAMPLES_EXPIRED,
                estimator.snapshot().reason());
    }

    @Test
    public void sampleStorageIsBoundedAndNewSessionClearsOldState() {
        ExoThroughputEstimator estimator = estimator(4_000_000L);
        for (int i = 1; i <= 100; i++) {
            observe(estimator, i * 500L, 4_000_000L, 4_000_000L, TRUSTED, false);
        }
        assertTrue(estimator.snapshot().sampleCount() <= ExoThroughputEstimator.MAX_SAMPLES);

        PlaybackAutoContext.SessionToken replacement =
                new PlaybackAutoContext.SessionToken("p-throughput-2", 2);
        estimator.reset(replacement, 60_000, 2_000_000L,
                ExoThroughputEstimator.Reason.SESSION_RESET);

        assertEquals(replacement, estimator.snapshot().session());
        assertEquals(0, estimator.snapshot().sampleCount());
        assertEquals(2_000_000L, estimator.snapshot().effectiveEstimateBitsPerSecond());
    }

    private static ExoThroughputEstimator estimator(long prior) {
        ExoThroughputEstimator estimator = new ExoThroughputEstimator();
        estimator.reset(SESSION, 0, prior, ExoThroughputEstimator.Reason.SESSION_RESET);
        return estimator;
    }

    private static void observe(
            ExoThroughputEstimator estimator,
            long now,
            long sampleBitsPerSecond,
            long rawBitsPerSecond,
            ExoThroughputPathPolicy.Decision path,
            boolean contended) {
        estimator.observe(
                SESSION,
                now,
                bytesFor(sampleBitsPerSecond),
                1_000,
                rawBitsPerSecond,
                path,
                contended,
                true);
    }

    private static long bytesFor(long bitsPerSecond) {
        return bitsPerSecond / 8;
    }
}
