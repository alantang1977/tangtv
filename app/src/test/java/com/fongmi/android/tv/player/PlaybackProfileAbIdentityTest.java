package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackProfileAbIdentityTest {

    @Test
    public void deviceDigestIsStableAndVersionIsolated() {
        String first = PlaybackProfileAbIdentity.deviceDigest(
                "secret/fingerprint", 10, "1.0", "media3-a");
        String repeated = PlaybackProfileAbIdentity.deviceDigest(
                "secret/fingerprint", 10, "1.0", "media3-a");
        String upgraded = PlaybackProfileAbIdentity.deviceDigest(
                "secret/fingerprint", 11, "1.1", "media3-b");

        assertEquals(first, repeated);
        assertNotEquals(first, upgraded);
        assertTrue(PlaybackProfileAbIdentity.validDigest(first));
        assertFalse(first.contains("fingerprint"));
        assertEquals(12,
                PlaybackProfileAbIdentity.shortDigest(first).length());
    }

    @Test
    public void decoderAndGroupIdentifiersNeverReturnRawNames() {
        String decoder = PlaybackProfileAbIdentity.decoderDigest(
                "vendor.decoder/secret/path");
        PlaybackProfileAbPolicy.GroupKey key = group(decoder);
        String group = PlaybackProfileAbIdentity.groupDigest(key);

        assertTrue(PlaybackProfileAbIdentity.validDigest(decoder));
        assertTrue(PlaybackProfileAbIdentity.validDigest(group));
        assertFalse(decoder.contains("vendor"));
        assertFalse(group.contains("secret"));
    }

    private static PlaybackProfileAbPolicy.GroupKey group(String decoder) {
        return new PlaybackProfileAbPolicy.GroupKey(
                PlaybackProfileAbIdentity.deviceDigest(
                        "fingerprint", 10, "1", "media3"),
                PlaybackAutoContext.Kernel.EXO,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                decoder,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackProfileAbPolicy.VideoMimeClass.HEVC,
                PlaybackAutoContext.HdrType.HDR10,
                PlaybackAutoContext.PathKind.REMOTE);
    }
}
