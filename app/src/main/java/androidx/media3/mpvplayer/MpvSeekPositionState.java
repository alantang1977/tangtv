package androidx.media3.mpvplayer;

/** Keeps stale native time-pos callbacks from overriding a newly requested seek. */
final class MpvSeekPositionState {

    static final long NO_TARGET = -1;
    static final long TARGET_TOLERANCE_MS = 1_500;
    static final long TARGET_TIMEOUT_MS = 15_000;

    private long targetPositionMs = NO_TARGET;
    private long requestedAtMs;

    void begin(long targetPositionMs, long nowMs) {
        this.targetPositionMs = Math.max(0, targetPositionMs);
        requestedAtMs = Math.max(0, nowMs);
    }

    long resolve(long observedPositionMs, long nowMs) {
        long observed = Math.max(0, observedPositionMs);
        if (!hasTarget()) return observed;
        if (withinTarget(observed)
                || elapsed(nowMs, requestedAtMs, TARGET_TIMEOUT_MS)) {
            clear();
            return observed;
        }
        return targetPositionMs;
    }

    boolean hasTarget() {
        return targetPositionMs != NO_TARGET;
    }

    long targetPositionMs() {
        return targetPositionMs;
    }

    void clear() {
        targetPositionMs = NO_TARGET;
        requestedAtMs = 0;
    }

    private boolean withinTarget(long observedPositionMs) {
        long delta = observedPositionMs >= targetPositionMs
                ? observedPositionMs - targetPositionMs
                : targetPositionMs - observedPositionMs;
        return delta <= TARGET_TOLERANCE_MS;
    }

    private static boolean elapsed(long nowMs, long sinceMs, long thresholdMs) {
        return nowMs >= sinceMs && nowMs - sinceMs >= thresholdMs;
    }
}
