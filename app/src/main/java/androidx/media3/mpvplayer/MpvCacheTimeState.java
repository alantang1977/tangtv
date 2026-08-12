package androidx.media3.mpvplayer;

/** Tracks requested and native-observed MPV cache time options without querying synchronously. */
final class MpvCacheTimeState {

    private static final int CACHE_SECONDS_BIT = 1;
    private static final int READAHEAD_SECONDS_BIT = 1 << 1;
    private static final int HYSTERESIS_SECONDS_BIT = 1 << 2;

    private MpvCacheTimePolicy.Decision decision;
    private int cacheSeconds;
    private int readaheadSeconds;
    private int hysteresisSeconds;
    private int observedMask;

    MpvCacheTimeState(
            MpvCacheTimePolicy.Decision decision,
            int cacheSeconds,
            int readaheadSeconds,
            int hysteresisSeconds) {
        reset(decision, cacheSeconds, readaheadSeconds, hysteresisSeconds);
    }

    void reset(
            MpvCacheTimePolicy.Decision nextDecision,
            int nextCacheSeconds,
            int nextReadaheadSeconds,
            int nextHysteresisSeconds) {
        decision = nextDecision;
        cacheSeconds = nonNegative(nextCacheSeconds);
        readaheadSeconds = nonNegative(nextReadaheadSeconds);
        hysteresisSeconds = nonNegative(nextHysteresisSeconds);
        observedMask = 0;
    }

    void select(MpvCacheTimePolicy.Decision nextDecision) {
        if (nextDecision != null) decision = nextDecision;
    }

    boolean recordAccepted(String property, String value) {
        Integer seconds = parseSeconds(value);
        if (seconds == null) return false;
        int bit = update(property, seconds);
        if (bit == 0) return false;
        observedMask &= ~bit;
        return true;
    }

    boolean recordObserved(String property, Object value) {
        Integer seconds = parseSeconds(value);
        if (seconds == null) return false;
        int bit = update(property, seconds);
        if (bit == 0) return false;
        boolean firstReadback = (observedMask & bit) == 0;
        observedMask |= bit;
        return firstReadback;
    }

    Snapshot snapshot() {
        MpvCacheTimePolicy.Decision current = decision;
        return new Snapshot(
                current == null ? MpvCacheTimePolicy.Master.MPV_CONF : current.master(),
                current == null ? MpvCacheTimePolicy.Reason.UNKNOWN_SOURCE : current.reason(),
                cacheSeconds,
                readaheadSeconds,
                hysteresisSeconds,
                Integer.bitCount(observedMask));
    }

    private int update(String property, int seconds) {
        return switch (property == null ? "" : property) {
            case "cache-secs" -> {
                cacheSeconds = seconds;
                yield CACHE_SECONDS_BIT;
            }
            case "demuxer-readahead-secs" -> {
                readaheadSeconds = seconds;
                yield READAHEAD_SECONDS_BIT;
            }
            case "demuxer-hysteresis-secs" -> {
                hysteresisSeconds = seconds;
                yield HYSTERESIS_SECONDS_BIT;
            }
            default -> 0;
        };
    }

    private static Integer parseSeconds(Object value) {
        if (value instanceof Number number) {
            double seconds = number.doubleValue();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > Integer.MAX_VALUE) return null;
            return (int) Math.round(seconds);
        }
        if (!(value instanceof String text)) return null;
        try {
            double seconds = Double.parseDouble(text.trim());
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > Integer.MAX_VALUE) return null;
            return (int) Math.round(seconds);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    record Snapshot(
            MpvCacheTimePolicy.Master master,
            MpvCacheTimePolicy.Reason reason,
            int cacheSeconds,
            int readaheadSeconds,
            int hysteresisSeconds,
            int observedOptions) {
    }
}
