package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvHlsUpstreamEstimatorTest {

    @Test
    public void completeForegroundSampleIsFreshThenExpires() {
        MpvHlsUpstreamEstimator estimator = new MpvHlsUpstreamEstimator();
        long duration = TimeUnit.SECONDS.toNanos(1);

        assertTrue(estimator.recordForeground(
                2_500_000, duration, duration, true, 1_000));
        MpvHlsUpstreamEstimator.Snapshot fresh = estimator.snapshot(2_000);
        MpvHlsUpstreamEstimator.Snapshot stale = estimator.snapshot(
                1_000 + MpvHlsUpstreamEstimator.FRESH_MS);

        assertEquals(20_000_000L, fresh.bitsPerSecond());
        assertTrue(fresh.known());
        assertTrue(fresh.fresh());
        assertFalse(stale.fresh());
    }

    @Test
    public void incompleteSmallAndBackpressuredReadsAreRejected() {
        MpvHlsUpstreamEstimator estimator = new MpvHlsUpstreamEstimator();
        long second = TimeUnit.SECONDS.toNanos(1);

        assertFalse(estimator.recordForeground(
                1_000_000, second, second, false, 1_000));
        assertFalse(estimator.recordForeground(
                1_024, second, second, true, 2_000));
        assertFalse(estimator.recordForeground(
                1_000_000, TimeUnit.MILLISECONDS.toNanos(100),
                TimeUnit.SECONDS.toNanos(2), true, 3_000));

        MpvHlsUpstreamEstimator.Snapshot snapshot = estimator.snapshot(3_000);
        assertFalse(snapshot.known());
        assertEquals(3, snapshot.rejectedSamples());
        assertEquals(MpvHlsUpstreamEstimator.RejectReason.DOWNSTREAM_BACKPRESSURE,
                snapshot.lastRejectReason());
    }

    @Test
    public void slowdownAppliesImmediatelyWhileRecoveryIsSmoothed() {
        MpvHlsUpstreamEstimator estimator = new MpvHlsUpstreamEstimator();
        long second = TimeUnit.SECONDS.toNanos(1);
        estimator.recordForeground(2_500_000, second, second, true, 1_000);
        estimator.recordForeground(1_000_000, second, second, true, 2_000);
        long low = estimator.snapshot(2_000).bitsPerSecond();
        estimator.recordForeground(2_500_000, second, second, true, 3_000);
        long recovering = estimator.snapshot(3_000).bitsPerSecond();

        assertEquals(8_000_000L, low);
        assertTrue(recovering > low);
        assertTrue(recovering < 20_000_000L);
    }

    @Test
    public void previousSessionTransferCannotPublishAfterReset() {
        MpvHlsUpstreamEstimator estimator = new MpvHlsUpstreamEstimator();
        long oldGeneration = estimator.generation();
        estimator.reset();

        assertFalse(estimator.recordForeground(
                oldGeneration,
                2_500_000,
                TimeUnit.SECONDS.toNanos(1),
                TimeUnit.SECONDS.toNanos(1),
                true,
                1_000));
        assertFalse(estimator.snapshot(1_000).known());
    }
}
