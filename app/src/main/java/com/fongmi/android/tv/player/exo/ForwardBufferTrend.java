package com.fongmi.android.tv.player.exo;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ForwardBufferTrend {

    private static final long MIN_SAMPLE_INTERVAL_MS = 1_000;
    static final long MAX_IDLE_RETENTION_MS = 5_000;
    private static final long MIN_WINDOW_MS = 5_000;
    private static final long MEDIUM_WINDOW_MS = 15_000;
    private static final long HIGH_WINDOW_MS = 30_000;
    private static final long MAX_WINDOW_MS = 45_000;
    private static final long FAST_HALF_LIFE_MS = 6_000;
    private static final long SLOW_HALF_LIFE_MS = 20_000;
    private static final long MIN_SLOPE_MS_PER_SECOND = -2_000;
    private static final long MAX_SLOPE_MS_PER_SECOND = 5_000;

    private final Deque<Sample> samples = new ArrayDeque<>();
    private double fastSlope;
    private double slowSlope;
    private boolean slopeInitialized;
    private long idleSinceMs = -1;

    public synchronized void reset() {
        samples.clear();
        fastSlope = 0;
        slowSlope = 0;
        slopeInitialized = false;
        idleSinceMs = -1;
    }

    public synchronized void observe(long nowMs, long bufferedMs, boolean stablePlayback) {
        observe(nowMs, bufferedMs, stablePlayback, stablePlayback);
    }

    public synchronized void observe(
            long nowMs,
            long bufferedMs,
            boolean activePlayback,
            boolean loading) {
        if (!activePlayback || bufferedMs < 0) {
            reset();
            return;
        }
        if (!loading) {
            if (idleSinceMs < 0) idleSinceMs = nowMs;
            if (nowMs < idleSinceMs
                    || nowMs - idleSinceMs > MAX_IDLE_RETENTION_MS) {
                reset();
            }
            return;
        }
        boolean resumedAfterIdle = idleSinceMs >= 0;
        if (resumedAfterIdle) {
            if (nowMs < idleSinceMs
                    || nowMs - idleSinceMs > MAX_IDLE_RETENTION_MS) {
                reset();
                resumedAfterIdle = false;
            }
            idleSinceMs = -1;
        }
        Sample last = samples.peekLast();
        if (last != null && !resumedAfterIdle
                && nowMs - last.nowMs() < MIN_SAMPLE_INTERVAL_MS) return;
        if (last != null && !resumedAfterIdle) {
            updateSlope(nowMs - last.nowMs(), bufferedMs - last.bufferedMs());
        }
        samples.addLast(new Sample(nowMs, bufferedMs));
        while (samples.size() > 2 && nowMs - samples.peekFirst().nowMs() > MAX_WINDOW_MS) samples.removeFirst();
    }

    public synchronized Snapshot snapshot() {
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null) return Snapshot.unknown();
        long windowMs = last.nowMs() - first.nowMs();
        if (windowMs < MIN_WINDOW_MS || !slopeInitialized) {
            return new Snapshot(
                    0,
                    0,
                    0,
                    windowMs,
                    samples.size(),
                    Confidence.UNKNOWN,
                    last.bufferedMs(),
                    last.nowMs());
        }
        long fast = Math.round(fastSlope);
        long slow = Math.round(slowSlope);
        long slope = Math.min(fast, slow);
        Confidence confidence = windowMs >= HIGH_WINDOW_MS ? Confidence.HIGH : windowMs >= MEDIUM_WINDOW_MS ? Confidence.MEDIUM : Confidence.LOW;
        return new Snapshot(
                slope,
                fast,
                slow,
                windowMs,
                samples.size(),
                confidence,
                last.bufferedMs(),
                last.nowMs());
    }

    private void updateSlope(long elapsedMs, long bufferDeltaMs) {
        if (elapsedMs <= 0) return;
        double sample = Math.min(Math.max(bufferDeltaMs * 1_000d / elapsedMs, MIN_SLOPE_MS_PER_SECOND), MAX_SLOPE_MS_PER_SECOND);
        if (!slopeInitialized) {
            fastSlope = sample;
            slowSlope = sample;
            slopeInitialized = true;
            return;
        }
        fastSlope = ewma(fastSlope, sample, elapsedMs, FAST_HALF_LIFE_MS);
        slowSlope = ewma(slowSlope, sample, elapsedMs, SLOW_HALF_LIFE_MS);
    }

    private static double ewma(double current, double sample, long elapsedMs, long halfLifeMs) {
        double alpha = 1d - Math.exp(-Math.log(2d) * elapsedMs / halfLifeMs);
        return current + alpha * (sample - current);
    }

    public enum Confidence {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        UNKNOWN("unknown");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Snapshot(
            long slopeMsPerSecond,
            long fastSlopeMsPerSecond,
            long slowSlopeMsPerSecond,
            long windowMs,
            int sampleCount,
            Confidence confidence,
            long lastBufferedMs,
            long sampledAtElapsedMs) {

        public Snapshot(
                long slopeMsPerSecond,
                long fastSlopeMsPerSecond,
                long slowSlopeMsPerSecond,
                long windowMs,
                int sampleCount,
                Confidence confidence) {
            this(
                    slopeMsPerSecond,
                    fastSlopeMsPerSecond,
                    slowSlopeMsPerSecond,
                    windowMs,
                    sampleCount,
                    confidence,
                    -1,
                    -1);
        }

        public Snapshot(long slopeMsPerSecond, long windowMs, int sampleCount, Confidence confidence) {
            this(
                    slopeMsPerSecond,
                    slopeMsPerSecond,
                    slopeMsPerSecond,
                    windowMs,
                    sampleCount,
                    confidence,
                    -1,
                    -1);
        }

        static Snapshot unknown() {
            return new Snapshot(0, 0, 0, 0, 0, Confidence.UNKNOWN, -1, -1);
        }

        public boolean known() {
            return confidence != Confidence.UNKNOWN;
        }

        public long timeToEmptyMs() {
            if (!known() || lastBufferedMs < 0 || slopeMsPerSecond >= 0) return -1;
            long drainPerSecond = -slopeMsPerSecond;
            if (lastBufferedMs > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
            return lastBufferedMs * 1_000L / drainPerSecond;
        }
    }

    private record Sample(long nowMs, long bufferedMs) {
    }
}
