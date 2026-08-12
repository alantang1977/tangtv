package androidx.media3.mpvplayer;

import java.util.concurrent.TimeUnit;

/** Conservative estimator fed only by completed foreground proxy reads. */
final class MpvHlsUpstreamEstimator {

    static final long FRESH_MS = 15_000L;
    static final long MIN_SAMPLE_BYTES = 64L * 1024L;
    static final long MIN_SAMPLE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    static final long IDLE_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private long estimateBitsPerSecond;
    private long sampledAtElapsedMs = -1;
    private long generation;
    private int acceptedSamples;
    private int rejectedSamples;
    private RejectReason lastRejectReason = RejectReason.NONE;

    synchronized void reset() {
        if (generation < Long.MAX_VALUE) generation++;
        estimateBitsPerSecond = 0;
        sampledAtElapsedMs = -1;
        acceptedSamples = 0;
        rejectedSamples = 0;
        lastRejectReason = RejectReason.NONE;
    }

    synchronized boolean recordForeground(
            long bytes,
            long blockedReadNanos,
            long wallNanos,
            boolean complete,
            long completedAtElapsedMs) {
        return recordForeground(generation, bytes, blockedReadNanos,
                wallNanos, complete, completedAtElapsedMs);
    }

    synchronized boolean recordForeground(
            long expectedGeneration,
            long bytes,
            long blockedReadNanos,
            long wallNanos,
            boolean complete,
            long completedAtElapsedMs) {
        if (expectedGeneration != generation) return false;
        RejectReason rejected = rejectReason(
                bytes, blockedReadNanos, wallNanos, complete);
        if (rejected != RejectReason.NONE) {
            rejectedSamples = increment(rejectedSamples);
            lastRejectReason = rejected;
            return false;
        }
        long sample = bitsPerSecond(bytes, wallNanos);
        if (sample <= 0) {
            rejectedSamples = increment(rejectedSamples);
            lastRejectReason = RejectReason.INVALID_RATE;
            return false;
        }
        if (estimateBitsPerSecond <= 0 || sample < estimateBitsPerSecond) {
            estimateBitsPerSecond = sample;
        } else {
            estimateBitsPerSecond = weightedRecovery(estimateBitsPerSecond, sample);
        }
        sampledAtElapsedMs = Math.max(0, completedAtElapsedMs);
        acceptedSamples = increment(acceptedSamples);
        lastRejectReason = RejectReason.NONE;
        return true;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized Snapshot snapshot(long nowElapsedMs) {
        long now = Math.max(0, nowElapsedMs);
        boolean known = estimateBitsPerSecond > 0 && sampledAtElapsedMs >= 0;
        long age = known && now >= sampledAtElapsedMs
                ? now - sampledAtElapsedMs : Long.MAX_VALUE;
        boolean fresh = known && age < FRESH_MS;
        return new Snapshot(
                estimateBitsPerSecond,
                known,
                fresh,
                sampledAtElapsedMs,
                known && age != Long.MAX_VALUE ? age : -1,
                acceptedSamples,
                rejectedSamples,
                lastRejectReason);
    }

    private static RejectReason rejectReason(
            long bytes,
            long blockedReadNanos,
            long wallNanos,
            boolean complete) {
        if (!complete) return RejectReason.INCOMPLETE;
        if (bytes < MIN_SAMPLE_BYTES) return RejectReason.TOO_SMALL;
        if (blockedReadNanos <= 0 || wallNanos < MIN_SAMPLE_NANOS
                || blockedReadNanos > wallNanos) {
            return RejectReason.INVALID_TIMING;
        }
        long idle = wallNanos - blockedReadNanos;
        long allowedIdle = Math.max(IDLE_GRACE_NANOS, wallNanos / 2L);
        return idle > allowedIdle
                ? RejectReason.DOWNSTREAM_BACKPRESSURE : RejectReason.NONE;
    }

    private static long bitsPerSecond(long bytes, long nanos) {
        if (bytes <= 0 || nanos <= 0) return 0;
        double value = bytes * 8d * TimeUnit.SECONDS.toNanos(1) / nanos;
        if (!Double.isFinite(value) || value <= 0) return 0;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value);
    }

    private static long weightedRecovery(long current, long sample) {
        long currentPart = current - current / 4L;
        long samplePart = sample / 4L;
        return currentPart > Long.MAX_VALUE - samplePart
                ? Long.MAX_VALUE : currentPart + samplePart;
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    record Snapshot(
            long bitsPerSecond,
            boolean known,
            boolean fresh,
            long sampledAtElapsedMs,
            long ageMs,
            int acceptedSamples,
            int rejectedSamples,
            RejectReason lastRejectReason) {

        Snapshot {
            bitsPerSecond = Math.max(0, bitsPerSecond);
            acceptedSamples = Math.max(0, acceptedSamples);
            rejectedSamples = Math.max(0, rejectedSamples);
            lastRejectReason = lastRejectReason == null
                    ? RejectReason.NONE : lastRejectReason;
        }
    }

    enum RejectReason {
        NONE("none"),
        INCOMPLETE("incomplete"),
        TOO_SMALL("too-small"),
        INVALID_TIMING("invalid-timing"),
        DOWNSTREAM_BACKPRESSURE("downstream-backpressure"),
        INVALID_RATE("invalid-rate");

        private final String label;

        RejectReason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
