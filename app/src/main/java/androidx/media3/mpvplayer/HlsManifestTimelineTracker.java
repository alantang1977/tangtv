package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Session-local reducer for HLS master/media playlist observations.
 *
 * <p>A master playlist is not enough to classify a stream. Media playlists are
 * retained by stable proxy key, live observations expire from their manifest
 * cadence, and a later master refresh cannot erase a still-valid media scene.
 * The reducer never exposes URLs or treats a stale observation as current.
 */
final class HlsManifestTimelineTracker {

    static final int MAX_MEDIA_PLAYLISTS = 16;

    private final LinkedHashMap<String, Observation> mediaPlaylists = new LinkedHashMap<>();
    private PlaybackResourceClassifier.Classification master;
    private long masterObservedAtElapsedMs = -1;

    synchronized boolean observe(
            String key,
            PlaybackResourceClassifier.Classification classification) {
        if (classification == null || !classification.actualManifest()
                || classification.protocol() != PlaybackAutoContext.Protocol.HLS) return false;
        long observedAt = classification.observedAtElapsedMs();
        if (classification.manifest().kind() == PlaybackAutoContext.ManifestKind.HLS_MASTER) {
            if (master != null && observedAt >= 0 && masterObservedAtElapsedMs >= 0
                    && observedAt < masterObservedAtElapsedMs) return false;
            master = classification;
            masterObservedAtElapsedMs = observedAt;
            return true;
        }
        if (classification.manifest().kind() != PlaybackAutoContext.ManifestKind.HLS_MEDIA) {
            return false;
        }
        String stableKey = key == null || key.isBlank() ? "direct" : key;
        Observation previous = mediaPlaylists.get(stableKey);
        if (previous != null && observedAt >= 0 && previous.observedAtElapsedMs() >= 0
                && observedAt < previous.observedAtElapsedMs()) return false;
        mediaPlaylists.remove(stableKey);
        mediaPlaylists.put(stableKey, new Observation(classification, observedAt));
        while (mediaPlaylists.size() > MAX_MEDIA_PLAYLISTS) {
            String oldest = mediaPlaylists.keySet().iterator().next();
            mediaPlaylists.remove(oldest);
        }
        return true;
    }

    synchronized Snapshot snapshot(
            PlaybackResourceClassifier.Classification fallback,
            long nowElapsedMs) {
        long now = Math.max(0, nowElapsedMs);
        PlaybackResourceClassifier.Classification base = fallback == null
                ? PlaybackResourceClassifier.classifyRequest(null, null, null) : fallback;
        int stale = 0;
        List<Observation> fresh = new ArrayList<>();
        for (var iterator = mediaPlaylists.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<String, Observation> entry = iterator.next();
            if (!isFresh(entry.getValue(), now)) {
                iterator.remove();
                stale++;
            } else {
                fresh.add(entry.getValue());
            }
        }
        if (fresh.isEmpty()) {
            PlaybackResourceClassifier.Classification resolved = master == null
                    ? base : PlaybackResourceClassifier.merge(base, master);
            return new Snapshot(resolved, 0, stale, SceneReason.NO_FRESH_MEDIA);
        }

        PlaybackResourceClassifier.Classification merged = master == null
                ? base : PlaybackResourceClassifier.merge(base, master);
        for (Observation observation : fresh) {
            merged = PlaybackResourceClassifier.merge(merged, observation.classification());
        }
        PlaybackAutoContext.StreamKind scene = resolveScene(fresh);
        long latestObservedAt = -1;
        long targetMs = -1;
        long partMs = -1;
        long holdBackMs = -1;
        boolean byteRange = false;
        boolean lowLatency = scene == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
        Integer variantCount = master == null ? null : master.manifest().variantCount();
        for (Observation observation : fresh) {
            PlaybackResourceClassifier.Classification classification = observation.classification();
            latestObservedAt = Math.max(latestObservedAt, observation.observedAtElapsedMs());
            PlaybackAutoContext.ManifestFacts manifest = classification.manifest();
            targetMs = max(targetMs, manifest.targetDurationMs());
            partMs = max(partMs, manifest.partDurationMs());
            holdBackMs = max(holdBackMs, manifest.holdBackMs());
            byteRange |= Boolean.TRUE.equals(manifest.byteRange());
            lowLatency |= Boolean.TRUE.equals(manifest.lowLatency());
            if (variantCount == null) variantCount = manifest.variantCount();
        }
        PlaybackAutoContext.ManifestFacts manifest = new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA,
                scene == PlaybackAutoContext.StreamKind.VOD ? Boolean.TRUE
                        : scene == PlaybackAutoContext.StreamKind.UNKNOWN ? null : Boolean.FALSE,
                targetMs < 0 ? null : targetMs,
                partMs < 0 ? null : partMs,
                holdBackMs < 0 ? null : holdBackMs,
                variantCount,
                byteRange,
                lowLatency);
        LinkedHashSet<PlaybackResourceClassifier.Evidence> evidence =
                new LinkedHashSet<>(merged.evidence());
        for (Observation observation : fresh) evidence.addAll(observation.classification().evidence());
        PlaybackAutoContext.TransferUnit transfer = scene
                == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE
                ? PlaybackAutoContext.TransferUnit.PART
                : PlaybackAutoContext.TransferUnit.SEGMENT;
        PlaybackResourceClassifier.Classification resolved =
                new PlaybackResourceClassifier.Classification(
                        PlaybackAutoContext.Protocol.HLS,
                        scene,
                        merged.rangeSupport(),
                        transfer,
                        merged.playerPath(),
                        merged.upstreamPath(),
                        merged.upstreamState(),
                        manifest,
                        List.copyOf(evidence),
                        scene == PlaybackAutoContext.StreamKind.UNKNOWN
                                ? merged.confidence() : PlaybackAutoContext.Confidence.HIGH,
                        PlaybackResourceClassifier.ValueSource.MANIFEST,
                        true,
                        latestObservedAt);
        return new Snapshot(resolved, fresh.size(), stale,
                sceneReason(fresh, scene));
    }

    synchronized void clear() {
        mediaPlaylists.clear();
        master = null;
        masterObservedAtElapsedMs = -1;
    }

    private static PlaybackAutoContext.StreamKind resolveScene(List<Observation> observations) {
        boolean vod = false;
        boolean live = false;
        boolean lowLatency = false;
        for (Observation observation : observations) {
            switch (observation.classification().streamKind()) {
                case LOW_LATENCY_LIVE -> lowLatency = true;
                case LIVE -> live = true;
                case VOD -> vod = true;
                default -> {
                }
            }
        }
        if (lowLatency) return PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
        if (live) return PlaybackAutoContext.StreamKind.LIVE;
        if (vod) return PlaybackAutoContext.StreamKind.VOD;
        return PlaybackAutoContext.StreamKind.UNKNOWN;
    }

    private static SceneReason sceneReason(
            List<Observation> observations,
            PlaybackAutoContext.StreamKind scene) {
        if (scene == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE) {
            return SceneReason.LOW_LATENCY_EVIDENCE;
        }
        if (scene == PlaybackAutoContext.StreamKind.LIVE) {
            for (Observation observation : observations) {
                if (observation.classification().streamKind()
                        == PlaybackAutoContext.StreamKind.VOD) return SceneReason.LIVE_DOMINATES;
            }
            return SceneReason.LIVE_EVIDENCE;
        }
        if (scene == PlaybackAutoContext.StreamKind.VOD) return SceneReason.ENDLIST_EVIDENCE;
        return SceneReason.MEDIA_UNKNOWN;
    }

    private static boolean isFresh(Observation observation, long nowElapsedMs) {
        PlaybackResourceClassifier.Classification classification = observation.classification();
        if (classification.streamKind() == PlaybackAutoContext.StreamKind.VOD
                && Boolean.TRUE.equals(classification.manifest().endList())) return true;
        long observedAt = observation.observedAtElapsedMs();
        if (observedAt < 0 || nowElapsedMs < observedAt) return false;
        long ttl = PlaybackResourceClassifier.manifestTtlMs(classification.manifest());
        return nowElapsedMs - observedAt < ttl;
    }

    private static long max(long current, Long value) {
        return value == null ? current : Math.max(current, value);
    }

    private record Observation(
            PlaybackResourceClassifier.Classification classification,
            long observedAtElapsedMs) {
    }

    record Snapshot(
            PlaybackResourceClassifier.Classification classification,
            int freshMediaPlaylists,
            int staleMediaPlaylists,
            SceneReason reason) {

        Snapshot {
            classification = classification == null
                    ? PlaybackResourceClassifier.classifyRequest(null, null, null) : classification;
            freshMediaPlaylists = Math.max(0, freshMediaPlaylists);
            staleMediaPlaylists = Math.max(0, staleMediaPlaylists);
            reason = reason == null ? SceneReason.MEDIA_UNKNOWN : reason;
        }
    }

    enum SceneReason {
        NO_FRESH_MEDIA("no-fresh-media"),
        MEDIA_UNKNOWN("media-unknown"),
        ENDLIST_EVIDENCE("endlist"),
        LIVE_EVIDENCE("live-evidence"),
        LIVE_DOMINATES("live-dominates-vod"),
        LOW_LATENCY_EVIDENCE("low-latency-evidence");

        private final String label;

        SceneReason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
