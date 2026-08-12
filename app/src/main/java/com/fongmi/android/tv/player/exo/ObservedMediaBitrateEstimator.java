package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

final class ObservedMediaBitrateEstimator {

    private static final long MIN_SAMPLE_SPAN_MS = 10_000;
    private static final long MIN_LOAD_SAMPLE_SPAN_MS = 1_000;
    private static final long HIGH_CONFIDENCE_SPAN_MS = 30_000;
    private static final long MAX_SAMPLE_SPAN_MS = 60_000;
    private static final long MIN_VALID_BITRATE = 64_000;
    private static final long MAX_VALID_BITRATE = 1_000_000_000L;
    private static final long MIN_WHOLE_FILE_LENGTH_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_RATE_SAMPLES = 24;

    private final Deque<PositionSample> positions = new ArrayDeque<>();
    private final List<RateSample> rateSamples = new ArrayList<>();
    private long formatAverageBitrate;
    private long formatPeakBitrate;
    private boolean formatPeakExplicit;
    private boolean videoFormatPresent;
    private long contentLengthBytes;
    private long durationMs = C.TIME_UNSET;
    private long lastRateMediaPositionMs = C.TIME_UNSET;
    private long sequence = Long.MIN_VALUE;
    private long streamToken = Long.MIN_VALUE;

    synchronized void reset() {
        formatAverageBitrate = 0;
        formatPeakBitrate = 0;
        formatPeakExplicit = false;
        videoFormatPresent = false;
        contentLengthBytes = 0;
        durationMs = C.TIME_UNSET;
        invalidateObserved(Long.MIN_VALUE);
    }

    synchronized void updateFormats(@Nullable Format video, @Nullable Format audio) {
        videoFormatPresent = video != null;
        long videoKnown = declaredBitrate(video);
        if (videoFormatPresent && videoKnown <= 0) {
            formatAverageBitrate = 0;
            formatPeakBitrate = 0;
            formatPeakExplicit = false;
            return;
        }

        long videoAverage = declaredAverage(video);
        long audioAverage = declaredAverage(audio);
        formatAverageBitrate = safeAdd(videoAverage, audioAverage);

        long videoPeak = declaredPeak(video);
        long audioPeak = declaredPeak(audio);
        long videoBurst = videoPeak > 0 ? Math.max(videoPeak, videoAverage) : videoAverage;
        long audioBurst = audioPeak > 0 ? Math.max(audioPeak, audioAverage) : audioAverage;
        formatPeakBitrate = safeAdd(videoBurst, audioBurst);
        formatPeakExplicit = videoPeak > 0 || audioPeak > 0;
    }

    synchronized void updateContent(long contentLengthBytes, long durationMs) {
        if (contentLengthBytes > this.contentLengthBytes) this.contentLengthBytes = contentLengthBytes;
        if (durationMs > 0 && durationMs != C.TIME_UNSET) this.durationMs = durationMs;
    }

    synchronized void observeLoad(long bytesLoaded, long mediaStartTimeMs, long mediaEndTimeMs) {
        if (bytesLoaded <= 0 || mediaStartTimeMs == C.TIME_UNSET || mediaEndTimeMs == C.TIME_UNSET) return;
        long spanMs = positiveDelta(mediaEndTimeMs, mediaStartTimeMs);
        if (spanMs < MIN_LOAD_SAMPLE_SPAN_MS || spanMs > MAX_SAMPLE_SPAN_MS) return;
        addRate(rateFor(bytesLoaded, spanMs), spanMs, Source.OBSERVED_LOAD);
    }

    synchronized void observeBytePosition(long nowMs, long mediaPositionMs, PlaybackBytePositionDataSource.Snapshot bytePosition, boolean stable) {
        if (bytePosition != null) updateContent(bytePosition.contentLengthBytes(), durationMs);
        if (!stable || bytePosition == null || !bytePosition.byteSlopeEligible() || bytePosition.positionBytes() < 0 || mediaPositionMs < 0 || mediaPositionMs == C.TIME_UNSET) {
            invalidateByteSlope(
                    bytePosition == null ? Long.MIN_VALUE : bytePosition.sequence(),
                    bytePosition == null ? Long.MIN_VALUE : bytePosition.streamToken());
            return;
        }
        if (sequence != bytePosition.sequence() || streamToken != bytePosition.streamToken()) {
            invalidateByteSlope(bytePosition.sequence(), bytePosition.streamToken());
        }
        PositionSample latest = positions.peekLast();
        if (latest != null && (mediaPositionMs < latest.mediaPositionMs() || bytePosition.positionBytes() < latest.bytePositionBytes())) {
            invalidateByteSlope(bytePosition.sequence(), bytePosition.streamToken());
            latest = null;
        }
        if (latest != null && nowMs - latest.nowMs() < 1_000 && mediaPositionMs - latest.mediaPositionMs() < 1_000) return;
        positions.addLast(new PositionSample(nowMs, mediaPositionMs, bytePosition.positionBytes()));
        trimPositions(nowMs, mediaPositionMs);
        recordWindowRate(mediaPositionMs);
    }

    synchronized void disrupt() {
        invalidateByteSlope(sequence, streamToken);
    }

    synchronized Estimate estimate() {
        return estimate(true);
    }

    synchronized Estimate estimateWithoutFormat() {
        return estimate(false);
    }

    private Estimate estimate(boolean includeFormat) {
        SampleStats observed = summarize(null);
        long p50 = observed.p50BitsPerSecond();
        long p90 = observed.p90BitsPerSecond();
        long positionWindowMs = positionWindowMs();
        long contentBitrate = contentBitrate();
        Demand content = contentBitrate > 0 ? new Demand(contentBitrate, Source.CONTENT_LENGTH, Confidence.HIGH) : Demand.unknown();
        Demand format = includeFormat && formatAverageBitrate > 0 ? new Demand(formatAverageBitrate, Source.FORMAT, Confidence.MEDIUM) : Demand.unknown();
        Demand observedAverage = observedAverage(observed);
        Demand average = chooseAverage(content, format, observedAverage);

        Demand formatBurst = includeFormat && formatPeakExplicit && formatPeakBitrate > 0
                ? new Demand(Math.max(formatPeakBitrate, formatAverageBitrate), Source.FORMAT, Confidence.MEDIUM)
                : Demand.unknown();
        Demand observedBurst = observedBurst(observed);
        Demand burst = chooseBurst(formatBurst, observedBurst, average);
        Demand effective = chooseEffective(average, burst);

        return new Estimate(
                effective.bitrateBitsPerSecond(),
                effective.source(),
                effective.confidence(),
                average.bitrateBitsPerSecond(),
                average.source(),
                average.confidence(),
                burst.bitrateBitsPerSecond(),
                burst.source(),
                burst.confidence(),
                p50,
                p90,
                observed.count(),
                positionWindowMs,
                observed.durationMs(),
                contentLengthBytes,
                durationMs);
    }

    private Demand observedAverage(SampleStats observed) {
        if (observed.p50BitsPerSecond() <= 0) return Demand.unknown();
        return new Demand(observed.p50BitsPerSecond(), observed.sourceFor(false), confidenceFor(observed));
    }

    private Demand observedBurst(SampleStats observed) {
        if (observed.p90BitsPerSecond() <= 0) return Demand.unknown();
        long conservative = conservativeObserved(observed.p50BitsPerSecond(), observed.p90BitsPerSecond());
        return new Demand(conservative, observed.sourceFor(true), confidenceFor(observed));
    }

    private static Demand chooseAverage(Demand content, Demand format, Demand observed) {
        if (content.known()) return content;
        if (format.known()) return format;
        return observed;
    }

    private static Demand chooseBurst(Demand format, Demand observed, Demand average) {
        Demand selected = format;
        if (observed.known() && (!selected.known() || (observed.reliable() && observed.bitrateBitsPerSecond() > selected.bitrateBitsPerSecond()))) {
            selected = observed;
        }
        if (!selected.known()) return average;
        if (average.known() && selected.bitrateBitsPerSecond() < average.bitrateBitsPerSecond()) return average;
        return selected;
    }

    private static Demand chooseEffective(Demand average, Demand burst) {
        if (!average.known()) return burst;
        if (!burst.known() || !burst.reliable() || burst.bitrateBitsPerSecond() <= average.bitrateBitsPerSecond()) return average;
        Confidence confidence = lowerConfidence(average.confidence(), burst.confidence());
        Source source = average.source() == burst.source() ? burst.source() : Source.HYBRID;
        return new Demand(burst.bitrateBitsPerSecond(), source, confidence);
    }

    private void recordWindowRate(long mediaPositionMs) {
        if (lastRateMediaPositionMs != C.TIME_UNSET && mediaPositionMs - lastRateMediaPositionMs < 5_000) return;
        PositionSample newest = positions.peekLast();
        if (newest == null) return;
        PositionSample oldest = null;
        for (PositionSample sample : positions) {
            long spanMs = newest.mediaPositionMs() - sample.mediaPositionMs();
            if (spanMs >= MIN_SAMPLE_SPAN_MS && spanMs <= MAX_SAMPLE_SPAN_MS) {
                oldest = sample;
                break;
            }
        }
        if (oldest == null) return;
        long byteDelta = newest.bytePositionBytes() - oldest.bytePositionBytes();
        long mediaDelta = newest.mediaPositionMs() - oldest.mediaPositionMs();
        addRate(rateFor(byteDelta, mediaDelta), mediaDelta, Source.BYTE_SLOPE);
        lastRateMediaPositionMs = mediaPositionMs;
    }

    private void addRate(long rate, long spanMs, Source source) {
        if (rate < MIN_VALID_BITRATE || rate > MAX_VALID_BITRATE || spanMs <= 0) return;
        rateSamples.add(new RateSample(rate, spanMs, source));
        if (rateSamples.size() > MAX_RATE_SAMPLES) rateSamples.remove(0);
    }

    private void trimPositions(long nowMs, long mediaPositionMs) {
        while (positions.size() > 2) {
            PositionSample first = positions.peekFirst();
            if (first == null) return;
            if (nowMs - first.nowMs() <= MAX_SAMPLE_SPAN_MS && mediaPositionMs - first.mediaPositionMs() <= MAX_SAMPLE_SPAN_MS) return;
            positions.removeFirst();
        }
    }

    private void resetPositionWindow(long sequence) {
        positions.clear();
        lastRateMediaPositionMs = C.TIME_UNSET;
        this.sequence = sequence;
    }

    private void invalidateObserved(long sequence) {
        resetPositionWindow(sequence);
        streamToken = Long.MIN_VALUE;
        rateSamples.clear();
    }

    /**
     * Invalidates only byte-position slope samples.  Load-event samples remain
     * usable for HLS/DASH and for a Progressive stream after a seek/reopen.
     */
    private void invalidateByteSlope(long sequence, long streamToken) {
        resetPositionWindow(sequence);
        this.streamToken = streamToken;
        rateSamples.removeIf(sample -> sample.source() == Source.BYTE_SLOPE);
    }

    private long contentBitrate() {
        if (contentLengthBytes < MIN_WHOLE_FILE_LENGTH_BYTES || durationMs <= 0 || durationMs == C.TIME_UNSET) return 0;
        return rateFor(contentLengthBytes, durationMs);
    }

    private long positionWindowMs() {
        PositionSample first = positions.peekFirst();
        PositionSample last = positions.peekLast();
        return first == null || last == null ? 0 : Math.max(0, last.mediaPositionMs() - first.mediaPositionMs());
    }

    private SampleStats summarize(@Nullable Source source) {
        List<Long> values = new ArrayList<>();
        long duration = 0;
        for (RateSample sample : rateSamples) {
            if (source != null && sample.source() != source) continue;
            values.add(sample.bitrateBitsPerSecond());
            duration = safeAdd(duration, sample.spanMs());
        }
        if (values.isEmpty()) return SampleStats.empty(source);
        return new SampleStats(percentile(values, 50), percentile(values, 90), values.size(), duration, source, rateSamples);
    }

    private static Confidence confidenceFor(SampleStats stats) {
        if (stats.count() >= 3 && stats.durationMs() >= HIGH_CONFIDENCE_SPAN_MS) return Confidence.HIGH;
        if (stats.count() >= 2 && stats.durationMs() >= MIN_SAMPLE_SPAN_MS) return Confidence.MEDIUM;
        return stats.count() > 0 ? Confidence.LOW : Confidence.UNKNOWN;
    }

    private static long conservativeObserved(long p50, long p90) {
        long medianHeadroom = p50 <= 0 ? 0 : p50 > Long.MAX_VALUE / 5 ? Long.MAX_VALUE : p50 * 5 / 4;
        return Math.max(p90, medianHeadroom);
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    private static long rateFor(long bytes, long durationMs) {
        if (bytes <= 0 || durationMs <= 0) return 0;
        if (bytes > Long.MAX_VALUE / 8_000L) return Long.MAX_VALUE;
        return bytes * 8_000L / durationMs;
    }

    private static long positiveDelta(long end, long start) {
        if (end <= start) return 0;
        long delta = end - start;
        return delta > 0 ? delta : Long.MAX_VALUE;
    }

    private static long declaredBitrate(@Nullable Format format) {
        if (format == null) return 0;
        return Math.max(Math.max(0L, format.averageBitrate), Math.max(Math.max(0L, format.peakBitrate), Math.max(0L, format.bitrate)));
    }

    private static long declaredAverage(@Nullable Format format) {
        if (format == null) return 0;
        if (format.averageBitrate > 0) return format.averageBitrate;
        if (format.peakBitrate > 0) return format.peakBitrate;
        return Math.max(0, format.bitrate);
    }

    private static long declaredPeak(@Nullable Format format) {
        return format == null ? 0 : Math.max(0, format.peakBitrate);
    }

    private static long safeAdd(long first, long second) {
        if (first <= 0) return Math.max(0, second);
        if (second <= 0) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static Confidence lowerConfidence(Confidence first, Confidence second) {
        return confidenceRank(first) <= confidenceRank(second) ? first : second;
    }

    private static int confidenceRank(Confidence confidence) {
        return switch (confidence) {
            case UNKNOWN -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    enum Source {
        FORMAT("format"),
        CONTENT_LENGTH("content-length"),
        OBSERVED_LOAD("observed-load"),
        BYTE_SLOPE("byte-slope"),
        OBSERVED("observed"),
        HYBRID("hybrid"),
        UNKNOWN("unknown");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Confidence {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        UNKNOWN("unknown");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Estimate(
            long bitrateBitsPerSecond,
            Source source,
            Confidence confidence,
            long averageBitrateBitsPerSecond,
            Source averageSource,
            Confidence averageConfidence,
            long burstBitrateBitsPerSecond,
            Source burstSource,
            Confidence burstConfidence,
            long p50BitsPerSecond,
            long p90BitsPerSecond,
            int windowCount,
            long windowDurationMs,
            long observedDurationMs,
            long contentLengthBytes,
            long durationMs) {

        static Estimate unknown() {
            return new Estimate(0, Source.UNKNOWN, Confidence.UNKNOWN, 0, Source.UNKNOWN, Confidence.UNKNOWN, 0, Source.UNKNOWN, Confidence.UNKNOWN, 0, 0, 0, 0, 0, 0, C.TIME_UNSET);
        }

        boolean reliable() {
            return bitrateBitsPerSecond > 0 && confidence != Confidence.LOW && confidence != Confidence.UNKNOWN;
        }

        boolean averageReliable() {
            return averageBitrateBitsPerSecond > 0 && averageConfidence != Confidence.LOW && averageConfidence != Confidence.UNKNOWN;
        }

        boolean burstReliable() {
            return burstBitrateBitsPerSecond > 0 && burstConfidence != Confidence.LOW && burstConfidence != Confidence.UNKNOWN;
        }

        long effectiveBitrateBitsPerSecond() {
            return bitrateBitsPerSecond;
        }

        long averageDemandBitsPerSecond() {
            return averageBitrateBitsPerSecond;
        }

        long burstDemandBitsPerSecond() {
            return burstBitrateBitsPerSecond;
        }
    }

    private record Demand(long bitrateBitsPerSecond, Source source, Confidence confidence) {

        static Demand unknown() {
            return new Demand(0, Source.UNKNOWN, Confidence.UNKNOWN);
        }

        boolean known() {
            return bitrateBitsPerSecond > 0;
        }

        boolean reliable() {
            return known() && confidence != Confidence.LOW && confidence != Confidence.UNKNOWN;
        }
    }

    private record RateSample(long bitrateBitsPerSecond, long spanMs, Source source) {
    }

    private record SampleStats(long p50BitsPerSecond, long p90BitsPerSecond, int count, long durationMs, @Nullable Source filter, List<RateSample> samples) {

        static SampleStats empty(@Nullable Source filter) {
            return new SampleStats(0, 0, 0, 0, filter, List.of());
        }

        Source sourceFor(boolean burst) {
            long target = burst ? conservativeObserved(p50BitsPerSecond, p90BitsPerSecond) : p50BitsPerSecond;
            if (target <= 0) return Source.UNKNOWN;
            SampleStats load = filtered(Source.OBSERVED_LOAD);
            SampleStats slope = filtered(Source.BYTE_SLOPE);
            long loadTarget = burst ? conservativeObserved(load.p50BitsPerSecond, load.p90BitsPerSecond) : load.p50BitsPerSecond;
            long slopeTarget = burst ? conservativeObserved(slope.p50BitsPerSecond, slope.p90BitsPerSecond) : slope.p50BitsPerSecond;
            if (loadTarget > 0 && slopeTarget > 0) return loadTarget == slopeTarget ? Source.OBSERVED : (loadTarget > slopeTarget ? Source.OBSERVED_LOAD : Source.BYTE_SLOPE);
            if (loadTarget > 0) return Source.OBSERVED_LOAD;
            if (slopeTarget > 0) return Source.BYTE_SLOPE;
            return filter == null ? Source.OBSERVED : filter;
        }

        private SampleStats filtered(Source source) {
            List<Long> values = new ArrayList<>();
            long duration = 0;
            for (RateSample sample : samples) {
                if (sample.source() != source) continue;
                values.add(sample.bitrateBitsPerSecond());
                duration = safeAdd(duration, sample.spanMs());
            }
            if (values.isEmpty()) return empty(source);
            return new SampleStats(percentile(values, 50), percentile(values, 90), values.size(), duration, source, samples);
        }
    }

    private record PositionSample(long nowMs, long mediaPositionMs, long bytePositionBytes) {
    }
}
