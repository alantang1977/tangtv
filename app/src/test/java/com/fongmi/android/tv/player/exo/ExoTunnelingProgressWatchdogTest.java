package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoTunnelingProgressWatchdogTest {

    @Test
    public void requiresThreeSecondsWithoutPositionProgress() {
        ExoTunnelingProgressWatchdog watchdog = new ExoTunnelingProgressWatchdog();
        watchdog.arm(1_000, 10_000);

        assertFalse(watchdog.shouldTimeout(3_999, 10_000));
        assertTrue(watchdog.shouldTimeout(4_000, 10_000));
    }

    @Test
    public void positionProgressResetsTheStallDeadline() {
        ExoTunnelingProgressWatchdog watchdog = new ExoTunnelingProgressWatchdog();
        watchdog.arm(1_000, 10_000);
        watchdog.observe(3_000, 10_100);

        assertFalse(watchdog.shouldTimeout(5_999, 10_100));
        assertTrue(watchdog.shouldTimeout(6_000, 10_100));
    }
}
