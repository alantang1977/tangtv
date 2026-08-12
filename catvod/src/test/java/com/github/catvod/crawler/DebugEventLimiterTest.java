package com.github.catvod.crawler;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DebugEventLimiterTest {

    @Test
    public void aggregatesRepeatedEventsUntilIntervalExpires() {
        DebugEventLimiter limiter = new DebugEventLimiter(8);

        assertTrue(limiter.acquire("loading", 1_000, 5_000).allowed());
        assertFalse(limiter.acquire("loading", 2_000, 5_000).allowed());
        assertFalse(limiter.acquire("loading", 5_999, 5_000).allowed());

        DebugEventLimiter.Decision decision = limiter.acquire("loading", 6_000, 5_000);
        assertTrue(decision.allowed());
        assertEquals(2, decision.suppressedCount());
    }

    @Test
    public void tracksKeysIndependentlyAndAllowsClockReset() {
        DebugEventLimiter limiter = new DebugEventLimiter(8);

        assertTrue(limiter.acquire("select", 10_000, 30_000).allowed());
        assertTrue(limiter.acquire("failure", 10_001, 30_000).allowed());
        assertFalse(limiter.acquire("select", 20_000, 30_000).allowed());

        DebugEventLimiter.Decision reset = limiter.acquire("select", 100, 30_000);
        assertTrue(reset.allowed());
        assertEquals(1, reset.suppressedCount());
    }

    @Test
    public void clearForgetsSuppressedState() {
        DebugEventLimiter limiter = new DebugEventLimiter(1);
        limiter.acquire("event", 0, 1_000);
        limiter.acquire("event", 100, 1_000);

        limiter.clear();

        DebugEventLimiter.Decision decision = limiter.acquire("event", 200, 1_000);
        assertTrue(decision.allowed());
        assertEquals(0, decision.suppressedCount());
    }
}
