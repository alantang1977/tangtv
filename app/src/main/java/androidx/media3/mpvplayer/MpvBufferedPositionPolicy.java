package androidx.media3.mpvplayer;

/** Resolves a monotonic, range-checked MPV buffer endpoint. */
final class MpvBufferedPositionPolicy {

    private MpvBufferedPositionPolicy() {
    }

    static long resolve(
            long positionMs,
            long durationMs,
            long cacheDurationMs,
            long cacheEndMs,
            boolean local,
            boolean iso) {
        long position = Math.max(0, positionMs);
        if (durationMs <= 0) return position;
        long duration = Math.max(position, durationMs);
        long buffered = position;
        if (cacheDurationMs > 0) {
            buffered = Math.max(buffered,
                    Math.min(duration, saturatedAdd(position, cacheDurationMs)));
        }
        if (cacheEndMs >= position && cacheEndMs <= duration) {
            buffered = Math.max(buffered, cacheEndMs);
        }
        if (buffered > position) return buffered;
        if (iso) return position;
        return local ? duration : position;
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment <= 0) return value;
        return value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE : value + increment;
    }
}
