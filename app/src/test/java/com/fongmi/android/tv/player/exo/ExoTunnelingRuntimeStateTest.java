package com.fongmi.android.tv.player.exo;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoTunnelingRuntimeStateTest {

    @After
    public void tearDown() {
        ExoTunnelingRuntimeState.clearForTests();
    }

    @Test
    public void blacklistsOnlyAfterTwoFailures() {
        String key = "device|codec|video/hevc";

        assertFalse(ExoTunnelingRuntimeState.isBlacklisted(key));
        assertEquals(1, ExoTunnelingRuntimeState.recordFailure(key));
        assertFalse(ExoTunnelingRuntimeState.isBlacklisted(key));
        assertEquals(2, ExoTunnelingRuntimeState.recordFailure(key));
        assertTrue(ExoTunnelingRuntimeState.isBlacklisted(key));
    }

    @Test
    public void separateCodecsHaveIndependentFailureBudgets() {
        assertEquals(1, ExoTunnelingRuntimeState.recordFailure("device|codec-a|video/hevc"));
        assertEquals(1, ExoTunnelingRuntimeState.recordFailure("device|codec-b|video/hevc"));
        assertFalse(ExoTunnelingRuntimeState.isBlacklisted("device|codec-a|video/hevc"));
        assertFalse(ExoTunnelingRuntimeState.isBlacklisted("device|codec-b|video/hevc"));
    }
}
