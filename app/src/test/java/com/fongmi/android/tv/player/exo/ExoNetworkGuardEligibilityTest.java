package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkGuardEligibilityTest {

    @Test
    public void allowsExoVodWithoutResourceTypeGate() {
        assertTrue(ExoNetworkGuardEligibility.resolve(request(false, false)).eligible());
    }

    @Test
    public void preservesTunnelingAndPassthroughInsteadOfDisablingThem() {
        assertFalse(ExoNetworkGuardEligibility.resolve(request(true, false)).eligible());
        assertFalse(ExoNetworkGuardEligibility.resolve(request(false, true)).eligible());
    }

    @Test
    public void userSpeedAndUnsupportedSpeedCommandRemainUntouched() {
        assertFalse(ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(true, true, true, false, true, false, false)).eligible());
        assertFalse(ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(true, true, true, true, false, false, false)).eligible());
    }

    private static ExoNetworkGuardEligibility.Request request(boolean tunneling, boolean passthrough) {
        return new ExoNetworkGuardEligibility.Request(true, true, true, true, true, tunneling, passthrough);
    }
}
