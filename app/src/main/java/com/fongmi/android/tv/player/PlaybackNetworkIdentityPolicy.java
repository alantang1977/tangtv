package com.fongmi.android.tv.player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Creates an opaque, non-logged bucket identity for the lifetime of an Android network. */
public final class PlaybackNetworkIdentityPolicy {

    public static final int DIGEST_HEX_LENGTH = 24;
    private static final String NAMESPACE = "webhtv-exo-network-learning-v1:";

    private PlaybackNetworkIdentityPolicy() {
    }

    public static String digest(long networkHandle) {
        if (networkHandle == 0) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (NAMESPACE + Long.toUnsignedString(networkHandle))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(DIGEST_HEX_LENGTH);
            for (int i = 0; i < DIGEST_HEX_LENGTH / 2; i++) {
                value.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            }
            return value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isValidDigest(String value) {
        if (value == null || value.length() != DIGEST_HEX_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= '0' && character <= '9') continue;
            if (character >= 'a' && character <= 'f') continue;
            return false;
        }
        return true;
    }
}
