package com.fongmi.android.tv.player.ijk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded, versioned and expiring IJK device/format/path runtime profiles. */
final class IjkRuntimeProfileStore {

    static final int STORE_VERSION = 1;
    static final int ENTRY_VERSION = 1;
    static final int EXCLUSION_THRESHOLD = 2;
    static final long ENTRY_TTL_MS = TimeUnit.DAYS.toMillis(30);
    static final long MAX_FUTURE_SKEW_MS = TimeUnit.MINUTES.toMillis(5);
    static final int MAX_ENTRIES = 64;

    private static final String HEADER = "store-version=" + STORE_VERSION;

    private final Backend backend;
    private final Map<EntryKey, Entry> entries;
    private boolean loaded;

    IjkRuntimeProfileStore(Backend backend) {
        this.backend = backend;
        this.entries = new LinkedHashMap<>();
    }

    synchronized Lookup lookup(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            long nowEpochMs) {
        if (!validInput(key, path, nowEpochMs)) return Lookup.miss();
        ensureLoaded(nowEpochMs);
        if (prune(nowEpochMs)) save();
        Entry entry = entries.get(new EntryKey(key, path));
        return entry == null ? Lookup.miss() : new Lookup(true, entry);
    }

    synchronized List<Entry> entriesForKey(
            IjkRuntimeProfileKey.Key key,
            long nowEpochMs) {
        if (key == null || !key.valid() || nowEpochMs <= 0) return List.of();
        ensureLoaded(nowEpochMs);
        if (prune(nowEpochMs)) save();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (key.equals(entry.key())) result.add(entry);
        }
        return List.copyOf(result);
    }

    synchronized RecordResult recordFirstFrame(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            long nowEpochMs) {
        if (!validInput(key, path, nowEpochMs)) return RecordResult.skipped();
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Entry previous = entries.get(new EntryKey(key, path));
        Entry updated = create(
                key,
                path,
                saturatingIncrement(previous == null ? 0
                        : previous.firstFrameCount()),
                previous == null ? 0 : previous.stableSuccessCount(),
                previous == null ? 0 : previous.failureCount(),
                previous == null ? 0 : previous.consecutiveFailures(),
                previous == null ? 0 : previous.rebufferCount(),
                previous == null ? -1 : previous.maxDropRatePermille(),
                previous == null ? -1 : previous.minRenderedRatioPermille(),
                previous == null ? -1 : previous.maxNativeHeapGrowthBytes(),
                previous == null ? -1 : previous.maxPssGrowthBytes(),
                previous == null ? 0 : previous.fallbackSuccessCount(),
                previous == null ? 0 : previous.fallbackFailureCount(),
                previous == null
                        ? IjkRuntimeProfilePolicy.FailureKind.NONE
                        : previous.lastFailureKind(),
                nowEpochMs);
        return putAndSave(updated);
    }

    synchronized RecordResult recordFailure(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            IjkRuntimeProfilePolicy.FailureKind failureKind,
            Metrics metrics,
            long nowEpochMs) {
        if (!validInput(key, path, nowEpochMs)
                || failureKind == null
                || failureKind == IjkRuntimeProfilePolicy.FailureKind.NONE) {
            return RecordResult.skipped();
        }
        Metrics current = metrics == null ? Metrics.EMPTY : metrics;
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Entry previous = entries.get(new EntryKey(key, path));
        Entry updated = create(
                key,
                path,
                previous == null ? 0 : previous.firstFrameCount(),
                previous == null ? 0 : previous.stableSuccessCount(),
                saturatingIncrement(previous == null ? 0
                        : previous.failureCount()),
                saturatingIncrement(previous == null ? 0
                        : previous.consecutiveFailures()),
                saturatingAdd(previous == null ? 0
                        : previous.rebufferCount(), current.rebufferCount()),
                maxMetric(previous == null ? -1
                        : previous.maxDropRatePermille(),
                        current.dropRatePermille()),
                minMetric(previous == null ? -1
                        : previous.minRenderedRatioPermille(),
                        current.renderedRatioPermille()),
                maxMetric(previous == null ? -1
                        : previous.maxNativeHeapGrowthBytes(),
                        current.nativeHeapGrowthBytes()),
                maxMetric(previous == null ? -1
                        : previous.maxPssGrowthBytes(),
                        current.pssGrowthBytes()),
                previous == null ? 0 : previous.fallbackSuccessCount(),
                previous == null ? 0 : previous.fallbackFailureCount(),
                failureKind,
                nowEpochMs);
        return putAndSave(updated);
    }

    synchronized RecordResult recordStableSuccess(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            Metrics metrics,
            long nowEpochMs) {
        if (!validInput(key, path, nowEpochMs)) return RecordResult.skipped();
        Metrics current = metrics == null ? Metrics.EMPTY : metrics;
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Entry previous = entries.get(new EntryKey(key, path));
        Entry updated = create(
                key,
                path,
                previous == null ? 0 : previous.firstFrameCount(),
                saturatingIncrement(previous == null ? 0
                        : previous.stableSuccessCount()),
                previous == null ? 0 : previous.failureCount(),
                0,
                saturatingAdd(previous == null ? 0
                        : previous.rebufferCount(), current.rebufferCount()),
                maxMetric(previous == null ? -1
                        : previous.maxDropRatePermille(),
                        current.dropRatePermille()),
                minMetric(previous == null ? -1
                        : previous.minRenderedRatioPermille(),
                        current.renderedRatioPermille()),
                maxMetric(previous == null ? -1
                        : previous.maxNativeHeapGrowthBytes(),
                        current.nativeHeapGrowthBytes()),
                maxMetric(previous == null ? -1
                        : previous.maxPssGrowthBytes(),
                        current.pssGrowthBytes()),
                previous == null ? 0 : previous.fallbackSuccessCount(),
                previous == null ? 0 : previous.fallbackFailureCount(),
                previous == null
                        ? IjkRuntimeProfilePolicy.FailureKind.NONE
                        : previous.lastFailureKind(),
                nowEpochMs);
        return putAndSave(updated);
    }

    synchronized RecordResult recordObservation(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            Metrics metrics,
            long nowEpochMs) {
        Metrics current = metrics == null ? Metrics.EMPTY : metrics;
        if (!validInput(key, path, nowEpochMs) || current.empty()) {
            return RecordResult.skipped();
        }
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Entry previous = entries.get(new EntryKey(key, path));
        Entry updated = create(
                key,
                path,
                previous == null ? 0 : previous.firstFrameCount(),
                previous == null ? 0 : previous.stableSuccessCount(),
                previous == null ? 0 : previous.failureCount(),
                previous == null ? 0 : previous.consecutiveFailures(),
                saturatingAdd(previous == null ? 0
                        : previous.rebufferCount(), current.rebufferCount()),
                maxMetric(previous == null ? -1
                        : previous.maxDropRatePermille(),
                        current.dropRatePermille()),
                minMetric(previous == null ? -1
                        : previous.minRenderedRatioPermille(),
                        current.renderedRatioPermille()),
                maxMetric(previous == null ? -1
                        : previous.maxNativeHeapGrowthBytes(),
                        current.nativeHeapGrowthBytes()),
                maxMetric(previous == null ? -1
                        : previous.maxPssGrowthBytes(),
                        current.pssGrowthBytes()),
                previous == null ? 0 : previous.fallbackSuccessCount(),
                previous == null ? 0 : previous.fallbackFailureCount(),
                previous == null
                        ? IjkRuntimeProfilePolicy.FailureKind.NONE
                        : previous.lastFailureKind(),
                nowEpochMs);
        return putAndSave(updated);
    }

    synchronized RecordResult recordFallback(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            FallbackResult result,
            long nowEpochMs) {
        if (!validInput(key, path, nowEpochMs)
                || result == null
                || result == FallbackResult.NONE) {
            return RecordResult.skipped();
        }
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Entry previous = entries.get(new EntryKey(key, path));
        Entry updated = create(
                key,
                path,
                previous == null ? 0 : previous.firstFrameCount(),
                previous == null ? 0 : previous.stableSuccessCount(),
                previous == null ? 0 : previous.failureCount(),
                previous == null ? 0 : previous.consecutiveFailures(),
                previous == null ? 0 : previous.rebufferCount(),
                previous == null ? -1 : previous.maxDropRatePermille(),
                previous == null ? -1 : previous.minRenderedRatioPermille(),
                previous == null ? -1 : previous.maxNativeHeapGrowthBytes(),
                previous == null ? -1 : previous.maxPssGrowthBytes(),
                result == FallbackResult.SUCCESS
                        ? saturatingIncrement(previous == null ? 0
                        : previous.fallbackSuccessCount())
                        : previous == null ? 0
                        : previous.fallbackSuccessCount(),
                result == FallbackResult.FAILURE
                        ? saturatingIncrement(previous == null ? 0
                        : previous.fallbackFailureCount())
                        : previous == null ? 0
                        : previous.fallbackFailureCount(),
                previous == null
                        ? IjkRuntimeProfilePolicy.FailureKind.NONE
                        : previous.lastFailureKind(),
                nowEpochMs);
        return putAndSave(updated);
    }

    synchronized int entryCount(long nowEpochMs) {
        ensureLoaded(nowEpochMs);
        if (prune(nowEpochMs)) save();
        return entries.size();
    }

    synchronized void clear() {
        loaded = true;
        entries.clear();
        try {
            backend.clear();
        } catch (Throwable ignored) {
        }
    }

    private RecordResult putAndSave(Entry entry) {
        entries.put(new EntryKey(entry.key(), entry.path()), entry);
        trimToCapacity();
        save();
        return new RecordResult(true, entry);
    }

    private static Entry create(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            int firstFrameCount,
            int stableSuccessCount,
            int failureCount,
            int consecutiveFailures,
            long rebufferCount,
            int maxDropRatePermille,
            int minRenderedRatioPermille,
            long maxNativeHeapGrowthBytes,
            long maxPssGrowthBytes,
            int fallbackSuccessCount,
            int fallbackFailureCount,
            IjkRuntimeProfilePolicy.FailureKind lastFailureKind,
            long updatedAtEpochMs) {
        return new Entry(
                ENTRY_VERSION,
                key,
                path,
                firstFrameCount,
                stableSuccessCount,
                failureCount,
                consecutiveFailures,
                rebufferCount,
                maxDropRatePermille,
                minRenderedRatioPermille,
                maxNativeHeapGrowthBytes,
                maxPssGrowthBytes,
                fallbackSuccessCount,
                fallbackFailureCount,
                lastFailureKind,
                updatedAtEpochMs);
    }

    private boolean validInput(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            long nowEpochMs) {
        return key != null && key.valid() && path != null && nowEpochMs > 0;
    }

    private void ensureLoaded(long nowEpochMs) {
        if (loaded) return;
        loaded = true;
        String raw;
        try {
            raw = backend.read();
        } catch (Throwable ignored) {
            raw = "";
        }
        DecodeResult decoded = decode(raw);
        if (!decoded.validStore()) {
            entries.clear();
            try {
                backend.clear();
            } catch (Throwable ignored) {
            }
            return;
        }
        entries.putAll(decoded.entries());
        if (decoded.rewriteRequired() || prune(nowEpochMs)) save();
    }

    private boolean prune(long nowEpochMs) {
        if (nowEpochMs <= 0) return false;
        boolean changed = false;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.valid()
                    || entry.updatedAtEpochMs()
                    > saturatingAdd(nowEpochMs, MAX_FUTURE_SKEW_MS)
                    || nowEpochMs >= entry.updatedAtEpochMs()
                    && nowEpochMs - entry.updatedAtEpochMs() >= ENTRY_TTL_MS) {
                iterator.remove();
                changed = true;
            }
        }
        return trimToCapacity() || changed;
    }

    private boolean trimToCapacity() {
        boolean changed = false;
        while (entries.size() > MAX_ENTRIES) {
            EntryKey victim = null;
            Entry victimEntry = null;
            for (Map.Entry<EntryKey, Entry> candidate : entries.entrySet()) {
                Entry entry = candidate.getValue();
                if (victimEntry == null
                        || retentionRank(entry) < retentionRank(victimEntry)
                        || retentionRank(entry) == retentionRank(victimEntry)
                        && entry.updatedAtEpochMs()
                        < victimEntry.updatedAtEpochMs()) {
                    victim = candidate.getKey();
                    victimEntry = entry;
                }
            }
            if (victim == null) break;
            entries.remove(victim);
            changed = true;
        }
        return changed;
    }

    private static int retentionRank(Entry entry) {
        if (entry.excluded()) return 2;
        if (entry.verified()) return 1;
        return 0;
    }

    private void save() {
        try {
            if (entries.isEmpty()) backend.clear();
            else backend.write(encode(entries.values()));
        } catch (Throwable ignored) {
        }
    }

    private static DecodeResult decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return new DecodeResult(true, false, Map.of());
        }
        String[] lines = raw.split("\\r?\\n");
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            return new DecodeResult(false, false, Map.of());
        }
        Map<EntryKey, Entry> decoded = new LinkedHashMap<>();
        boolean rewriteRequired = false;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            Entry entry = decodeEntry(line);
            if (entry == null) {
                rewriteRequired = true;
                continue;
            }
            EntryKey key = new EntryKey(entry.key(), entry.path());
            Entry previous = decoded.get(key);
            if (previous == null
                    || entry.updatedAtEpochMs() > previous.updatedAtEpochMs()) {
                decoded.put(key, entry);
            }
            if (previous != null) rewriteRequired = true;
        }
        return new DecodeResult(true, rewriteRequired, decoded);
    }

    private static Entry decodeEntry(String line) {
        try {
            String[] values = line.split("\\|", -1);
            if (values.length != 32) return null;
            IjkRuntimeProfileKey.Key key = new IjkRuntimeProfileKey.Key(
                    values[1],
                    com.fongmi.android.tv.player.PlaybackAutoContext.Protocol
                            .valueOf(values[2]),
                    com.fongmi.android.tv.player.PlaybackAutoContext.StreamKind
                            .valueOf(values[3]),
                    values[4],
                    values[5],
                    Integer.parseInt(values[6]),
                    Integer.parseInt(values[7]),
                    Integer.parseInt(values[8]),
                    Integer.parseInt(values[9]),
                    Integer.parseInt(values[10]),
                    com.fongmi.android.tv.player.PlaybackAutoContext.HdrType
                            .valueOf(values[11]),
                    Integer.parseInt(values[12]),
                    Integer.parseInt(values[13]),
                    Integer.parseInt(values[14]),
                    parseBoolean(values[15]),
                    Integer.parseInt(values[16]),
                    com.fongmi.android.tv.player.PlaybackAutoContext.RenderTarget
                            .valueOf(values[17]));
            Entry entry = new Entry(
                    Integer.parseInt(values[0]),
                    key,
                    IjkRuntimeProfilePolicy.Path.valueOf(values[18]),
                    Integer.parseInt(values[19]),
                    Integer.parseInt(values[20]),
                    Integer.parseInt(values[21]),
                    Integer.parseInt(values[22]),
                    Long.parseLong(values[23]),
                    Integer.parseInt(values[24]),
                    Integer.parseInt(values[25]),
                    Long.parseLong(values[26]),
                    Long.parseLong(values[27]),
                    Integer.parseInt(values[28]),
                    Integer.parseInt(values[29]),
                    IjkRuntimeProfilePolicy.FailureKind.valueOf(values[30]),
                    Long.parseLong(values[31]));
            return entry.valid() ? entry : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean parseBoolean(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }

    private static String encode(Iterable<Entry> values) {
        List<Entry> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(Comparator
                .comparingInt(IjkRuntimeProfileStore::retentionRank)
                .reversed()
                .thenComparing(Entry::updatedAtEpochMs,
                        Comparator.reverseOrder())
                .thenComparing(entry -> IjkRuntimeProfileKey.shortId(
                        entry.key()))
                .thenComparing(entry -> entry.path().name()));
        StringBuilder result = new StringBuilder(HEADER);
        for (Entry entry : sorted) {
            IjkRuntimeProfileKey.Key key = entry.key();
            result.append('\n')
                    .append(entry.version()).append('|')
                    .append(key.environmentDigest()).append('|')
                    .append(key.protocol().name()).append('|')
                    .append(key.streamKind().name()).append('|')
                    .append(key.mimeType()).append('|')
                    .append(key.codecDigest()).append('|')
                    .append(key.profile()).append('|')
                    .append(key.level()).append('|')
                    .append(key.width()).append('|')
                    .append(key.height()).append('|')
                    .append(key.frameRateMilli()).append('|')
                    .append(key.hdrType().name()).append('|')
                    .append(key.colorSpace()).append('|')
                    .append(key.colorRange()).append('|')
                    .append(key.colorTransfer()).append('|')
                    .append(key.hdrStaticInfo() ? 1 : 0).append('|')
                    .append(key.secureState()).append('|')
                    .append(key.renderTarget().name()).append('|')
                    .append(entry.path().name()).append('|')
                    .append(entry.firstFrameCount()).append('|')
                    .append(entry.stableSuccessCount()).append('|')
                    .append(entry.failureCount()).append('|')
                    .append(entry.consecutiveFailures()).append('|')
                    .append(entry.rebufferCount()).append('|')
                    .append(entry.maxDropRatePermille()).append('|')
                    .append(entry.minRenderedRatioPermille()).append('|')
                    .append(entry.maxNativeHeapGrowthBytes()).append('|')
                    .append(entry.maxPssGrowthBytes()).append('|')
                    .append(entry.fallbackSuccessCount()).append('|')
                    .append(entry.fallbackFailureCount()).append('|')
                    .append(entry.lastFailureKind().name()).append('|')
                    .append(entry.updatedAtEpochMs());
        }
        return result.toString();
    }

    private static int saturatingIncrement(int value) {
        return value >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.max(0, value) + 1;
    }

    private static long saturatingAdd(long first, long second) {
        if (second <= 0) return Math.max(0, first);
        return first > Long.MAX_VALUE - second
                ? Long.MAX_VALUE : Math.max(0, first) + second;
    }

    private static int maxMetric(int first, int second) {
        if (second < 0) return first;
        return first < 0 ? second : Math.max(first, second);
    }

    private static long maxMetric(long first, long second) {
        if (second < 0) return first;
        return first < 0 ? second : Math.max(first, second);
    }

    private static int minMetric(int first, int second) {
        if (second < 0) return first;
        return first < 0 ? second : Math.min(first, second);
    }

    interface Backend {

        String read();

        void write(String value);

        void clear();
    }

    enum FallbackResult {
        NONE,
        SUCCESS,
        FAILURE
    }

    record Metrics(
            int rebufferCount,
            int dropRatePermille,
            int renderedRatioPermille,
            long nativeHeapGrowthBytes,
            long pssGrowthBytes) {

        static final Metrics EMPTY = new Metrics(0, -1, -1, -1, -1);

        Metrics {
            rebufferCount = Math.max(0, rebufferCount);
            dropRatePermille = Math.max(-1, dropRatePermille);
            renderedRatioPermille = Math.max(-1, renderedRatioPermille);
            nativeHeapGrowthBytes = Math.max(-1, nativeHeapGrowthBytes);
            pssGrowthBytes = Math.max(-1, pssGrowthBytes);
        }

        boolean empty() {
            return rebufferCount == 0
                    && dropRatePermille < 0
                    && renderedRatioPermille < 0
                    && nativeHeapGrowthBytes < 0
                    && pssGrowthBytes < 0;
        }
    }

    record Lookup(boolean hit, Entry entry) {

        static Lookup miss() {
            return new Lookup(false, null);
        }

        boolean excluded() {
            return hit && entry != null && entry.excluded();
        }

        boolean verified() {
            return hit && entry != null && entry.verified();
        }
    }

    record RecordResult(boolean recorded, Entry entry) {

        static RecordResult skipped() {
            return new RecordResult(false, null);
        }
    }

    record Entry(
            int version,
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            int firstFrameCount,
            int stableSuccessCount,
            int failureCount,
            int consecutiveFailures,
            long rebufferCount,
            int maxDropRatePermille,
            int minRenderedRatioPermille,
            long maxNativeHeapGrowthBytes,
            long maxPssGrowthBytes,
            int fallbackSuccessCount,
            int fallbackFailureCount,
            IjkRuntimeProfilePolicy.FailureKind lastFailureKind,
            long updatedAtEpochMs) {

        Entry {
            firstFrameCount = Math.max(0, firstFrameCount);
            stableSuccessCount = Math.max(0, stableSuccessCount);
            failureCount = Math.max(0, failureCount);
            consecutiveFailures = Math.max(0, consecutiveFailures);
            rebufferCount = Math.max(0, rebufferCount);
            maxDropRatePermille = Math.max(-1, maxDropRatePermille);
            minRenderedRatioPermille = Math.max(-1,
                    minRenderedRatioPermille);
            maxNativeHeapGrowthBytes = Math.max(-1,
                    maxNativeHeapGrowthBytes);
            maxPssGrowthBytes = Math.max(-1, maxPssGrowthBytes);
            fallbackSuccessCount = Math.max(0, fallbackSuccessCount);
            fallbackFailureCount = Math.max(0, fallbackFailureCount);
            lastFailureKind = lastFailureKind == null
                    ? IjkRuntimeProfilePolicy.FailureKind.NONE
                    : lastFailureKind;
        }

        boolean valid() {
            return version == ENTRY_VERSION
                    && key != null
                    && key.valid()
                    && path != null
                    && updatedAtEpochMs > 0;
        }

        boolean excluded() {
            return consecutiveFailures >= EXCLUSION_THRESHOLD;
        }

        boolean verified() {
            return stableSuccessCount > 0 && consecutiveFailures == 0;
        }
    }

    private record EntryKey(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path) {
    }

    private record DecodeResult(
            boolean validStore,
            boolean rewriteRequired,
            Map<EntryKey, Entry> entries) {
    }
}
