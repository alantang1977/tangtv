package com.fongmi.android.tv.player;

import android.os.Build;

import com.fongmi.android.tv.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Privacy-safe identities used by automatic/recommended profile validation. */
public final class PlaybackProfileAbIdentity {

    public static final int DIGEST_HEX_LENGTH = 24;

    private static final String DEVICE_NAMESPACE =
            "webhtv-playback-profile-ab-device-v1:";
    private static final String DECODER_NAMESPACE =
            "webhtv-playback-profile-ab-decoder-v1:";
    private static final String GROUP_NAMESPACE =
            "webhtv-playback-profile-ab-group-v1:";

    private PlaybackProfileAbIdentity() {
    }

    public static String currentDeviceDigest() {
        try {
            return deviceDigest(
                    Build.FINGERPRINT,
                    BuildConfig.VERSION_CODE,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.MEDIA3_VERSION);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String deviceDigest(
            String fingerprint,
            int appVersionCode,
            String appVersionName,
            String media3Version) {
        String canonical = safe(fingerprint) + '\n'
                + Math.max(0, appVersionCode) + '\n'
                + safe(appVersionName) + '\n'
                + safe(media3Version);
        return digest(DEVICE_NAMESPACE, canonical);
    }

    public static String decoderDigest(String decoderName) {
        return digest(DECODER_NAMESPACE, normalize(decoderName));
    }

    public static String groupDigest(
            PlaybackProfileAbPolicy.GroupKey key) {
        if (key == null || !key.valid()) return "";
        return digest(GROUP_NAMESPACE, key.canonical());
    }

    public static boolean validDigest(String value) {
        if (value == null || value.length() != DIGEST_HEX_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') continue;
            if (character >= 'a' && character <= 'f') continue;
            return false;
        }
        return true;
    }

    public static String shortDigest(String value) {
        return validDigest(value) ? value.substring(0, 12) : "unknown";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String digest(String namespace, String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + value)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result =
                    new StringBuilder(DIGEST_HEX_LENGTH);
            for (int index = 0;
                 index < DIGEST_HEX_LENGTH / 2; index++) {
                result.append(String.format(
                        Locale.US, "%02x", bytes[index] & 0xff));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
