package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Side-effect-free mapping for MPV cache time limits and the guarded hysteresis experiment. */
final class MpvCacheTimePolicy {

    static final int NATIVE_DEMUXER_READAHEAD_SECONDS = 1;
    static final int MIN_EXPERIMENTAL_HYSTERESIS_SECONDS = 4;
    static final int MAX_EXPERIMENTAL_HYSTERESIS_SECONDS = 6;

    private MpvCacheTimePolicy() {
    }

    static Decision resolve(
            boolean performanceOptionsPriority,
            boolean automatic,
            boolean cacheEnabled,
            int cacheSeconds,
            int demuxerReadaheadSeconds,
            int rebufferMs,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.PathKind playerPath) {
        int cacheTarget = nonNegative(cacheSeconds);
        int readaheadTarget = nonNegative(demuxerReadaheadSeconds);
        int rebufferWait = ceilSeconds(rebufferMs);
        PlaybackAutoContext.Protocol resolvedProtocol = protocol == null
                ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
        PlaybackAutoContext.PathKind resolvedPath = playerPath == null
                ? PlaybackAutoContext.PathKind.UNKNOWN : playerPath;
        if (!performanceOptionsPriority) {
            return new Decision(
                    false,
                    Master.MPV_CONF,
                    Reason.CONFIG_PRIORITY,
                    cacheTarget,
                    readaheadTarget,
                    0,
                    rebufferWait,
                    resolvedProtocol,
                    resolvedPath);
        }
        if (!cacheEnabled) {
            return new Decision(
                    true,
                    Master.DEMUXER_READAHEAD,
                    Reason.CACHE_DISABLED,
                    cacheTarget,
                    readaheadTarget,
                    0,
                    rebufferWait,
                    resolvedProtocol,
                    resolvedPath);
        }
        if (!automatic) {
            return new Decision(
                    true,
                    Master.CACHE_SECONDS,
                    Reason.STATIC_TARGET,
                    cacheTarget,
                    readaheadTarget,
                    0,
                    rebufferWait,
                    resolvedProtocol,
                    resolvedPath);
        }
        if (!supportsExperimentProtocol(resolvedProtocol)) {
            return new Decision(
                    true,
                    Master.CACHE_SECONDS,
                    resolvedProtocol == PlaybackAutoContext.Protocol.UNKNOWN
                            ? Reason.UNKNOWN_SOURCE : Reason.PROTOCOL_GUARD,
                    cacheTarget,
                    readaheadTarget,
                    0,
                    rebufferWait,
                    resolvedProtocol,
                    resolvedPath);
        }
        if (!supportsExperimentPath(resolvedPath)) {
            return new Decision(
                    true,
                    Master.CACHE_SECONDS,
                    resolvedPath == PlaybackAutoContext.PathKind.UNKNOWN
                            ? Reason.UNKNOWN_SOURCE : Reason.PATH_GUARD,
                    cacheTarget,
                    readaheadTarget,
                    0,
                    rebufferWait,
                    resolvedProtocol,
                    resolvedPath);
        }
        int hysteresisSeconds = experimentalHysteresisSeconds(cacheTarget, rebufferWait);
        return new Decision(
                true,
                Master.CACHE_SECONDS,
                hysteresisSeconds > 0
                        ? Reason.DIRECT_REMOTE_EXPERIMENT : Reason.REBUFFER_GUARD,
                cacheTarget,
                readaheadTarget,
                hysteresisSeconds,
                rebufferWait,
                resolvedProtocol,
                resolvedPath);
    }

    static int experimentalHysteresisSeconds(int cacheSeconds, int rebufferWaitSeconds) {
        int cacheTarget = nonNegative(cacheSeconds);
        int rebufferWait = nonNegative(rebufferWaitSeconds);
        if (cacheTarget <= 0) return 0;
        int candidate = Math.min(
                MAX_EXPERIMENTAL_HYSTERESIS_SECONDS,
                Math.max(MIN_EXPERIMENTAL_HYSTERESIS_SECONDS, cacheTarget / 6));
        if (candidate >= cacheTarget || candidate * 2 > cacheTarget) return 0;
        // Restart prefetching with a little more time than the rebuffer resume
        // watermark. High user-selected rebuffer waits therefore disable this experiment.
        return candidate >= rebufferWait + 2 ? candidate : 0;
    }

    private static boolean supportsExperimentProtocol(PlaybackAutoContext.Protocol protocol) {
        // Start with one continuous, directly observable transport. Segment and
        // live playlists have different cadence/latency semantics and remain at 0
        // until device evidence can separate HLS/DASH VOD from live safely.
        return protocol == PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP;
    }

    private static boolean supportsExperimentPath(PlaybackAutoContext.PathKind path) {
        return path == PlaybackAutoContext.PathKind.REMOTE
                || path == PlaybackAutoContext.PathKind.LAN_PRIVATE;
    }

    private static int ceilSeconds(int milliseconds) {
        int value = nonNegative(milliseconds);
        return value == 0 ? 0 : (value + 999) / 1000;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    enum Master {
        CACHE_SECONDS("cache-secs"),
        DEMUXER_READAHEAD("demuxer-readahead-secs"),
        MPV_CONF("mpv.conf");

        private final String label;

        Master(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Reason {
        CONFIG_PRIORITY("config-priority"),
        CACHE_DISABLED("cache-disabled"),
        STATIC_TARGET("static-target"),
        UNKNOWN_SOURCE("unknown-source"),
        PROTOCOL_GUARD("protocol-guard"),
        PATH_GUARD("path-guard"),
        REBUFFER_GUARD("rebuffer-guard"),
        DIRECT_REMOTE_EXPERIMENT("direct-remote-experiment");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Decision(
            boolean runtimeManaged,
            Master master,
            Reason reason,
            int cacheSeconds,
            int demuxerReadaheadSeconds,
            int hysteresisSeconds,
            int rebufferWaitSeconds,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.PathKind playerPath) {

        Decision {
            master = master == null ? Master.MPV_CONF : master;
            reason = reason == null ? Reason.UNKNOWN_SOURCE : reason;
            cacheSeconds = nonNegative(cacheSeconds);
            demuxerReadaheadSeconds = nonNegative(demuxerReadaheadSeconds);
            hysteresisSeconds = nonNegative(hysteresisSeconds);
            rebufferWaitSeconds = nonNegative(rebufferWaitSeconds);
            protocol = protocol == null ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            playerPath = playerPath == null ? PlaybackAutoContext.PathKind.UNKNOWN : playerPath;
        }

        Map<String, String> runtimeOptions() {
            if (!runtimeManaged) return Collections.emptyMap();
            Map<String, String> options = new LinkedHashMap<>();
            options.put("cache-secs", String.valueOf(cacheSeconds));
            options.put("demuxer-readahead-secs", String.valueOf(demuxerReadaheadSeconds));
            options.put("demuxer-hysteresis-secs", String.valueOf(hysteresisSeconds));
            return Collections.unmodifiableMap(options);
        }
    }
}
