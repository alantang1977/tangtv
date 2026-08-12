package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy;

final class ExoCacheWritePolicy {

    private ExoCacheWritePolicy() {
    }

    static Decision resolve(DiskCacheCapacityPolicy.Decision capacity, long actualCapacityBytes) {
        long actual = Math.max(0, actualCapacityBytes);
        if (capacity == null || capacity.state() == DiskCacheCapacityPolicy.State.UNAVAILABLE) return decision(false, Reason.STORAGE_UNAVAILABLE, capacity, actual);
        if (capacity.state() == DiskCacheCapacityPolicy.State.DISABLED) return decision(false, Reason.CACHE_DISABLED, capacity, actual);
        if (capacity.reclaimBytes() > 0) return decision(false, Reason.RECLAIM_REQUIRED, capacity, actual);
        if (actual > capacity.effectiveCapacityBytes()) return decision(false, Reason.CAPACITY_REBUILD_REQUIRED, capacity, actual);
        if (actual <= 0) return decision(false, Reason.NO_ACTIVE_CAPACITY, capacity, actual);
        return decision(true, Reason.READY, capacity, actual);
    }

    private static Decision decision(boolean writeAllowed, Reason reason, DiskCacheCapacityPolicy.Decision capacity, long actual) {
        if (capacity == null) return new Decision(writeAllowed, reason, actual, 0, 0, 0, 0, 0, 0, 0);
        return new Decision(writeAllowed, reason, actual, capacity.effectiveCapacityBytes(), capacity.existingCacheBytes(), capacity.availableStorageBytes(),
                capacity.totalStorageBytes(), capacity.reserveBytes(), capacity.newWriteBudgetBytes(), capacity.reclaimBytes());
    }

    enum Reason {
        READY("ready"),
        STORAGE_UNAVAILABLE("storage-unavailable"),
        CACHE_DISABLED("cache-disabled"),
        RECLAIM_REQUIRED("reclaim-required"),
        CAPACITY_REBUILD_REQUIRED("capacity-rebuild-required"),
        NO_ACTIVE_CAPACITY("no-active-capacity");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Decision(boolean writeAllowed, Reason reason, long actualCapacityBytes, long effectiveCapacityBytes, long existingCacheBytes,
                    long availableStorageBytes, long totalStorageBytes, long reserveBytes, long newWriteBudgetBytes, long reclaimBytes) {
    }
}
