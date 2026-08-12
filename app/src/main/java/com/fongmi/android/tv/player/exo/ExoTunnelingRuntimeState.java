package com.fongmi.android.tv.player.exo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local tunneling failure memory; it is intentionally not persisted as a user setting. */
public final class ExoTunnelingRuntimeState {

    public static final int BLACKLIST_THRESHOLD = 2;
    private static final ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();

    private ExoTunnelingRuntimeState() {
    }

    public static int recordFailure(String key) {
        if (key == null || key.isEmpty()) return 0;
        return failures.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public static int failureCount(String key) {
        AtomicInteger count = failures.get(key);
        return count == null ? 0 : count.get();
    }

    public static boolean isBlacklisted(String key) {
        return failureCount(key) >= BLACKLIST_THRESHOLD;
    }

    static void clearForTests() {
        failures.clear();
    }
}
