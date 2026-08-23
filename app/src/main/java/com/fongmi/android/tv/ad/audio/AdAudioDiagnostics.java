package com.fongmi.android.tv.ad.audio;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class AdAudioDiagnostics {

    public enum Code {
        QUEUE_OVERFLOW,
        STALE_GENERATION,
        CLOCK_UNAVAILABLE,
        RULE_LOAD_FAILED,
        MATCHER_ERROR,
        SEEK_REJECTED
    }

    private final EnumMap<Code, Long> counts = new EnumMap<>(Code.class);
    private Code lastCode;

    public synchronized void record(Code code) {
        if (code == null) return;
        counts.put(code, counts.getOrDefault(code, 0L) + 1L);
        lastCode = code;
    }

    public synchronized long count(Code code) {
        return counts.getOrDefault(code, 0L);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(Collections.unmodifiableMap(new EnumMap<>(counts)), lastCode);
    }

    public record Snapshot(Map<Code, Long> counts, Code lastCode) {
        public Snapshot {
            counts = Map.copyOf(counts);
        }

        public long count(Code code) {
            return counts.getOrDefault(code, 0L);
        }
    }
}
