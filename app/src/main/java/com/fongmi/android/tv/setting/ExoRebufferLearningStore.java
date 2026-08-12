package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded, versioned and expiring persistent learning for EXO rebuffer thresholds. */
final class ExoRebufferLearningStore {

    static final int STORE_VERSION = 1;
    static final int ENTRY_VERSION = 1;
    static final long ENTRY_TTL_MS = TimeUnit.DAYS.toMillis(14);
    static final long MAX_FUTURE_SKEW_MS = TimeUnit.MINUTES.toMillis(5);
    static final int MAX_ENTRIES = 32;

    private static final String HEADER = "store-version=" + STORE_VERSION;

    private final Backend backend;
    private final Map<ExoRebufferLearningKey.Key, Entry> entries;
    private boolean loaded;

    ExoRebufferLearningStore(Backend backend) {
        this.backend = backend;
        this.entries = new LinkedHashMap<>();
    }

    synchronized Lookup lookup(
            ExoRebufferLearningKey.Key key,
            long nowEpochMs) {
        if (key == null || !key.valid() || nowEpochMs <= 0) {
            return Lookup.miss();
        }
        ensureLoaded(nowEpochMs);
        boolean changed = prune(nowEpochMs);
        if (changed) save();
        Entry entry = entries.get(key);
        return entry == null
                ? Lookup.miss()
                : new Lookup(
                true,
                entry.rebufferMs(),
                entry.cleanStreak(),
                entry.sampleCount(),
                entry.severity());
    }

    synchronized RecordResult record(
            ExoRebufferLearningKey.Key key,
            int rebufferCount,
            long rebufferTotalMs,
            long positionMs,
            long mediaBitrate,
            long bandwidthEstimate,
            long nowEpochMs) {
        if (key == null || !key.valid() || nowEpochMs <= 0) {
            return RecordResult.skipped();
        }
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Entry previous = entries.get(key);
        int currentMs = previous == null
                ? AutoRebufferPolicy.DEFAULT_REBUFFER_MS : previous.rebufferMs();
        int cleanStreak = previous == null ? 0 : previous.cleanStreak();
        AutoRebufferPolicy.Result resolved = AutoRebufferPolicy.resolve(
                currentMs,
                cleanStreak,
                rebufferCount,
                rebufferTotalMs,
                positionMs,
                mediaBitrate,
                bandwidthEstimate);
        int sampleCount = previous == null
                ? 1 : saturatingIncrement(previous.sampleCount());
        Entry updated = new Entry(
                ENTRY_VERSION,
                key,
                resolved.rebufferMs(),
                resolved.cleanStreak(),
                sampleCount,
                Severity.fromThreshold(resolved.rebufferMs()),
                nowEpochMs);
        entries.put(key, updated);
        trimToCapacity();
        save();
        return new RecordResult(true, updated);
    }

    synchronized void clear() {
        loaded = true;
        entries.clear();
        try {
            backend.clear();
        } catch (Throwable ignored) {
        }
    }

    synchronized int entryCount(long nowEpochMs) {
        ensureLoaded(nowEpochMs);
        if (prune(nowEpochMs)) save();
        return entries.size();
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
        boolean changed = decoded.rewriteRequired() || prune(nowEpochMs);
        if (changed) save();
    }

    private boolean prune(long nowEpochMs) {
        if (nowEpochMs <= 0) return false;
        boolean changed = false;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.valid()
                    || entry.updatedAtEpochMs() > saturatingAdd(
                    nowEpochMs, MAX_FUTURE_SKEW_MS)
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
            ExoRebufferLearningKey.Key oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Entry entry : entries.values()) {
                if (entry.updatedAtEpochMs() < oldestAt) {
                    oldestAt = entry.updatedAtEpochMs();
                    oldest = entry.key();
                }
            }
            if (oldest == null) break;
            entries.remove(oldest);
            changed = true;
        }
        return changed;
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
        Map<ExoRebufferLearningKey.Key, Entry> decoded = new LinkedHashMap<>();
        boolean rewriteRequired = false;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            Entry entry = decodeEntry(line);
            if (entry == null) {
                rewriteRequired = true;
                continue;
            }
            Entry previous = decoded.get(entry.key());
            if (previous == null
                    || entry.updatedAtEpochMs() > previous.updatedAtEpochMs()) {
                decoded.put(entry.key(), entry);
            }
            if (previous != null) rewriteRequired = true;
        }
        return new DecodeResult(true, rewriteRequired, decoded);
    }

    private static Entry decodeEntry(String line) {
        try {
            String[] values = line.split("\\|", -1);
            if (values.length != 10) return null;
            int version = Integer.parseInt(values[0]);
            ExoRebufferLearningKey.Key key = new ExoRebufferLearningKey.Key(
                    values[1],
                    PlaybackAutoContext.PathKind.valueOf(values[2]),
                    PlaybackAutoContext.Protocol.valueOf(values[3]),
                    PlaybackAutoContext.StreamKind.valueOf(values[4]));
            Entry entry = new Entry(
                    version,
                    key,
                    Integer.parseInt(values[5]),
                    Integer.parseInt(values[6]),
                    Integer.parseInt(values[7]),
                    Severity.valueOf(values[8]),
                    Long.parseLong(values[9]));
            return entry.valid() ? entry : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String encode(Iterable<Entry> values) {
        List<Entry> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(Comparator
                .comparingLong(Entry::updatedAtEpochMs)
                .reversed()
                .thenComparing(entry -> entry.key().networkDigest())
                .thenComparing(entry -> entry.key().pathKind().name())
                .thenComparing(entry -> entry.key().protocol().name())
                .thenComparing(entry -> entry.key().streamKind().name()));
        StringBuilder result = new StringBuilder(HEADER);
        for (Entry entry : sorted) {
            result.append('\n')
                    .append(entry.version()).append('|')
                    .append(entry.key().networkDigest()).append('|')
                    .append(entry.key().pathKind().name()).append('|')
                    .append(entry.key().protocol().name()).append('|')
                    .append(entry.key().streamKind().name()).append('|')
                    .append(entry.rebufferMs()).append('|')
                    .append(entry.cleanStreak()).append('|')
                    .append(entry.sampleCount()).append('|')
                    .append(entry.severity().name()).append('|')
                    .append(entry.updatedAtEpochMs());
        }
        return result.toString();
    }

    private static int saturatingIncrement(int value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, value) + 1;
    }

    private static long saturatingAdd(long first, long second) {
        if (first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    interface Backend {

        String read();

        void write(String value);

        void clear();
    }

    enum Severity {
        NONE,
        GUARDED,
        CAUTION,
        HIGH,
        CRITICAL;

        static Severity fromThreshold(int rebufferMs) {
            int normalized = AutoRebufferPolicy.normalize(rebufferMs);
            if (normalized >= 15_000) return CRITICAL;
            if (normalized >= 8_000) return HIGH;
            if (normalized >= 5_000) return CAUTION;
            if (normalized >= 3_000) return GUARDED;
            return NONE;
        }
    }

    record Lookup(
            boolean hit,
            int rebufferMs,
            int cleanStreak,
            int sampleCount,
            Severity severity) {

        static Lookup miss() {
            return new Lookup(
                    false,
                    AutoRebufferPolicy.DEFAULT_REBUFFER_MS,
                    0,
                    0,
                    Severity.GUARDED);
        }
    }

    record RecordResult(boolean recorded, Entry entry) {

        static RecordResult skipped() {
            return new RecordResult(false, null);
        }
    }

    record Entry(
            int version,
            ExoRebufferLearningKey.Key key,
            int rebufferMs,
            int cleanStreak,
            int sampleCount,
            Severity severity,
            long updatedAtEpochMs) {

        Entry {
            rebufferMs = AutoRebufferPolicy.normalize(rebufferMs);
            cleanStreak = Math.clamp(cleanStreak, 0, 1);
            sampleCount = Math.max(0, sampleCount);
            severity = severity == null
                    ? Severity.fromThreshold(rebufferMs) : severity;
        }

        boolean valid() {
            return version == ENTRY_VERSION
                    && key != null
                    && key.valid()
                    && sampleCount > 0
                    && updatedAtEpochMs > 0;
        }
    }

    private record DecodeResult(
            boolean validStore,
            boolean rewriteRequired,
            Map<ExoRebufferLearningKey.Key, Entry> entries) {
    }
}
