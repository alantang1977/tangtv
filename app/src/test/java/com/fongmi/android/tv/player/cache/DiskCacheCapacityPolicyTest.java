package com.fongmi.android.tv.player.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DiskCacheCapacityPolicyTest {

    @Test
    public void emptyCacheUsesOnlyAvailableSpaceAboveReserve() {
        DiskCacheCapacityPolicy.Decision decision = resolve(gib(4), 0, gib(3), gib(20));

        assertEquals(DiskCacheCapacityPolicy.State.SAFE, decision.state());
        assertEquals(gib(2), decision.reserveBytes());
        assertEquals(gib(1), decision.storageLimitedCapacityBytes());
        assertEquals(gib(1), decision.effectiveCapacityBytes());
        assertEquals(gib(1), decision.newWriteBudgetBytes());
        assertEquals(0, decision.reclaimBytes());
    }

    @Test
    public void ampleStorageAllowsGrowthOnlyUpToConfiguredCapacity() {
        long total = gib(64);
        DiskCacheCapacityPolicy.Decision decision = resolve(gib(4), gib(1), gib(20), total);

        assertEquals(DiskCacheCapacityPolicy.State.SAFE, decision.state());
        assertEquals(total / 10, decision.reserveBytes());
        assertEquals(0, decision.reserveDeficitBytes());
        assertEquals(gib(4), decision.effectiveCapacityBytes());
        assertEquals(gib(3), decision.newWriteBudgetBytes());
        assertEquals(0, decision.reclaimBytes());
    }

    @Test
    public void availableSpaceEqualToReserveAllowsRetentionWithoutGrowth() {
        long total = gib(10);
        long existing = mib(256);
        DiskCacheCapacityPolicy.Decision decision = resolve(gib(1), existing, gib(1), total);

        assertEquals(DiskCacheCapacityPolicy.State.NO_GROWTH, decision.state());
        assertEquals(existing, decision.storageLimitedCapacityBytes());
        assertEquals(existing, decision.effectiveCapacityBytes());
        assertEquals(0, decision.newWriteBudgetBytes());
        assertEquals(0, decision.reclaimBytes());
    }

    @Test
    public void nearFullStorageRequestsEnoughReclaimToRestoreReserve() {
        long existing = gib(1);
        DiskCacheCapacityPolicy.Decision decision = resolve(gib(2), existing, mib(512), gib(10));

        assertEquals(DiskCacheCapacityPolicy.State.RECLAIM_REQUIRED, decision.state());
        assertEquals(gib(1), decision.reserveBytes());
        assertEquals(mib(512), decision.reserveDeficitBytes());
        assertEquals(mib(512), decision.effectiveCapacityBytes());
        assertEquals(0, decision.newWriteBudgetBytes());
        assertEquals(mib(512), decision.reclaimBytes());
    }

    @Test
    public void configuredLimitCanRequireReclaimEvenWithAmpleStorage() {
        DiskCacheCapacityPolicy.Decision decision = resolve(mib(512), gib(2), gib(20), gib(64));

        assertEquals(DiskCacheCapacityPolicy.State.RECLAIM_REQUIRED, decision.state());
        assertEquals(mib(512), decision.effectiveCapacityBytes());
        assertEquals(gib(2) - mib(512), decision.reclaimBytes());
    }

    @Test
    public void minimumReserveProtectsExtremelySmallVolumes() {
        DiskCacheCapacityPolicy.Decision decision = resolve(mib(128), mib(64), mib(128), mib(256));

        assertEquals(DiskCacheCapacityPolicy.State.RECLAIM_REQUIRED, decision.state());
        assertEquals(mib(512), decision.reserveBytes());
        assertEquals(mib(384), decision.reserveDeficitBytes());
        assertEquals(0, decision.effectiveCapacityBytes());
        assertEquals(mib(64), decision.reclaimBytes());
    }

    @Test
    public void zeroOrNegativeConfigurationDisablesCache() {
        for (long configured : new long[]{0, -1, Long.MIN_VALUE}) {
            DiskCacheCapacityPolicy.Decision decision = resolve(configured, mib(128), gib(8), gib(16));
            assertEquals(DiskCacheCapacityPolicy.State.DISABLED, decision.state());
            assertEquals(0, decision.configuredCapacityBytes());
            assertEquals(0, decision.effectiveCapacityBytes());
            assertEquals(0, decision.newWriteBudgetBytes());
            assertEquals(mib(128), decision.reclaimBytes());
        }
    }

    @Test
    public void unavailableOrContradictoryStorageFactsNeverGrowOrRequestDeletion() {
        assertUnavailable(false, mib(128), gib(1), gib(8));
        assertUnavailable(true, -1, gib(1), gib(8));
        assertUnavailable(true, mib(128), -1, gib(8));
        assertUnavailable(true, mib(128), gib(1), -1);
        assertUnavailable(true, 0, 0, 0);
        assertUnavailable(true, mib(128), gib(9), gib(8));
        assertUnavailable(true, gib(9), 0, gib(8));
        assertUnavailable(true, gib(4), gib(5), gib(8));
    }

    @Test
    public void extremeValidValuesRemainNonNegativeWithoutOverflow() {
        long total = Long.MAX_VALUE;
        long existing = Long.MAX_VALUE / 4;
        long available = Long.MAX_VALUE / 2;
        DiskCacheCapacityPolicy.Decision decision = resolve(Long.MAX_VALUE, existing, available, total);

        assertEquals(DiskCacheCapacityPolicy.State.SAFE, decision.state());
        assertEquals(total / 10, decision.reserveBytes());
        assertEquals(existing + available - decision.reserveBytes(), decision.storageLimitedCapacityBytes());
        assertEquals(decision.storageLimitedCapacityBytes(), decision.effectiveCapacityBytes());
        assertEquals(available - decision.reserveBytes(), decision.newWriteBudgetBytes());
        assertEquals(0, decision.reclaimBytes());
        assertInvariants(decision);
    }

    @Test
    public void outputsAlwaysRespectCapacityAndReclaimBounds() {
        DiskCacheCapacityPolicy.Decision[] decisions = {
                resolve(gib(4), 0, gib(20), gib(64)),
                resolve(gib(1), mib(256), gib(1), gib(10)),
                resolve(gib(2), gib(1), mib(512), gib(10)),
                resolve(mib(512), gib(2), gib(20), gib(64)),
                resolve(mib(128), mib(64), mib(128), mib(256))
        };

        for (DiskCacheCapacityPolicy.Decision decision : decisions) assertInvariants(decision);
    }

    private static DiskCacheCapacityPolicy.Decision resolve(long configured, long existing, long available, long total) {
        return DiskCacheCapacityPolicy.resolve(true, configured, existing, available, total);
    }

    private static void assertUnavailable(boolean storageAvailable, long existing, long available, long total) {
        DiskCacheCapacityPolicy.Decision decision = DiskCacheCapacityPolicy.resolve(storageAvailable, gib(1), existing, available, total);
        assertEquals(DiskCacheCapacityPolicy.State.UNAVAILABLE, decision.state());
        assertEquals(0, decision.effectiveCapacityBytes());
        assertEquals(0, decision.newWriteBudgetBytes());
        assertEquals(0, decision.reclaimBytes());
    }

    private static void assertInvariants(DiskCacheCapacityPolicy.Decision decision) {
        assertTrue(decision.effectiveCapacityBytes() >= 0);
        assertTrue(decision.newWriteBudgetBytes() >= 0);
        assertTrue(decision.reclaimBytes() >= 0);
        assertTrue(decision.effectiveCapacityBytes() <= decision.configuredCapacityBytes());
        assertTrue(decision.reclaimBytes() <= decision.existingCacheBytes());
    }

    private static long mib(long value) {
        return value * 1024 * 1024;
    }

    private static long gib(long value) {
        return value * 1024 * 1024 * 1024;
    }
}
