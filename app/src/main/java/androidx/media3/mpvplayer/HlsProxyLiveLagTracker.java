package androidx.media3.mpvplayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** URL-free lower-bound estimator from rewritten live media-unit requests. */
final class HlsProxyLiveLagTracker {

    static final int MAX_PLAYLISTS = 16;
    static final int MAX_INDEXED_UNITS = 512;

    private final LinkedHashMap<String, PlaylistWindow> playlists =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, UnitReference> unitIndex =
            new LinkedHashMap<>();
    private CurrentUnit current;

    synchronized void observePlaylist(
            String playlistKey,
            List<HlsPlaylistRewriter.MediaUnit> mediaUnits,
            long observedAtElapsedMs) {
        String key = digest(playlistKey);
        if (key.isEmpty()) return;
        long now = Math.max(0, observedAtElapsedMs);
        PlaylistWindow previous = playlists.remove(key);
        if (previous != null) removeIndexedUnits(previous);

        PlaylistWindow window = buildWindow(key, mediaUnits, now);
        if (window == null) {
            if (current != null && key.equals(current.playlistKey())) {
                current = null;
            }
            return;
        }
        playlists.put(key, window);
        for (Map.Entry<String, Long> entry : window.offsetByUnit().entrySet()) {
            unitIndex.put(entry.getKey(), new UnitReference(
                    key, entry.getKey(), entry.getValue(), now));
        }
        trimBounds();

        if (current == null || !key.equals(current.playlistKey())) return;
        Long refreshedOffset = window.offsetByUnit().get(current.unitKey());
        if (refreshedOffset != null) {
            current = new CurrentUnit(key, current.unitKey(),
                    refreshedOffset, now, false);
        } else {
            long edgeAdvanceMs = edgeAdvanceMs(previous, window);
            if (edgeAdvanceMs <= 0) {
                current = null;
                return;
            }
            current = new CurrentUnit(key, current.unitKey(),
                    Math.max(addSaturated(
                                    current.lowerBoundMs(), edgeAdvanceMs),
                            window.totalDurationMs()),
                    now, true);
        }
    }

    synchronized void clearPlaylist(String playlistKey) {
        String key = digest(playlistKey);
        PlaylistWindow removed = playlists.remove(key);
        if (removed != null) removeIndexedUnits(removed);
        if (current != null && key.equals(current.playlistKey())) current = null;
    }

    synchronized void observeMediaRequest(
            String uri,
            long requestedAtElapsedMs) {
        String unitKey = digest(uri);
        UnitReference reference = unitIndex.get(unitKey);
        if (reference == null) {
            current = null;
            return;
        }
        current = new CurrentUnit(
                reference.playlistKey(),
                reference.unitKey(),
                reference.lowerBoundMs(),
                Math.max(0, requestedAtElapsedMs),
                false);
    }

    synchronized Snapshot snapshot(
            long nowElapsedMs,
            long nativeBufferedDurationMs) {
        if (current == null) return Snapshot.unknown();
        long now = Math.max(0, nowElapsedMs);
        long elapsed = Math.max(0, now - current.evidenceAtElapsedMs());
        long decayed = Math.max(0, current.lowerBoundMs() - elapsed);
        long buffered = Math.max(0, nativeBufferedDurationMs);
        long lowerBound = addSaturated(decayed, buffered);
        return new Snapshot(true, lowerBound, buffered,
                current.outsideWindow());
    }

    private PlaylistWindow buildWindow(
            String playlistKey,
            List<HlsPlaylistRewriter.MediaUnit> mediaUnits,
            long observedAtElapsedMs) {
        List<HlsPlaylistRewriter.MediaUnit> units = mediaUnits == null
                ? List.of() : mediaUnits;
        long totalDurationMs = 0;
        for (HlsPlaylistRewriter.MediaUnit unit : units) {
            if (unit == null || unit.durationSeconds() <= 0) continue;
            totalDurationMs = Math.max(totalDurationMs,
                    secondsToMs(unit.endSeconds()));
        }
        if (totalDurationMs <= 0) return null;

        LinkedHashMap<String, Long> offsets = new LinkedHashMap<>();
        for (HlsPlaylistRewriter.MediaUnit unit : units) {
            if (unit == null || unit.byteRange()
                    || unit.durationSeconds() <= 0) continue;
            String unitKey = digest(unit.uri());
            if (unitKey.isEmpty()) continue;
            long endMs = secondsToMs(unit.endSeconds());
            offsets.put(unitKey, Math.max(0, totalDurationMs - endMs));
        }
        if (offsets.isEmpty()) return null;
        return new PlaylistWindow(playlistKey, offsets, totalDurationMs,
                observedAtElapsedMs);
    }

    private void trimBounds() {
        while (playlists.size() > MAX_PLAYLISTS) {
            Map.Entry<String, PlaylistWindow> eldest =
                    playlists.entrySet().iterator().next();
            playlists.remove(eldest.getKey());
            removeIndexedUnits(eldest.getValue());
            if (current != null
                    && eldest.getKey().equals(current.playlistKey())) {
                current = null;
            }
        }
        while (unitIndex.size() > MAX_INDEXED_UNITS) {
            String eldest = unitIndex.keySet().iterator().next();
            unitIndex.remove(eldest);
            if (current != null && eldest.equals(current.unitKey())) {
                current = null;
            }
        }
    }

    private void removeIndexedUnits(PlaylistWindow window) {
        if (window == null) return;
        for (String unitKey : window.offsetByUnit().keySet()) {
            UnitReference indexed = unitIndex.get(unitKey);
            if (indexed != null
                    && window.playlistKey().equals(indexed.playlistKey())) {
                unitIndex.remove(unitKey);
            }
        }
    }

    private static long edgeAdvanceMs(
            PlaylistWindow previous,
            PlaylistWindow current) {
        if (previous == null || current == null) return -1;
        long advance = -1;
        for (Map.Entry<String, Long> entry
                : previous.offsetByUnit().entrySet()) {
            Long currentOffset = current.offsetByUnit().get(entry.getKey());
            if (currentOffset == null) continue;
            advance = Math.max(advance,
                    Math.max(0, currentOffset - entry.getValue()));
        }
        return advance;
    }

    private static String digest(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) return "";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(safe.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                output.append(Character.forDigit((valueByte >>> 4) & 0xF, 16));
                output.append(Character.forDigit(valueByte & 0xF, 16));
            }
            return output.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long secondsToMs(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return 0;
        double millis = seconds * 1_000d;
        return millis >= Long.MAX_VALUE ? Long.MAX_VALUE
                : Math.max(0, Math.round(millis));
    }

    private static long addSaturated(long first, long second) {
        if (first >= Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    record Snapshot(
            boolean known,
            long lowerBoundMs,
            long nativeBufferedDurationMs,
            boolean outsideWindow) {

        Snapshot {
            lowerBoundMs = Math.max(0, lowerBoundMs);
            nativeBufferedDurationMs = Math.max(0, nativeBufferedDurationMs);
        }

        static Snapshot unknown() {
            return new Snapshot(false, 0, 0, false);
        }
    }

    private record PlaylistWindow(
            String playlistKey,
            Map<String, Long> offsetByUnit,
            long totalDurationMs,
            long observedAtElapsedMs) {
    }

    private record UnitReference(
            String playlistKey,
            String unitKey,
            long lowerBoundMs,
            long observedAtElapsedMs) {
    }

    private record CurrentUnit(
            String playlistKey,
            String unitKey,
            long lowerBoundMs,
            long evidenceAtElapsedMs,
            boolean outsideWindow) {
    }
}
