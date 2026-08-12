package com.fongmi.android.tv.player.cache;

public final class DiskCacheCapacityPolicy {

    static final int RESERVE_PERCENT = 10;
    static final long MIN_RESERVE_BYTES = 512L * 1024 * 1024;

    private DiskCacheCapacityPolicy() {
    }

    public static Decision resolve(boolean storageAvailable, long configuredCapacityBytes, long existingCacheBytes, long availableStorageBytes, long totalStorageBytes) {
        long configured = Math.max(0, configuredCapacityBytes);
        long existing = Math.max(0, existingCacheBytes);
        long available = Math.max(0, availableStorageBytes);
        long total = Math.max(0, totalStorageBytes);
        if (!hasConsistentStorageFacts(storageAvailable, existingCacheBytes, availableStorageBytes, totalStorageBytes)) {
            return new Decision(State.UNAVAILABLE, configured, existing, available, total, 0, 0, 0, 0, 0, 0);
        }

        long reserve = Math.max(MIN_RESERVE_BYTES, percentageOf(total, RESERVE_PERCENT));
        long reserveDeficit = Math.max(0, reserve - Math.min(reserve, available));
        long disposable = saturatedAdd(existing, available);
        long storageLimitedCapacity = Math.max(0, disposable - Math.min(disposable, reserve));
        long effectiveCapacity = Math.min(configured, storageLimitedCapacity);
        long newWriteBudget = Math.max(0, effectiveCapacity - Math.min(effectiveCapacity, existing));
        long reclaim = Math.max(0, existing - Math.min(existing, effectiveCapacity));

        State state;
        if (configured == 0) state = State.DISABLED;
        else if (reclaim > 0) state = State.RECLAIM_REQUIRED;
        else if (newWriteBudget > 0) state = State.SAFE;
        else state = State.NO_GROWTH;
        return new Decision(state, configured, existing, available, total, reserve, reserveDeficit, storageLimitedCapacity, effectiveCapacity, newWriteBudget, reclaim);
    }

    private static boolean hasConsistentStorageFacts(boolean storageAvailable, long existingCacheBytes, long availableStorageBytes, long totalStorageBytes) {
        if (!storageAvailable || existingCacheBytes < 0 || availableStorageBytes < 0 || totalStorageBytes <= 0) return false;
        if (existingCacheBytes > totalStorageBytes || availableStorageBytes > totalStorageBytes) return false;
        return existingCacheBytes <= totalStorageBytes - availableStorageBytes;
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static long percentageOf(long value, int percent) {
        long quotient = value / 100;
        long remainder = value % 100;
        return quotient * percent + remainder * percent / 100;
    }

    public enum State {
        SAFE,
        NO_GROWTH,
        RECLAIM_REQUIRED,
        UNAVAILABLE,
        DISABLED
    }

    public record Decision(State state, long configuredCapacityBytes, long existingCacheBytes, long availableStorageBytes, long totalStorageBytes,
                           long reserveBytes, long reserveDeficitBytes, long storageLimitedCapacityBytes, long effectiveCapacityBytes,
                           long newWriteBudgetBytes, long reclaimBytes) {
    }
}
