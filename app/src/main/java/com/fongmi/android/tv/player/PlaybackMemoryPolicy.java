package com.fongmi.android.tv.player;

/** Pure policy for converting observable Android memory signals into conservative facts. */
public final class PlaybackMemoryPolicy {

    static final int API_RUNNING_TRIM_DEPRECATED = 34;
    static final int TRIM_MEMORY_RUNNING_MODERATE = 5;
    static final int TRIM_MEMORY_RUNNING_LOW = 10;
    static final int TRIM_MEMORY_RUNNING_CRITICAL = 15;
    static final long SNAPSHOT_TTL_MS = 60_000;
    static final long PRESSURE_TTL_MS = 60_000;
    static final long PSS_TTL_MS = 10 * 60_000;

    private PlaybackMemoryPolicy() {
    }

    public static Result evaluate(PlaybackAutoContext.MemorySnapshot snapshot, int sdkInt) {
        if (snapshot == null) return Result.unknown();
        if (snapshot.trigger() == PlaybackAutoContext.MemoryTrigger.LOW_MEMORY) {
            return new Result(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.LOW_MEMORY_CALLBACK, true);
        }
        if (Boolean.TRUE.equals(snapshot.systemLowMemory())) {
            return new Result(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_LOW_MEMORY, false);
        }
        if (atOrBelowThreshold(snapshot.systemAvailableBytes(), snapshot.systemThresholdBytes())) {
            return new Result(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_THRESHOLD, false);
        }
        if (Long.valueOf(0).equals(snapshot.javaHeapHeadroomBytes())) {
            return new Result(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.JAVA_HEAP_EXHAUSTED, false);
        }
        if (sdkInt < API_RUNNING_TRIM_DEPRECATED && snapshot.lastTrimLevel() != null) {
            return switch (snapshot.lastTrimLevel()) {
                case TRIM_MEMORY_RUNNING_CRITICAL -> new Result(PlaybackAutoContext.MemoryPressure.CRITICAL,
                        PlaybackAutoContext.Confidence.MEDIUM, Reason.LEGACY_RUNNING_CRITICAL, true);
                case TRIM_MEMORY_RUNNING_LOW -> new Result(PlaybackAutoContext.MemoryPressure.MODERATE,
                        PlaybackAutoContext.Confidence.MEDIUM, Reason.LEGACY_RUNNING_LOW, true);
                case TRIM_MEMORY_RUNNING_MODERATE -> new Result(PlaybackAutoContext.MemoryPressure.MODERATE,
                        PlaybackAutoContext.Confidence.LOW, Reason.LEGACY_RUNNING_MODERATE, true);
                default -> normalOrUnknown(snapshot);
            };
        }
        return normalOrUnknown(snapshot);
    }

    public static PlaybackAutoContext.Confidence snapshotConfidence(PlaybackAutoContext.MemorySnapshot snapshot) {
        if (snapshot == null || !snapshot.hasEvidence()) return PlaybackAutoContext.Confidence.UNKNOWN;
        if (snapshot.hasJavaMetrics() && snapshot.hasSystemMetrics()) return PlaybackAutoContext.Confidence.HIGH;
        return PlaybackAutoContext.Confidence.MEDIUM;
    }

    private static Result normalOrUnknown(PlaybackAutoContext.MemorySnapshot snapshot) {
        if (Boolean.FALSE.equals(snapshot.systemLowMemory())) {
            return new Result(PlaybackAutoContext.MemoryPressure.NORMAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_NORMAL, false);
        }
        Long available = snapshot.systemAvailableBytes();
        Long threshold = snapshot.systemThresholdBytes();
        if (available != null && threshold != null && available > threshold) {
            return new Result(PlaybackAutoContext.MemoryPressure.NORMAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_NORMAL, false);
        }
        return Result.unknown();
    }

    private static boolean atOrBelowThreshold(Long available, Long threshold) {
        return available != null && threshold != null && available <= threshold;
    }

    public record Result(
            PlaybackAutoContext.MemoryPressure pressure,
            PlaybackAutoContext.Confidence confidence,
            Reason reason,
            boolean callbackEvidence) {

        public Result {
            pressure = pressure == null ? PlaybackAutoContext.MemoryPressure.UNKNOWN : pressure;
            confidence = confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            reason = reason == null ? Reason.UNKNOWN : reason;
        }

        public static Result unknown() {
            return new Result(PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN, Reason.UNKNOWN, false);
        }

        public boolean known() {
            return pressure != PlaybackAutoContext.MemoryPressure.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN;
        }

        public PlaybackAutoContext.ValueSource source() {
            return callbackEvidence ? PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK
                    : PlaybackAutoContext.ValueSource.SYSTEM_API;
        }
    }

    public enum Reason {
        LOW_MEMORY_CALLBACK("low-memory-callback"),
        SYSTEM_LOW_MEMORY("system-low-memory"),
        SYSTEM_THRESHOLD("system-threshold"),
        JAVA_HEAP_EXHAUSTED("java-heap-exhausted"),
        LEGACY_RUNNING_CRITICAL("legacy-running-critical"),
        LEGACY_RUNNING_LOW("legacy-running-low"),
        LEGACY_RUNNING_MODERATE("legacy-running-moderate"),
        SYSTEM_NORMAL("system-normal"),
        UNKNOWN("unknown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
