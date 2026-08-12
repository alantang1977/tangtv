package com.fongmi.android.tv.player;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-local, privacy-preserving handoff of trusted throughput evidence.
 *
 * <p>The store never persists or logs the network identity digest. A record is
 * reusable only for the same Android network identity and the same effective
 * upstream path.</p>
 */
public final class PlaybackThroughputHistory {

    public static final long EVIDENCE_TTL_MS = 5L * 60L * 1000L;
    public static final int MAX_ENTRIES = 8;
    public static final int MIN_LONG_SAMPLES = 4;
    public static final long MIN_LONG_WINDOW_MS = 15_000L;
    public static final int MAX_PREDICTION_ERROR_PERMILLE = 500;

    private static final PlaybackThroughputHistory PROCESS =
            new PlaybackThroughputHistory();

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    public static PlaybackThroughputHistory process() {
        return PROCESS;
    }

    public synchronized RecordResult record(
            PlaybackAutoContext context,
            Evidence evidence,
            long nowElapsedMs) {
        long now = Math.max(0, nowElapsedMs);
        Key key = keyFromContext(context, "", now);
        if (key == null) return new RecordResult(false, Reason.IDENTITY_OR_PATH_UNKNOWN, 0);
        Evidence safe = evidence == null ? Evidence.unknown() : evidence;
        if (!safe.trusted()) return new RecordResult(false, safe.rejectionReason(), 0);
        long estimate = safe.conservativeBitsPerSecond();
        if (estimate <= 0) return new RecordResult(false, Reason.ESTIMATE_UNKNOWN, 0);
        prune(now);
        entries.remove(key);
        entries.put(key, new Entry(estimate, safe.confidence(), now));
        trimToLimit();
        return new RecordResult(true, Reason.RECORDED, estimate);
    }

    public synchronized Match lookup(
            PlaybackAutoContext context,
            String currentNetworkIdentityDigest,
            long nowElapsedMs) {
        long now = Math.max(0, nowElapsedMs);
        prune(now);
        Key key = keyFromContext(context, currentNetworkIdentityDigest, now);
        if (key == null) return Match.unavailable(Reason.IDENTITY_OR_PATH_UNKNOWN);
        Entry entry = entries.get(key);
        if (entry == null) return Match.unavailable(Reason.NO_MATCH);
        long age = Math.max(0, now - entry.recordedAtElapsedMs());
        if (age > EVIDENCE_TTL_MS) {
            entries.remove(key);
            return Match.unavailable(Reason.EXPIRED);
        }
        return new Match(true, entry.bitsPerSecond(), age, key.pathKind(),
                entry.confidence(), Reason.MATCHED);
    }

    public synchronized void invalidate() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized boolean record(
            String networkIdentityDigest,
            PlaybackAutoContext.PathKind pathKind,
            Evidence evidence,
            long nowElapsedMs) {
        if (!PlaybackNetworkIdentityPolicy.isValidDigest(networkIdentityDigest)
                || !usablePath(pathKind)) {
            return false;
        }
        Evidence safe = evidence == null ? Evidence.unknown() : evidence;
        if (!safe.trusted()) return false;
        long estimate = safe.conservativeBitsPerSecond();
        if (estimate <= 0) return false;
        long now = Math.max(0, nowElapsedMs);
        prune(now);
        Key key = new Key(networkIdentityDigest, pathKind);
        entries.remove(key);
        entries.put(key, new Entry(estimate, safe.confidence(), now));
        trimToLimit();
        return true;
    }

    synchronized Match lookup(
            String networkIdentityDigest,
            PlaybackAutoContext.PathKind pathKind,
            long nowElapsedMs) {
        long now = Math.max(0, nowElapsedMs);
        prune(now);
        if (!PlaybackNetworkIdentityPolicy.isValidDigest(networkIdentityDigest)
                || !usablePath(pathKind)) {
            return Match.unavailable(Reason.IDENTITY_OR_PATH_UNKNOWN);
        }
        Key key = new Key(networkIdentityDigest, pathKind);
        Entry entry = entries.get(key);
        if (entry == null) return Match.unavailable(Reason.NO_MATCH);
        long age = Math.max(0, now - entry.recordedAtElapsedMs());
        return new Match(true, entry.bitsPerSecond(), age, pathKind,
                entry.confidence(), Reason.MATCHED);
    }

    static PlaybackAutoContext.PathKind effectivePath(
            PlaybackAutoContext context,
            long nowElapsedMs) {
        if (context == null) return PlaybackAutoContext.PathKind.UNKNOWN;
        PlaybackAutoContext.PathFacts path = context.path();
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> upstreamState =
                path.upstreamState();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstreamPath =
                path.upstreamPath();
        if (upstreamState.isUsable(now)
                && upstreamState.value() == PlaybackAutoContext.UpstreamState.VISIBLE
                && upstreamPath.isUsable(now)
                && usablePath(upstreamPath.value())) {
            return upstreamPath.value();
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPath =
                path.playerPath();
        if (playerPath.isUsable(now) && usablePath(playerPath.value())) {
            return playerPath.value();
        }
        return PlaybackAutoContext.PathKind.UNKNOWN;
    }

    private static Key keyFromContext(
            PlaybackAutoContext context,
            String fallbackIdentityDigest,
            long now) {
        if (context == null || !context.active()) return null;
        String contextDigest = "";
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> network =
                context.device().networkSnapshot();
        if (network.isUsable(now)) {
            contextDigest = network.value().identityDigest();
        }
        boolean contextIdentityUsable =
                PlaybackNetworkIdentityPolicy.isValidDigest(contextDigest);
        boolean fallbackIdentityUsable =
                PlaybackNetworkIdentityPolicy.isValidDigest(fallbackIdentityDigest);
        if (contextIdentityUsable && fallbackIdentityUsable
                && !contextDigest.equals(fallbackIdentityDigest)) return null;
        String digest = fallbackIdentityUsable
                ? fallbackIdentityDigest : contextDigest;
        PlaybackAutoContext.PathKind path = effectivePath(context, now);
        if (!PlaybackNetworkIdentityPolicy.isValidDigest(digest) || !usablePath(path)) {
            return null;
        }
        return new Key(digest, path);
    }

    private static boolean usablePath(PlaybackAutoContext.PathKind pathKind) {
        return pathKind == PlaybackAutoContext.PathKind.REMOTE
                || pathKind == PlaybackAutoContext.PathKind.LAN_PRIVATE;
    }

    private void prune(long now) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.recordedAtElapsedMs() > EVIDENCE_TTL_MS) iterator.remove();
        }
    }

    private void trimToLimit() {
        while (entries.size() > MAX_ENTRIES) {
            Iterator<Key> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    public record Evidence(
            long effectiveBitsPerSecond,
            long shortBitsPerSecond,
            long longBitsPerSecond,
            int longSampleCount,
            long longWindowMs,
            int predictionErrorPermille,
            PlaybackAutoContext.Confidence confidence,
            boolean trustedPath,
            boolean preloadContended) {

        public Evidence {
            effectiveBitsPerSecond = Math.max(0, effectiveBitsPerSecond);
            shortBitsPerSecond = Math.max(0, shortBitsPerSecond);
            longBitsPerSecond = Math.max(0, longBitsPerSecond);
            longSampleCount = Math.max(0, longSampleCount);
            longWindowMs = Math.max(0, longWindowMs);
            confidence = confidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
        }

        public static Evidence unknown() {
            return new Evidence(0, 0, 0, 0, 0, -1,
                    PlaybackAutoContext.Confidence.UNKNOWN, false, false);
        }

        boolean trusted() {
            return trustedPath
                    && !preloadContended
                    && (confidence == PlaybackAutoContext.Confidence.MEDIUM
                    || confidence == PlaybackAutoContext.Confidence.HIGH)
                    && longSampleCount >= MIN_LONG_SAMPLES
                    && longWindowMs >= MIN_LONG_WINDOW_MS
                    && predictionErrorPermille >= 0
                    && predictionErrorPermille <= MAX_PREDICTION_ERROR_PERMILLE
                    && conservativeBitsPerSecond() > 0;
        }

        long conservativeBitsPerSecond() {
            if (effectiveBitsPerSecond <= 0
                    || shortBitsPerSecond <= 0
                    || longBitsPerSecond <= 0) return 0;
            return Math.min(effectiveBitsPerSecond,
                    Math.min(shortBitsPerSecond, longBitsPerSecond));
        }

        Reason rejectionReason() {
            if (!trustedPath) return Reason.PATH_NOT_TRUSTED;
            if (preloadContended) return Reason.PRELOAD_CONTENDED;
            if (confidence != PlaybackAutoContext.Confidence.MEDIUM
                    && confidence != PlaybackAutoContext.Confidence.HIGH) {
                return Reason.LOW_CONFIDENCE;
            }
            if (longSampleCount < MIN_LONG_SAMPLES
                    || longWindowMs < MIN_LONG_WINDOW_MS) {
                return Reason.INSUFFICIENT_LONG_WINDOW;
            }
            if (predictionErrorPermille < 0
                    || predictionErrorPermille > MAX_PREDICTION_ERROR_PERMILLE) {
                return Reason.HIGH_PREDICTION_ERROR;
            }
            return Reason.ESTIMATE_UNKNOWN;
        }

    }

    public record Match(
            boolean usable,
            long bitsPerSecond,
            long ageMs,
            PlaybackAutoContext.PathKind pathKind,
            PlaybackAutoContext.Confidence confidence,
            Reason reason) {

        public Match {
            bitsPerSecond = Math.max(0, bitsPerSecond);
            ageMs = Math.max(0, ageMs);
            pathKind = pathKind == null
                    ? PlaybackAutoContext.PathKind.UNKNOWN : pathKind;
            confidence = confidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            reason = reason == null ? Reason.NO_MATCH : reason;
        }

        public static Match unavailable(Reason reason) {
            return new Match(false, 0, 0, PlaybackAutoContext.PathKind.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN, reason);
        }
    }

    public record RecordResult(boolean recorded, Reason reason, long bitsPerSecond) {

        public RecordResult {
            reason = reason == null ? Reason.ESTIMATE_UNKNOWN : reason;
            bitsPerSecond = Math.max(0, bitsPerSecond);
        }
    }

    public enum Reason {
        RECORDED("recorded"),
        MATCHED("matched"),
        NO_MATCH("no-match"),
        EXPIRED("expired"),
        IDENTITY_OR_PATH_UNKNOWN("identity-or-path-unknown"),
        PATH_NOT_TRUSTED("path-not-trusted"),
        PRELOAD_CONTENDED("preload-contended"),
        LOW_CONFIDENCE("low-confidence"),
        INSUFFICIENT_LONG_WINDOW("insufficient-long-window"),
        HIGH_PREDICTION_ERROR("high-prediction-error"),
        ESTIMATE_UNKNOWN("estimate-unknown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private record Key(String networkIdentityDigest,
                       PlaybackAutoContext.PathKind pathKind) {
    }

    private record Entry(long bitsPerSecond,
                         PlaybackAutoContext.Confidence confidence,
                         long recordedAtElapsedMs) {
    }
}
