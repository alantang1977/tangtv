package com.github.catvod.crawler;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DebugEventLimiter {

    private final LinkedHashMap<String, State> states;
    private final int maxKeys;

    public DebugEventLimiter(int maxKeys) {
        this.maxKeys = Math.max(1, maxKeys);
        this.states = new LinkedHashMap<>(this.maxKeys, 0.75f, true);
    }

    public synchronized Decision acquire(String key, long nowMs, long intervalMs) {
        String safeKey = key == null ? "" : key;
        long now = Math.max(0, nowMs);
        long interval = Math.max(0, intervalMs);
        State state = states.get(safeKey);
        if (state == null) {
            states.put(safeKey, new State(now));
            trim();
            return Decision.allowed(0);
        }
        long elapsed = now - state.lastAllowedMs;
        if (elapsed >= 0 && elapsed < interval) {
            state.suppressed++;
            return Decision.suppressed();
        }
        int suppressed = state.suppressed;
        state.lastAllowedMs = now;
        state.suppressed = 0;
        return Decision.allowed(suppressed);
    }

    public synchronized void clear() {
        states.clear();
    }

    private void trim() {
        while (states.size() > maxKeys) {
            Iterator<Map.Entry<String, State>> iterator = states.entrySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static final class State {

        private long lastAllowedMs;
        private int suppressed;

        private State(long lastAllowedMs) {
            this.lastAllowedMs = lastAllowedMs;
        }
    }

    public record Decision(boolean allowed, int suppressedCount) {

        private static Decision allowed(int suppressedCount) {
            return new Decision(true, Math.max(0, suppressedCount));
        }

        private static Decision suppressed() {
            return new Decision(false, 0);
        }
    }
}
