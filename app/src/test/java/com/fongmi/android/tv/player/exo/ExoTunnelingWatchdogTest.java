package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoTunnelingWatchdogTest {

    @Test
    public void timesOutOnlyAfterDeadlineWithoutFirstFrame() {
        ExoTunnelingWatchdog watchdog = new ExoTunnelingWatchdog();
        watchdog.arm(1_000);

        assertFalse(watchdog.shouldTimeout(8_999));
        assertTrue(watchdog.shouldTimeout(9_000));
    }

    @Test
    public void firstFrameAndErrorsCancelTimeout() {
        ExoTunnelingWatchdog watchdog = new ExoTunnelingWatchdog();
        watchdog.arm(1_000);
        watchdog.onFirstFrame();
        assertFalse(watchdog.shouldTimeout(20_000));

        watchdog.arm(2_000);
        watchdog.onError();
        assertFalse(watchdog.shouldTimeout(20_000));
    }
}
