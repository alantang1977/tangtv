package com.fongmi.android.tv.player.exo;

/** Aggregates Media3 frame processing offsets without logging every rendered frame. */
public final class ExoFrameTimingMetrics {

    private long totalProcessingOffsetUs;
    private long frameCount;
    private int lateBatchCount;
    private int codecErrorCount;
    private String lastCodecError = "";
    private long totalReleaseLeadUs;
    private long releaseFrameCount;
    private long lateReleaseFrameCount;
    private long maxLateReleaseUs;
    private long totalReleaseJitterUs;
    private long releaseJitterSampleCount;
    private long totalCallbackGapUs;
    private long callbackGapSampleCount;
    private long maxCallbackGapUs;
    private long previousPresentationTimeUs = Long.MIN_VALUE;
    private long previousReleaseTimeNs = Long.MIN_VALUE;
    private long previousCallbackTimeNs = Long.MIN_VALUE;
    private final SignedTimingBucketsAccumulator processingOffsetBuckets =
            new SignedTimingBucketsAccumulator();
    private final SignedTimingBucketsAccumulator releaseLeadBuckets =
            new SignedTimingBucketsAccumulator();
    private final MagnitudeTimingBucketsAccumulator releaseJitterBuckets =
            new MagnitudeTimingBucketsAccumulator(2_000, 5_000, 10_000);
    private final MagnitudeTimingBucketsAccumulator callbackGapBuckets =
            new MagnitudeTimingBucketsAccumulator(20_000, 40_000, 80_000);

    synchronized void reset() {
        totalProcessingOffsetUs = 0;
        frameCount = 0;
        lateBatchCount = 0;
        codecErrorCount = 0;
        lastCodecError = "";
        totalReleaseLeadUs = 0;
        releaseFrameCount = 0;
        lateReleaseFrameCount = 0;
        maxLateReleaseUs = 0;
        totalReleaseJitterUs = 0;
        releaseJitterSampleCount = 0;
        totalCallbackGapUs = 0;
        callbackGapSampleCount = 0;
        maxCallbackGapUs = 0;
        previousPresentationTimeUs = Long.MIN_VALUE;
        previousReleaseTimeNs = Long.MIN_VALUE;
        previousCallbackTimeNs = Long.MIN_VALUE;
        processingOffsetBuckets.reset();
        releaseLeadBuckets.reset();
        releaseJitterBuckets.reset();
        callbackGapBuckets.reset();
    }

    synchronized void observeProcessingOffset(long batchOffsetUs, int batchFrameCount) {
        observeProcessingOffset(batchOffsetUs, batchFrameCount, true);
    }

    synchronized void observeProcessingOffset(
            long batchOffsetUs,
            int batchFrameCount,
            boolean collectDistribution) {
        if (batchFrameCount <= 0) return;
        totalProcessingOffsetUs = saturatedAdd(totalProcessingOffsetUs, batchOffsetUs);
        frameCount = saturatedAdd(frameCount, batchFrameCount);
        if (collectDistribution) {
            processingOffsetBuckets.add(
                    batchOffsetUs / batchFrameCount, batchFrameCount);
        }
        if (batchOffsetUs < 0) lateBatchCount = saturatedIncrement(lateBatchCount);
    }

    synchronized void observeCodecError(Exception error) {
        codecErrorCount = saturatedIncrement(codecErrorCount);
        lastCodecError = error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /** Starts a new continuous playback segment without discarding accumulated diagnostics. */
    synchronized void resetReleaseContinuity() {
        previousPresentationTimeUs = Long.MIN_VALUE;
        previousReleaseTimeNs = Long.MIN_VALUE;
        previousCallbackTimeNs = Long.MIN_VALUE;
    }

    /**
     * Records the renderer's scheduled release time. This is a timing proxy only: Android does
     * not expose HWC composition or MediaCodec queue occupancy to the app.
     */
    synchronized void observeFrameRelease(long presentationTimeUs, long releaseTimeNs, long nowNs) {
        if (releaseTimeNs <= 0 || nowNs <= 0) return;
        long leadUs = saturatedSubtract(releaseTimeNs, nowNs) / 1_000L;
        totalReleaseLeadUs = saturatedAdd(totalReleaseLeadUs, leadUs);
        releaseFrameCount = saturatedAdd(releaseFrameCount, 1);
        releaseLeadBuckets.add(leadUs, 1);
        if (leadUs < 0) {
            lateReleaseFrameCount = saturatedAdd(lateReleaseFrameCount, 1);
            maxLateReleaseUs = Math.max(
                    maxLateReleaseUs, saturatedAbs(leadUs));
        }
        if (previousReleaseTimeNs != Long.MIN_VALUE) {
            long callbackGapUs = Math.max(
                    0, saturatedSubtract(nowNs, previousCallbackTimeNs) / 1_000L);
            totalCallbackGapUs = saturatedAdd(totalCallbackGapUs, callbackGapUs);
            callbackGapSampleCount = saturatedAdd(callbackGapSampleCount, 1);
            maxCallbackGapUs = Math.max(maxCallbackGapUs, callbackGapUs);
            callbackGapBuckets.add(callbackGapUs);
            if (previousPresentationTimeUs != Long.MIN_VALUE) {
                long releaseDeltaUs = saturatedSubtract(
                        releaseTimeNs, previousReleaseTimeNs) / 1_000L;
                long presentationDeltaUs = saturatedSubtract(
                        presentationTimeUs, previousPresentationTimeUs);
                long jitterUs = saturatedAbs(saturatedSubtract(
                        releaseDeltaUs, presentationDeltaUs));
                totalReleaseJitterUs = saturatedAdd(totalReleaseJitterUs, jitterUs);
                releaseJitterSampleCount = saturatedAdd(releaseJitterSampleCount, 1);
                releaseJitterBuckets.add(jitterUs);
            }
        }
        previousPresentationTimeUs = presentationTimeUs;
        previousReleaseTimeNs = releaseTimeNs;
        previousCallbackTimeNs = nowNs;
    }

    synchronized Snapshot snapshot() {
        long averageOffsetUs = frameCount <= 0 ? 0 : totalProcessingOffsetUs / frameCount;
        long averageReleaseLeadUs = releaseFrameCount <= 0 ? 0 : totalReleaseLeadUs / releaseFrameCount;
        long averageReleaseJitterUs = releaseJitterSampleCount <= 0 ? 0 : totalReleaseJitterUs / releaseJitterSampleCount;
        long averageCallbackGapUs = callbackGapSampleCount <= 0 ? 0 : totalCallbackGapUs / callbackGapSampleCount;
        return new Snapshot(averageOffsetUs, frameCount, lateBatchCount, codecErrorCount, lastCodecError,
                releaseFrameCount, averageReleaseLeadUs, lateReleaseFrameCount, maxLateReleaseUs,
                averageReleaseJitterUs, releaseJitterSampleCount, averageCallbackGapUs,
                callbackGapSampleCount, maxCallbackGapUs,
                processingOffsetBuckets.snapshot(), releaseLeadBuckets.snapshot(),
                releaseJitterBuckets.snapshot(), callbackGapBuckets.snapshot());
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0 && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    private static long saturatedSubtract(long first, long second) {
        if (second > 0 && first < Long.MIN_VALUE + second) return Long.MIN_VALUE;
        if (second < 0 && first > Long.MAX_VALUE + second) return Long.MAX_VALUE;
        return first - second;
    }

    private static long saturatedAbs(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static int saturatedIncrement(int value) {
        return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;
    }

    public record Snapshot(long averageOffsetUs, long frameCount, int lateBatchCount, int codecErrorCount,
                           String lastCodecError, long releaseFrameCount, long averageReleaseLeadUs,
                           long lateReleaseFrameCount, long maxLateReleaseUs, long averageReleaseJitterUs,
                           long releaseJitterSampleCount, long averageCallbackGapUs,
                           long callbackGapSampleCount, long maxCallbackGapUs,
                           SignedTimingBuckets processingOffsetBuckets,
                           SignedTimingBuckets releaseLeadBuckets,
                           MagnitudeTimingBuckets releaseJitterBuckets,
                           MagnitudeTimingBuckets callbackGapBuckets) {

        public Snapshot {
            lastCodecError = lastCodecError == null ? "" : lastCodecError;
            processingOffsetBuckets = processingOffsetBuckets == null
                    ? SignedTimingBuckets.empty() : processingOffsetBuckets;
            releaseLeadBuckets = releaseLeadBuckets == null
                    ? SignedTimingBuckets.empty() : releaseLeadBuckets;
            releaseJitterBuckets = releaseJitterBuckets == null
                    ? MagnitudeTimingBuckets.empty() : releaseJitterBuckets;
            callbackGapBuckets = callbackGapBuckets == null
                    ? MagnitudeTimingBuckets.empty() : callbackGapBuckets;
        }
    }

    public record SignedTimingBuckets(
            long earlierThanMinus20Ms,
            long minus20MsToZero,
            long zeroTo20Ms,
            long twentyTo50Ms,
            long fiftyTo75Ms,
            long seventyFiveTo100Ms,
            long atLeast100Ms) {

        static SignedTimingBuckets empty() {
            return new SignedTimingBuckets(0, 0, 0, 0, 0, 0, 0);
        }

        public long total() {
            return saturatedAdd(saturatedAdd(saturatedAdd(
                    earlierThanMinus20Ms, minus20MsToZero),
                    saturatedAdd(zeroTo20Ms, twentyTo50Ms)),
                    saturatedAdd(saturatedAdd(fiftyTo75Ms,
                            seventyFiveTo100Ms), atLeast100Ms));
        }

        public String compact() {
            return earlierThanMinus20Ms + "/" + minus20MsToZero + "/"
                    + zeroTo20Ms + "/" + twentyTo50Ms + "/"
                    + fiftyTo75Ms + "/" + seventyFiveTo100Ms + "/"
                    + atLeast100Ms;
        }
    }

    public record MagnitudeTimingBuckets(
            long low,
            long medium,
            long high,
            long extreme) {

        static MagnitudeTimingBuckets empty() {
            return new MagnitudeTimingBuckets(0, 0, 0, 0);
        }

        public long total() {
            return saturatedAdd(saturatedAdd(low, medium),
                    saturatedAdd(high, extreme));
        }

        public String compact() {
            return low + "/" + medium + "/" + high + "/" + extreme;
        }
    }

    private static final class SignedTimingBucketsAccumulator {

        private long earlierThanMinus20Ms;
        private long minus20MsToZero;
        private long zeroTo20Ms;
        private long twentyTo50Ms;
        private long fiftyTo75Ms;
        private long seventyFiveTo100Ms;
        private long atLeast100Ms;

        private void reset() {
            earlierThanMinus20Ms = 0;
            minus20MsToZero = 0;
            zeroTo20Ms = 0;
            twentyTo50Ms = 0;
            fiftyTo75Ms = 0;
            seventyFiveTo100Ms = 0;
            atLeast100Ms = 0;
        }

        private void add(long valueUs, long weight) {
            if (weight <= 0) return;
            if (valueUs < -20_000) {
                earlierThanMinus20Ms = saturatedAdd(
                        earlierThanMinus20Ms, weight);
            } else if (valueUs < 0) {
                minus20MsToZero = saturatedAdd(minus20MsToZero, weight);
            } else if (valueUs < 20_000) {
                zeroTo20Ms = saturatedAdd(zeroTo20Ms, weight);
            } else if (valueUs < 50_000) {
                twentyTo50Ms = saturatedAdd(twentyTo50Ms, weight);
            } else if (valueUs < 75_000) {
                fiftyTo75Ms = saturatedAdd(fiftyTo75Ms, weight);
            } else if (valueUs < 100_000) {
                seventyFiveTo100Ms = saturatedAdd(
                        seventyFiveTo100Ms, weight);
            } else {
                atLeast100Ms = saturatedAdd(atLeast100Ms, weight);
            }
        }

        private SignedTimingBuckets snapshot() {
            return new SignedTimingBuckets(
                    earlierThanMinus20Ms,
                    minus20MsToZero,
                    zeroTo20Ms,
                    twentyTo50Ms,
                    fiftyTo75Ms,
                    seventyFiveTo100Ms,
                    atLeast100Ms);
        }
    }

    private static final class MagnitudeTimingBucketsAccumulator {

        private final long lowThresholdUs;
        private final long mediumThresholdUs;
        private final long highThresholdUs;
        private long low;
        private long medium;
        private long high;
        private long extreme;

        private MagnitudeTimingBucketsAccumulator(
                long lowThresholdUs,
                long mediumThresholdUs,
                long highThresholdUs) {
            this.lowThresholdUs = lowThresholdUs;
            this.mediumThresholdUs = mediumThresholdUs;
            this.highThresholdUs = highThresholdUs;
        }

        private void reset() {
            low = 0;
            medium = 0;
            high = 0;
            extreme = 0;
        }

        private void add(long valueUs) {
            long value = Math.max(0, valueUs);
            if (value < lowThresholdUs) {
                low = saturatedAdd(low, 1);
            } else if (value < mediumThresholdUs) {
                medium = saturatedAdd(medium, 1);
            } else if (value < highThresholdUs) {
                high = saturatedAdd(high, 1);
            } else {
                extreme = saturatedAdd(extreme, 1);
            }
        }

        private MagnitudeTimingBuckets snapshot() {
            return new MagnitudeTimingBuckets(low, medium, high, extreme);
        }
    }
}
