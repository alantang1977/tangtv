package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObservedMediaBitrateEstimatorTest {

    @Test
    public void trustedContentAverageIsNotMaskedByFormat() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(new Format.Builder().setAverageBitrate(80_000_000).build(), new Format.Builder().setAverageBitrate(1_500_000).build());
        estimator.updateContent(mib(10_000), 2_000_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(41_943_040, estimate.averageBitrateBitsPerSecond());
        assertEquals(41_943_040, estimate.bitrateBitsPerSecond());
        assertEquals(41_943_040, estimate.burstBitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH, estimate.averageSource());
        assertEquals(ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH, estimate.source());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.HIGH, estimate.averageConfidence());
    }

    @Test
    public void formatAverageAndExplicitPeakAreSeparate() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(
                new Format.Builder().setAverageBitrate(8_000_000).setPeakBitrate(20_000_000).build(),
                new Format.Builder().setAverageBitrate(1_000_000).setPeakBitrate(2_000_000).build());

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(9_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(22_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(22_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.FORMAT, estimate.averageSource());
        assertEquals(ObservedMediaBitrateEstimator.Source.FORMAT, estimate.burstSource());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.MEDIUM, estimate.burstConfidence());
    }

    @Test
    public void cbrFormatWithoutPeakUsesTheAverageForBothDemands() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(new Format.Builder().setAverageBitrate(10_000_000).build(), null);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(10_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(10_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(10_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.FORMAT, estimate.source());
    }

    @Test
    public void positiveFormatDoesNotMaskTrustedObservedP90() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(new Format.Builder().setAverageBitrate(8_000_000).build(), null);
        observeRate(estimator, 8_000_000, 10_000);
        observeRate(estimator, 8_000_000, 10_000);
        observeRate(estimator, 20_000_000, 10_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(8_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(20_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(20_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD, estimate.burstSource());
        assertEquals(ObservedMediaBitrateEstimator.Source.HYBRID, estimate.source());
    }

    @Test
    public void observedVbrBurstRaisesEffectiveDemand() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateContent(rateBytes(8_000_000, 60_000_000), 60_000_000);
        observeRate(estimator, 8_000_000, 10_000);
        observeRate(estimator, 8_000_000, 10_000);
        observeRate(estimator, 20_000_000, 10_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimateWithoutFormat();
        assertEquals(8_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(20_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(20_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH, estimate.averageSource());
        assertEquals(ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD, estimate.burstSource());
        assertTrue(estimate.burstReliable());
        assertEquals(30_000, estimate.observedDurationMs());
    }

    @Test
    public void isolatedOutlierIsExposedButDoesNotExpandEffectiveDemand() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(new Format.Builder().setAverageBitrate(10_000_000).build(), null);
        observeRate(estimator, 100_000_000, 10_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(10_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(125_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(10_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.LOW, estimate.burstConfidence());
        assertFalse(estimate.burstReliable());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.MEDIUM, estimate.confidence());
    }

    @Test
    public void singleObservedSampleRemainsUnreliableWithoutAnotherAverage() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        observeRate(estimator, 100_000_000, 10_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimateWithoutFormat();
        assertEquals(100_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(125_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(100_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.LOW, estimate.confidence());
        assertFalse(estimate.reliable());
    }

    @Test
    public void observedP50AndP90RemainVisibleSeparately() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        observeRate(estimator, 8_000_000, 10_000);
        observeRate(estimator, 12_000_000, 10_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimateWithoutFormat();
        assertEquals(8_000_000, estimate.p50BitsPerSecond());
        assertEquals(12_000_000, estimate.p90BitsPerSecond());
        assertEquals(8_000_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(12_000_000, estimate.burstBitrateBitsPerSecond());
        assertEquals(12_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.MEDIUM, estimate.confidence());
    }

    @Test
    public void observedLoadSamplesUseTheirOwnSourceAndCoverage() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        observeRate(estimator, 10_000_000, 6_000);
        observeRate(estimator, 10_000_000, 6_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimateWithoutFormat();
        assertEquals(ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD, estimate.source());
        assertEquals(ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD, estimate.averageSource());
        assertEquals(ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD, estimate.burstSource());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.MEDIUM, estimate.confidence());
        assertEquals(12_000, estimate.observedDurationMs());
    }

    @Test
    public void byteSlopeUsesConservativePercentile() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        long sequence = 7;
        estimator.observeBytePosition(0, 0, snapshot(sequence, 0), true);
        estimator.observeBytePosition(10_000, 10_000, snapshot(sequence, 100_000_000), true);
        estimator.observeBytePosition(20_000, 20_000, snapshot(sequence, 225_000_000), true);
        estimator.observeBytePosition(30_000, 30_000, snapshot(sequence, 375_000_000), true);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(ObservedMediaBitrateEstimator.Source.BYTE_SLOPE, estimate.source());
        assertTrue(estimate.bitrateBitsPerSecond() >= 100_000_000);
        assertEquals(ObservedMediaBitrateEstimator.Source.BYTE_SLOPE, estimate.burstSource());
        assertTrue(estimate.p90BitsPerSecond() >= estimate.p50BitsPerSecond());
        assertTrue(estimate.windowCount() >= 3);
        assertTrue(estimate.observedDurationMs() >= 30_000);
    }

    @Test
    public void audioOnlyFormatCanProvideAverage() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(null, new Format.Builder().setAverageBitrate(1_500_000).build());

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(1_500_000, estimate.averageBitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.FORMAT, estimate.source());
    }

    @Test
    public void unknownVideoBitrateDoesNotLetAudioOverrideWholeFileEstimate() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(new Format.Builder().setSampleMimeType("video/hevc").build(), new Format.Builder().setAverageBitrate(384_000).build());
        estimator.updateContent(80_468_115_456L, 8_502_743);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH, estimate.source());
        assertTrue(estimate.bitrateBitsPerSecond() > 70_000_000);
    }

    @Test
    public void contentLengthAndDurationProvideHighConfidenceAverage() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateContent(60_000_000_000L, 6_000_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(80_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH, estimate.source());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.HIGH, estimate.confidence());
    }

    @Test
    public void smallSegmentLengthIsNotTreatedAsWholeMovie() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateContent(8_000_000, 6_000_000);

        assertEquals(ObservedMediaBitrateEstimator.Source.UNKNOWN, estimator.estimate().source());
    }

    @Test
    public void seekOrSequenceChangeInvalidatesSlopeWindow() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.observeBytePosition(0, 0, snapshot(1, 0), true);
        estimator.observeBytePosition(10_000, 10_000, snapshot(1, 100_000_000), true);
        estimator.observeBytePosition(11_000, 2_000, snapshot(2, 10_000_000), true);

        assertEquals(0, estimator.estimate().windowDurationMs());
        assertEquals(0, estimator.estimate().observedDurationMs());
    }

    @Test
    public void unstableOrZeroDeltaSamplesDoNotCreateEstimate() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.observeBytePosition(0, 0, snapshot(1, 0), true);
        estimator.observeBytePosition(20_000, 20_000, snapshot(1, 0), true);
        estimator.observeBytePosition(30_000, 30_000, snapshot(1, 100_000_000), false);

        assertEquals(ObservedMediaBitrateEstimator.Source.UNKNOWN, estimator.estimate().source());
    }

    @Test
    public void ineligibleProtocolCannotCreateByteSlope() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.observeBytePosition(0, 0, ineligibleSnapshot(1, 0), true);
        estimator.observeBytePosition(10_000, 10_000, ineligibleSnapshot(1, 100_000_000), true);
        estimator.observeBytePosition(20_000, 20_000, ineligibleSnapshot(1, 200_000_000), true);

        assertEquals(ObservedMediaBitrateEstimator.Source.UNKNOWN, estimator.estimateWithoutFormat().source());
        assertEquals(0, estimator.estimateWithoutFormat().windowCount());
    }

    @Test
    public void invalidatingSlopeKeepsLoadFallback() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        observeRate(estimator, 12_000_000, 6_000);
        observeRate(estimator, 12_000_000, 6_000);
        estimator.observeBytePosition(0, 0, ineligibleSnapshot(3, 0), true);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimateWithoutFormat();
        assertEquals(ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD, estimate.source());
        assertEquals(12_000, estimate.observedDurationMs());
        assertEquals(0, estimate.windowDurationMs());
    }

    @Test
    public void streamTokenChangeInvalidatesSlopeEvenWhenSequenceMatches() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.observeBytePosition(0, 0, progressiveSnapshot(9, 100, 0), true);
        estimator.observeBytePosition(10_000, 10_000, progressiveSnapshot(9, 100, 100_000_000), true);
        estimator.observeBytePosition(20_000, 20_000, progressiveSnapshot(9, 200, 200_000_000), true);

        assertEquals(0, estimator.estimateWithoutFormat().windowDurationMs());
        assertEquals(0, estimator.estimateWithoutFormat().windowCount());
    }

    @Test
    public void saturatedWholeFileRateDoesNotWrap() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateContent(Long.MAX_VALUE, 1);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(Long.MAX_VALUE, estimate.averageBitrateBitsPerSecond());
        assertEquals(Long.MAX_VALUE, estimate.bitrateBitsPerSecond());
        assertTrue(estimate.bitrateBitsPerSecond() > 0);
    }

    private static void observeRate(ObservedMediaBitrateEstimator estimator, long bitrate, long spanMs) {
        estimator.observeLoad(rateBytes(bitrate, spanMs), 0, spanMs);
    }

    private static long rateBytes(long bitrate, long durationMs) {
        return bitrate * durationMs / 8_000L;
    }

    private static PlaybackBytePositionDataSource.Snapshot snapshot(long sequence, long position) {
        return progressiveSnapshot(sequence, sequence, position);
    }

    private static PlaybackBytePositionDataSource.Snapshot progressiveSnapshot(long sequence, long token, long position) {
        return new PlaybackBytePositionDataSource.Snapshot(
                sequence,
                position,
                0,
                PlaybackBytePositionDataSource.Eligibility.PROGRESSIVE,
                token,
                1,
                true);
    }

    private static PlaybackBytePositionDataSource.Snapshot ineligibleSnapshot(long sequence, long position) {
        return new PlaybackBytePositionDataSource.Snapshot(
                sequence,
                position,
                0,
                PlaybackBytePositionDataSource.Eligibility.INELIGIBLE,
                1,
                2,
                false);
    }

    private static long mib(long value) {
        return value * 1024L * 1024L;
    }
}
