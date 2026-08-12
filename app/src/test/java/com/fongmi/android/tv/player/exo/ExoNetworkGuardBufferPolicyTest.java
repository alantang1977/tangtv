package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExoNetworkGuardBufferPolicyTest {

    @Test
    public void loopbackKeepsLargerBaseReserve() {
        assertEquals(25_000, ExoNetworkGuardBufferPolicy.resolve(true, 3_000));
        assertEquals(20_000, ExoNetworkGuardBufferPolicy.resolve(false, 3_000));
    }

    @Test
    public void activeRebufferThresholdRaisesReserveWithCap() {
        assertEquals(30_000, ExoNetworkGuardBufferPolicy.resolve(false, 20_000));
        assertEquals(45_000, ExoNetworkGuardBufferPolicy.resolve(true, 60_000));
    }
}
