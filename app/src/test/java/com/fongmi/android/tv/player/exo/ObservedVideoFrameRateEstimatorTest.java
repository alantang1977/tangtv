package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObservedVideoFrameRateEstimatorTest {

    @Test
    public void estimatesCadenceFromPresentationTimestamps() {
        ObservedVideoFrameRateEstimator estimator = new ObservedVideoFrameRateEstimator();
        for (int i = 0; i < 16; i++) estimator.observe(Math.round(i * 1_000_000f / 23.976f));

        ObservedVideoFrameRateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(23.976f, estimate.frameRate(), 0.02f);
        assertTrue(estimate.sampleCount() >= 8);
    }

    @Test
    public void waitsForEnoughFrames() {
        ObservedVideoFrameRateEstimator estimator = new ObservedVideoFrameRateEstimator();
        for (int i = 0; i < 8; i++) estimator.observe(i * 40_000L);

        assertEquals(0f, estimator.estimate().frameRate(), 0f);
    }

    @Test
    public void largeTimestampGapResetsCadenceWindow() {
        ObservedVideoFrameRateEstimator estimator = new ObservedVideoFrameRateEstimator();
        for (int i = 0; i < 16; i++) estimator.observe(i * 40_000L);
        estimator.observe(2_000_000L);

        assertEquals(0f, estimator.estimate().frameRate(), 0f);
    }
}
