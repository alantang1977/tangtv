package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkProtectionPolicyTest {

    @Test
    public void disabledModePreservesHardwareOutputPaths() {
        ExoNetworkProtectionPolicy.Decision decision = ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_OFF);

        assertFalse(decision.enabled());
        assertEquals(1.0f, decision.minimumSpeed(), 0.0001f);
    }

    @Test
    public void automaticModeUsesExtendedFloorAndPreferredLightBoundary() {
        ExoNetworkProtectionPolicy.Decision decision = ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_AUTO);

        assertTrue(decision.enabled());
        assertEquals(0.85f, decision.minimumSpeed(), 0.0001f);
        assertEquals(0.97f, ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED, 0.0001f);
    }

    @Test
    public void migratesLegacyPositiveModesToAutomatic() {
        assertEquals(ExoNetworkProtectionPolicy.MODE_OFF, ExoNetworkProtectionPolicy.resolve(-1).mode());
        assertEquals(ExoNetworkProtectionPolicy.MODE_AUTO, ExoNetworkProtectionPolicy.resolve(99).mode());
    }
}
