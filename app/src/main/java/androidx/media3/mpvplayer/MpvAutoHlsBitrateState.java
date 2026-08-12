package androidx.media3.mpvplayer;

/** Keeps the automatic HLS bitrate option safe across native-context changes. */
final class MpvAutoHlsBitrateState {

    private String stagedOption = "";
    private String acceptedOption = "";
    private long observedBitsPerSecond = -1;
    private int observedCount;

    synchronized boolean stage(
            boolean performanceOptionsPriority,
            boolean automaticHlsVariant,
            String option) {
        if (!performanceOptionsPriority
                || !automaticHlsVariant
                || !validOption(option)) {
            clear();
            return false;
        }
        stagedOption = option.trim();
        return true;
    }

    synchronized void recordAccepted(String option) {
        if (option != null && option.trim().equals(stagedOption)) {
            acceptedOption = option.trim();
        }
    }

    synchronized boolean recordObserved(String property, Object value) {
        if (acceptedOption.isEmpty()
                || !"hls-bitrate".equals(property)
                || !(value instanceof Number number)) {
            return false;
        }
        long observed = Math.max(0, number.longValue());
        boolean first = observedCount == 0;
        observedBitsPerSecond = observed;
        if (observedCount < Integer.MAX_VALUE) observedCount++;
        return first;
    }

    synchronized void clear() {
        stagedOption = "";
        acceptedOption = "";
        observedBitsPerSecond = -1;
        observedCount = 0;
    }

    synchronized void onNativeContextReleased() {
        acceptedOption = "";
        observedBitsPerSecond = -1;
        observedCount = 0;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(stagedOption, acceptedOption,
                observedBitsPerSecond, observedCount);
    }

    private static boolean validOption(String option) {
        if (option == null || option.isBlank()) return false;
        String value = option.trim();
        if ("min".equals(value) || "max".equals(value) || "no".equals(value)) {
            return true;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 && parsed <= Integer.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    record Snapshot(
            String stagedOption,
            String acceptedOption,
            long observedBitsPerSecond,
            int observedCount) {

        Snapshot {
            stagedOption = stagedOption == null ? "" : stagedOption;
            acceptedOption = acceptedOption == null ? "" : acceptedOption;
            observedCount = Math.max(0, observedCount);
        }

        boolean staged() {
            return !stagedOption.isEmpty();
        }
    }
}
