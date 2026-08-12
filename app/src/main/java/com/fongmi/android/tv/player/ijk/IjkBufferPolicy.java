package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure automatic policy for IJK's finite demux queue and buffering watermarks. */
public final class IjkBufferPolicy {

    public static final int MIB = 1024 * 1024;
    public static final int LOW_BUFFER_MB = 4;
    public static final int BALANCED_BUFFER_MB = 8;
    public static final int HIGH_BUFFER_MB = 15;

    private static final long SMALL_HEAP_LIMIT_BYTES = 192L * MIB;
    private static final long LOW_JAVA_HEADROOM_BYTES = 64L * MIB;
    private static final long LOW_SYSTEM_SURPLUS_BYTES = 64L * MIB;
    private static final long BALANCED_SYSTEM_SURPLUS_BYTES = 256L * MIB;
    private static final int MIN_WATER_MS = 100;
    private static final int MAX_WATER_MS = 5_000;

    private IjkBufferPolicy() {
    }

    public static Config safeInitialConfig() {
        return new Config(BALANCED_BUFFER_MB, 100, 1_000, 3_000);
    }

    public static Decision resolve(Request request) {
        Request input = request == null ? Request.inactive() : request;
        if (!input.automatic() || !input.ijk()) {
            return new Decision(false, safeInitialConfig(), Reason.NOT_AUTOMATIC_IJK,
                    BALANCED_BUFFER_MB, false, -1, 0);
        }

        MemoryLimit memory = memoryLimit(input);
        Scene scene = scene(input);
        Watermarks watermarks = watermarks(input, scene);
        long targetOffsetMs = targetOffsetMs(input, scene);
        boolean lagHigh = isLagHigh(input, scene, targetOffsetMs);
        if (lagHigh) watermarks = new Watermarks(100, 300, 1_000);

        int targetMb = scene == Scene.LOW_LATENCY || scene == Scene.REALTIME
                ? LOW_BUFFER_MB : BALANCED_BUFFER_MB;
        long demandBytes = 0;
        if (input.mediaBitrateUsable() && input.mediaBitrateBitsPerSecond() > 0) {
            demandBytes = bytesForDuration(input.mediaBitrateBitsPerSecond(),
                    watermarks.lastMs(), 120);
            targetMb = Math.max(targetMb, tierForBytes(demandBytes));
        }
        if (input.rebufferUsable() && input.rebufferCount() > 0 && !lagHigh) {
            targetMb = nextTier(targetMb);
        }
        if (scene == Scene.UNKNOWN) targetMb = Math.min(targetMb, BALANCED_BUFFER_MB);
        if (lagHigh) targetMb = LOW_BUFFER_MB;
        targetMb = Math.min(targetMb, memory.ceilingMb());

        Config target = new Config(targetMb, watermarks.firstMs(),
                watermarks.nextMs(), watermarks.lastMs());
        Reason reason = reason(input, scene, memory, lagHigh, demandBytes, targetMb);
        return new Decision(true, target, reason, memory.ceilingMb(), lagHigh,
                targetOffsetMs, demandBytes);
    }

    private static Reason reason(
            Request input,
            Scene scene,
            MemoryLimit memory,
            boolean lagHigh,
            long demandBytes,
            int targetMb) {
        if (memory.reason() != Reason.NORMAL_MEMORY) return memory.reason();
        if (lagHigh) return Reason.LIVE_LAG_HIGH;
        if (input.rebufferUsable() && input.rebufferCount() > 0
                && targetMb > LOW_BUFFER_MB) return Reason.REBUFFER_HEADROOM;
        if (demandBytes > (long) BALANCED_BUFFER_MB * MIB
                && targetMb == HIGH_BUFFER_MB) return Reason.MEDIA_DEMAND;
        return switch (scene) {
            case VOD -> Reason.VOD_BASELINE;
            case LIVE -> Reason.LIVE_CADENCE;
            case LOW_LATENCY -> Reason.LOW_LATENCY_CADENCE;
            case REALTIME -> Reason.REALTIME_BASELINE;
            case UNKNOWN -> Reason.SCENE_UNKNOWN;
        };
    }

    private static MemoryLimit memoryLimit(Request input) {
        PlaybackAutoContext.MemorySnapshot snapshot = input.memorySnapshot();
        if (input.pressureUsable()
                && input.memoryPressure() == PlaybackAutoContext.MemoryPressure.CRITICAL) {
            return new MemoryLimit(LOW_BUFFER_MB, Reason.CRITICAL_MEMORY);
        }
        if (input.snapshotUsable() && systemLow(snapshot)) {
            return new MemoryLimit(LOW_BUFFER_MB, Reason.SYSTEM_LOW_MEMORY);
        }
        if (input.snapshotUsable() && Boolean.TRUE.equals(snapshot.lowRamDevice())) {
            return new MemoryLimit(LOW_BUFFER_MB, Reason.LOW_RAM_DEVICE);
        }
        if (input.snapshotUsable() && snapshot.javaHeapLimitBytes() != null
                && snapshot.javaHeapLimitBytes() <= SMALL_HEAP_LIMIT_BYTES) {
            return new MemoryLimit(LOW_BUFFER_MB, Reason.SMALL_HEAP);
        }
        if (input.snapshotUsable() && snapshot.javaHeapHeadroomBytes() != null
                && snapshot.javaHeapHeadroomBytes() <= LOW_JAVA_HEADROOM_BYTES) {
            return new MemoryLimit(LOW_BUFFER_MB, Reason.LOW_JAVA_HEADROOM);
        }
        long systemSurplus = systemSurplus(snapshot);
        if (input.snapshotUsable() && systemSurplus >= 0
                && systemSurplus <= LOW_SYSTEM_SURPLUS_BYTES) {
            return new MemoryLimit(LOW_BUFFER_MB, Reason.LOW_SYSTEM_SURPLUS);
        }
        if (input.pressureUsable()
                && input.memoryPressure() == PlaybackAutoContext.MemoryPressure.MODERATE) {
            return new MemoryLimit(BALANCED_BUFFER_MB, Reason.MODERATE_MEMORY);
        }
        if (!input.snapshotUsable() || !input.pressureUsable()
                || input.memoryPressure() != PlaybackAutoContext.MemoryPressure.NORMAL) {
            return new MemoryLimit(BALANCED_BUFFER_MB, Reason.MEMORY_UNKNOWN);
        }
        if (!snapshot.hasJavaMetrics() || !snapshot.hasSystemMetrics()
                || snapshot.lowRamDevice() == null) {
            return new MemoryLimit(BALANCED_BUFFER_MB, Reason.MEMORY_UNKNOWN);
        }
        if (systemSurplus >= 0 && systemSurplus < BALANCED_SYSTEM_SURPLUS_BYTES) {
            return new MemoryLimit(BALANCED_BUFFER_MB, Reason.BALANCED_SYSTEM_SURPLUS);
        }
        return new MemoryLimit(HIGH_BUFFER_MB, Reason.NORMAL_MEMORY);
    }

    private static Scene scene(Request input) {
        if (input.protocolUsable()
                && (input.protocol() == PlaybackAutoContext.Protocol.RTSP
                || input.protocol() == PlaybackAutoContext.Protocol.RTMP)) {
            return Scene.REALTIME;
        }
        if (!input.streamKindUsable()) return Scene.UNKNOWN;
        return switch (input.streamKind()) {
            case VOD -> Scene.VOD;
            case LIVE -> Scene.LIVE;
            case LOW_LATENCY_LIVE -> Scene.LOW_LATENCY;
            case UNKNOWN -> Scene.UNKNOWN;
        };
    }

    private static Watermarks watermarks(Request input, Scene scene) {
        PlaybackAutoContext.ManifestFacts manifest = input.manifestFacts();
        if (scene == Scene.LOW_LATENCY) {
            long partMs = positive(manifest.partDurationMs(),
                    dividePositive(manifest.targetDurationMs(), 6), 333);
            long offsetMs = positive(manifest.holdBackMs(), multiplySaturated(partMs, 3), 1_000);
            int first = clampWater(partMs / 2, 100, 250);
            int next = clampWater(multiplySaturated(partMs, 2), 300, 1_000);
            int last = clampWater(offsetMs, Math.max(next, 600), 1_500);
            if (input.rebufferUsable() && input.rebufferCount() > 0) {
                last = clampWater(Math.max(last, multiplySaturated(partMs, 4)), next, 2_000);
            }
            return new Watermarks(first, next, last);
        }
        if (scene == Scene.LIVE) {
            long targetMs = positive(manifest.targetDurationMs(), 2_000);
            int first = clampWater(targetMs / 12, 100, 500);
            int next = clampWater(targetMs / 4, 500, 2_000);
            int last = clampWater(targetMs / 2, Math.max(next, 1_000), 5_000);
            if (input.rebufferUsable() && input.rebufferCount() > 0) {
                next = clampWater(Math.max(next, targetMs / 3), first, 2_500);
                last = clampWater(Math.max(last, targetMs), next, 5_000);
            }
            return new Watermarks(first, next, last);
        }
        if (scene == Scene.REALTIME) {
            return input.rebufferUsable() && input.rebufferCount() > 0
                    ? new Watermarks(100, 500, 1_500)
                    : new Watermarks(100, 300, 1_000);
        }
        if (scene == Scene.VOD) return new Watermarks(100, 1_000, 5_000);
        return new Watermarks(100, 1_000, 3_000);
    }

    private static long targetOffsetMs(Request input, Scene scene) {
        PlaybackAutoContext.ManifestFacts manifest = input.manifestFacts();
        if (scene == Scene.LOW_LATENCY) {
            long partMs = positive(manifest.partDurationMs(),
                    dividePositive(manifest.targetDurationMs(), 6), 333);
            return positive(manifest.holdBackMs(), multiplySaturated(partMs, 3), -1);
        }
        if (scene == Scene.LIVE) {
            long targetMs = positive(manifest.targetDurationMs(), -1);
            return positive(manifest.holdBackMs(), multiplySaturated(targetMs, 3), -1);
        }
        return -1;
    }

    private static boolean isLagHigh(Request input, Scene scene, long targetOffsetMs) {
        if (!input.liveLagUsable() || input.liveLagMs() < 0 || targetOffsetMs <= 0
                || scene != Scene.LIVE && scene != Scene.LOW_LATENCY) return false;
        long cadence = scene == Scene.LOW_LATENCY
                ? positive(input.manifestFacts().partDurationMs(), 333)
                : positive(input.manifestFacts().targetDurationMs(), 2_000);
        long tolerance = Math.max(1_000, cadence);
        long limit = targetOffsetMs > Long.MAX_VALUE - tolerance
                ? Long.MAX_VALUE : targetOffsetMs + tolerance;
        return input.liveLagMs() > limit;
    }

    private static boolean systemLow(PlaybackAutoContext.MemorySnapshot snapshot) {
        if (Boolean.TRUE.equals(snapshot.systemLowMemory())) return true;
        Long available = snapshot.systemAvailableBytes();
        Long threshold = snapshot.systemThresholdBytes();
        return available != null && threshold != null && available <= threshold;
    }

    private static long systemSurplus(PlaybackAutoContext.MemorySnapshot snapshot) {
        Long available = snapshot.systemAvailableBytes();
        Long threshold = snapshot.systemThresholdBytes();
        if (available == null || threshold == null) return -1;
        return Math.max(0, available - threshold);
    }

    private static long bytesForDuration(long bitsPerSecond, long durationMs, int percent) {
        if (bitsPerSecond <= 0 || durationMs <= 0 || percent <= 0) return 0;
        long bytes = multiplyDivideSaturated(bitsPerSecond, durationMs, 8_000);
        return multiplyDivideSaturated(bytes, percent, 100);
    }

    private static int tierForBytes(long bytes) {
        if (bytes <= (long) LOW_BUFFER_MB * MIB) return LOW_BUFFER_MB;
        if (bytes <= (long) BALANCED_BUFFER_MB * MIB) return BALANCED_BUFFER_MB;
        return HIGH_BUFFER_MB;
    }

    private static int nextTier(int mb) {
        return mb <= LOW_BUFFER_MB ? BALANCED_BUFFER_MB : HIGH_BUFFER_MB;
    }

    private static long positive(Long first, long second, long fallback) {
        if (first != null && first > 0) return first;
        if (second > 0) return second;
        return fallback;
    }

    private static long positive(Long first, long fallback) {
        return first != null && first > 0 ? first : fallback;
    }

    private static long dividePositive(Long value, long divisor) {
        return value == null || value <= 0 || divisor <= 0 ? -1 : value / divisor;
    }

    private static long multiplySaturated(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) return -1;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long multiplyDivideSaturated(long value, long multiplier, long divisor) {
        if (value <= 0 || multiplier <= 0 || divisor <= 0) return 0;
        long whole = value / divisor;
        long remainder = value % divisor;
        long first = whole > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : whole * multiplier;
        long second = remainder > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : remainder * multiplier / divisor;
        return first >= Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static int clampWater(long value, int min, int max) {
        long safe = Math.max(min, Math.min(max, value));
        return (int) Math.max(MIN_WATER_MS, Math.min(MAX_WATER_MS, safe));
    }

    private enum Scene {
        VOD,
        LIVE,
        LOW_LATENCY,
        REALTIME,
        UNKNOWN
    }

    private record MemoryLimit(int ceilingMb, Reason reason) {
    }

    private record Watermarks(int firstMs, int nextMs, int lastMs) {
    }

    public enum Reason {
        NOT_AUTOMATIC_IJK("not-automatic-ijk"),
        CRITICAL_MEMORY("critical-memory"),
        SYSTEM_LOW_MEMORY("system-low-memory"),
        LOW_RAM_DEVICE("low-ram-device"),
        SMALL_HEAP("small-heap"),
        LOW_JAVA_HEADROOM("low-java-headroom"),
        LOW_SYSTEM_SURPLUS("low-system-surplus"),
        MODERATE_MEMORY("moderate-memory"),
        MEMORY_UNKNOWN("memory-unknown"),
        BALANCED_SYSTEM_SURPLUS("balanced-system-surplus"),
        NORMAL_MEMORY("normal-memory"),
        LIVE_LAG_HIGH("live-lag-high"),
        REBUFFER_HEADROOM("rebuffer-headroom"),
        MEDIA_DEMAND("media-demand"),
        VOD_BASELINE("vod-baseline"),
        LIVE_CADENCE("live-cadence"),
        LOW_LATENCY_CADENCE("low-latency-cadence"),
        REALTIME_BASELINE("realtime-baseline"),
        SCENE_UNKNOWN("scene-unknown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Config(
            int bufferMb,
            int firstWaterMs,
            int nextWaterMs,
            int lastWaterMs) {

        public Config {
            bufferMb = bufferMb <= LOW_BUFFER_MB ? LOW_BUFFER_MB
                    : bufferMb <= BALANCED_BUFFER_MB ? BALANCED_BUFFER_MB
                    : HIGH_BUFFER_MB;
            firstWaterMs = clampWater(firstWaterMs, MIN_WATER_MS, MAX_WATER_MS);
            nextWaterMs = clampWater(nextWaterMs, firstWaterMs, MAX_WATER_MS);
            lastWaterMs = clampWater(lastWaterMs, nextWaterMs, MAX_WATER_MS);
        }

        public long maxBufferBytes() {
            return bufferMb * (long) MIB;
        }

        public String label() {
            return bufferMb + "m-" + firstWaterMs + "-" + nextWaterMs
                    + "-" + lastWaterMs;
        }
    }

    public record Request(
            boolean automatic,
            boolean ijk,
            boolean protocolUsable,
            PlaybackAutoContext.Protocol protocol,
            boolean streamKindUsable,
            PlaybackAutoContext.StreamKind streamKind,
            boolean manifestUsable,
            PlaybackAutoContext.ManifestFacts manifestFacts,
            boolean pressureUsable,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean snapshotUsable,
            PlaybackAutoContext.MemorySnapshot memorySnapshot,
            boolean mediaBitrateUsable,
            long mediaBitrateBitsPerSecond,
            boolean rebufferUsable,
            int rebufferCount,
            boolean liveLagUsable,
            long liveLagMs) {

        public Request {
            protocol = protocol == null ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            manifestFacts = manifestUsable && manifestFacts != null
                    ? manifestFacts : PlaybackAutoContext.ManifestFacts.unknown();
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
            memorySnapshot = snapshotUsable && memorySnapshot != null
                    ? memorySnapshot : PlaybackAutoContext.MemorySnapshot.unknown();
            mediaBitrateBitsPerSecond = Math.max(0, mediaBitrateBitsPerSecond);
            rebufferCount = Math.max(0, rebufferCount);
            liveLagMs = Math.max(-1, liveLagMs);
        }

        public static Request inactive() {
            return new Request(false, false, false,
                    PlaybackAutoContext.Protocol.UNKNOWN, false,
                    PlaybackAutoContext.StreamKind.UNKNOWN, false,
                    PlaybackAutoContext.ManifestFacts.unknown(), false,
                    PlaybackAutoContext.MemoryPressure.UNKNOWN, false,
                    PlaybackAutoContext.MemorySnapshot.unknown(), false, 0,
                    false, 0, false, -1);
        }
    }

    public record Decision(
            boolean managed,
            Config target,
            Reason reason,
            int memoryCeilingMb,
            boolean liveLagHigh,
            long targetOffsetMs,
            long mediaDemandBytes) {

        public Decision {
            target = target == null ? safeInitialConfig() : target;
            reason = reason == null ? Reason.SCENE_UNKNOWN : reason;
            memoryCeilingMb = memoryCeilingMb <= LOW_BUFFER_MB ? LOW_BUFFER_MB
                    : memoryCeilingMb <= BALANCED_BUFFER_MB
                    ? BALANCED_BUFFER_MB : HIGH_BUFFER_MB;
            targetOffsetMs = Math.max(-1, targetOffsetMs);
            mediaDemandBytes = Math.max(0, mediaDemandBytes);
        }
    }
}
