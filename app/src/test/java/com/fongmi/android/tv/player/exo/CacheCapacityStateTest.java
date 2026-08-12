package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CacheCapacityStateTest {

    @Test
    public void currentSessionReportsActualEvictorCapacity() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(128));

        assertEquals(mib(128), state.report(mib(512)));
        assertEquals(mib(512), state.pendingCapacityBytes());
        assertTrue(state.hasPending());
    }

    @Test
    public void safeReleaseAllowsNextSessionCapacity() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(128));
        state.report(mib(512));
        state.recordReleased();

        assertFalse(state.hasPending());
        assertEquals(mib(512), state.report(mib(512)));
        state.recordCreated(mib(512));
        assertEquals(mib(512), state.report(mib(512)));
        assertFalse(state.hasPending());
    }

    @Test
    public void zeroCapacityRemainsARealPendingShrink() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(128));

        assertEquals(mib(128), state.report(0));
        assertTrue(state.hasPending());
        assertEquals(0, state.pendingCapacityBytes());
        assertEquals(mib(128), state.actualCapacityBytes());

        state.recordReleased();
        assertFalse(state.hasPending());
        assertEquals(0, state.actualCapacityBytes());
    }

    @Test
    public void createdZeroCapacityCanGrowOnNextSafeSession() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(0);

        assertEquals(0, state.report(mib(128)));
        assertTrue(state.hasPending());
        assertEquals(mib(128), state.pendingCapacityBytes());
    }

    @Test
    public void pendingCapacityWaitsForEveryActiveSession() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(512));
        state.acquireSession();
        state.acquireSession();
        state.report(mib(128));

        assertEquals(2, state.activeSessions());
        assertFalse(state.canReleasePending());

        state.releaseSession();
        assertEquals(1, state.activeSessions());
        assertFalse(state.canReleasePending());

        state.releaseSession();
        assertEquals(0, state.activeSessions());
        assertTrue(state.canReleasePending());
    }

    @Test
    public void returningToActualCapacityClearsPendingRebuild() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(256));
        state.report(mib(128));

        assertTrue(state.hasPending());
        assertEquals(mib(256), state.report(mib(256)));
        assertFalse(state.hasPending());
    }

    private static long mib(long value) {
        return value * 1024L * 1024L;
    }
}
