package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoCacheWritePolicyTest {

    @Test
    public void safeCapacityAllowsWrites() {
        ExoCacheWritePolicy.Decision decision = resolve(mib(512), mib(128), gib(8), gib(16), mib(512));

        assertTrue(decision.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.READY, decision.reason());
    }

    @Test
    public void fullCacheCanStillUseEqualSizeLruReplacement() {
        ExoCacheWritePolicy.Decision decision = resolve(mib(512), mib(512), gib(1), gib(10), mib(512));

        assertEquals(0, decision.newWriteBudgetBytes());
        assertEquals(0, decision.reclaimBytes());
        assertTrue(decision.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.READY, decision.reason());
    }

    @Test
    public void reserveDeficitRequiresRebuildAndReclaim() {
        ExoCacheWritePolicy.Decision decision = resolve(gib(2), gib(1), mib(512), gib(10), gib(2));

        assertFalse(decision.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.RECLAIM_REQUIRED, decision.reason());
        assertEquals(mib(512), decision.reclaimBytes());
    }

    @Test
    public void lowerSafeLimitPausesUntilEvictorIsRebuilt() {
        ExoCacheWritePolicy.Decision decision = resolve(mib(512), mib(128), gib(8), gib(16), gib(1));

        assertFalse(decision.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.CAPACITY_REBUILD_REQUIRED, decision.reason());
    }

    @Test
    public void unavailableStorageNeverAllowsWritesOrRequestsDeletion() {
        DiskCacheCapacityPolicy.Decision capacity = DiskCacheCapacityPolicy.resolve(false, gib(1), mib(256), 0, 0);
        ExoCacheWritePolicy.Decision decision = ExoCacheWritePolicy.resolve(capacity, gib(1));

        assertFalse(decision.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.STORAGE_UNAVAILABLE, decision.reason());
        assertEquals(0, decision.reclaimBytes());
    }

    @Test
    public void disabledOrMissingEvictorCapacityBlocksWrites() {
        ExoCacheWritePolicy.Decision disabled = resolve(0, mib(128), gib(8), gib(16), mib(128));
        ExoCacheWritePolicy.Decision missing = resolve(mib(512), 0, gib(8), gib(16), 0);

        assertFalse(disabled.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.CACHE_DISABLED, disabled.reason());
        assertFalse(missing.writeAllowed());
        assertEquals(ExoCacheWritePolicy.Reason.NO_ACTIVE_CAPACITY, missing.reason());
    }

    private static ExoCacheWritePolicy.Decision resolve(long configured, long existing, long available, long total, long actual) {
        DiskCacheCapacityPolicy.Decision capacity = DiskCacheCapacityPolicy.resolve(true, configured, existing, available, total);
        return ExoCacheWritePolicy.resolve(capacity, actual);
    }

    private static long mib(long value) {
        return value * 1024 * 1024;
    }

    private static long gib(long value) {
        return value * 1024 * 1024 * 1024;
    }
}
