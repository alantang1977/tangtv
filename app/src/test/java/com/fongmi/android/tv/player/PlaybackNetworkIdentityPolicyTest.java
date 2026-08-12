package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackNetworkIdentityPolicyTest {

    @Test
    public void zeroHandleRemainsUnknown() {
        assertEquals("", PlaybackNetworkIdentityPolicy.digest(0));
        assertFalse(PlaybackNetworkIdentityPolicy.isValidDigest(""));
    }

    @Test
    public void digestIsDeterministicAndContainsNoRawHandle() {
        String first = PlaybackNetworkIdentityPolicy.digest(123456789L);
        String second = PlaybackNetworkIdentityPolicy.digest(123456789L);

        assertEquals(first, second);
        assertEquals(PlaybackNetworkIdentityPolicy.DIGEST_HEX_LENGTH, first.length());
        assertTrue(PlaybackNetworkIdentityPolicy.isValidDigest(first));
        assertFalse(first.contains("123456789"));
    }

    @Test
    public void differentNetworkHandlesUseDifferentBuckets() {
        assertNotEquals(
                PlaybackNetworkIdentityPolicy.digest(100L),
                PlaybackNetworkIdentityPolicy.digest(101L));
    }
}
