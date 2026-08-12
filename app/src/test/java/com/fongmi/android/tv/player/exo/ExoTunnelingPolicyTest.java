package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoTunnelingPolicyTest {

    @Test
    public void enablesOnlyForCompleteSurfaceHardDecodePath() {
        assertTrue(ExoTunnelingPolicy.resolve(request()).enabled());
    }

    @Test
    public void rejectsTextureViewAndSoftDecode() {
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, false, true, true, true, false, false, false, false, true, false)).enabled());
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, true, false, true, true, false, false, false, false, true, false)).enabled());
    }

    @Test
    public void rejectsEffectsExternalAudioAndKnownBadDevices() {
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, true, true, true, true, true, false, false, false, true, false)).enabled());
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, true, true, true, true, false, true, false, false, true, false)).enabled());
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, true, true, true, true, false, false, true, false, true, false)).enabled());
    }

    @Test
    public void automaticModeIsTvOnlyAndFallbackIsOneShot() {
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, true, true, true, true, false, false, false, true, false, false)).enabled());
        assertFalse(ExoTunnelingPolicy.resolve(new ExoTunnelingPolicy.Request(true, true, true, true, true, false, false, false, false, true, true)).enabled());
    }

    private static ExoTunnelingPolicy.Request request() {
        return new ExoTunnelingPolicy.Request(true, true, true, true, true, false, false, false, false, true, false);
    }
}
