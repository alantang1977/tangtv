package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ExoFrameSchedulingExperimentIdentityTest {

    @Test
    public void deviceAssignmentIsStableAndVersionIsolated() {
        String first = ExoFrameSchedulingExperimentIdentity.deviceDigest(
                "secret/fingerprint", "vendor", "model", 10,
                "1.0", "media3-a");
        String repeated = ExoFrameSchedulingExperimentIdentity.deviceDigest(
                "secret/fingerprint", "vendor", "model", 10,
                "1.0", "media3-a");
        String upgraded = ExoFrameSchedulingExperimentIdentity.deviceDigest(
                "secret/fingerprint", "vendor", "model", 11,
                "1.1", "media3-b");

        assertEquals(first, repeated);
        assertNotEquals(first, upgraded);
        assertTrue(ExoFrameSchedulingExperimentIdentity.validDigest(first));
        assertFalse(first.contains("fingerprint"));
        assertEquals(12,
                ExoFrameSchedulingExperimentIdentity.shortDigest(first)
                        .length());
    }

    @Test
    public void decoderAndCodecNamesAreNeverReturnedRaw() {
        String decoder = ExoFrameSchedulingExperimentIdentity.decoderDigest(
                "vendor.decoder/secret/path");
        String codec = ExoFrameSchedulingExperimentIdentity.codecDigest(
                "hvc1.secret-token");

        assertTrue(ExoFrameSchedulingExperimentIdentity.validDigest(decoder));
        assertTrue(ExoFrameSchedulingExperimentIdentity.validDigest(codec));
        assertFalse(decoder.contains("vendor"));
        assertFalse(codec.contains("secret"));
    }
}
