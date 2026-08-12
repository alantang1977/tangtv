package androidx.media3.mpvplayer;

import java.util.Collections;
import java.util.Map;

/** Keeps an automatic cache baseline safe across native-context creation and replacement. */
final class MpvAutoCacheBaselineState {

    private Map<String, String> options = Collections.emptyMap();

    synchronized boolean stage(
            boolean performanceOptionsPriority,
            long forwardBytes,
            long backBytes) {
        Map<String, String> resolved = MpvAutoCacheOptionPolicy.resolve(
                performanceOptionsPriority, forwardBytes, backBytes);
        if (resolved.isEmpty()) {
            options = Collections.emptyMap();
            return false;
        }
        options = resolved;
        return true;
    }

    synchronized void clear() {
        options = Collections.emptyMap();
    }

    synchronized Map<String, String> snapshot() {
        return options;
    }
}
