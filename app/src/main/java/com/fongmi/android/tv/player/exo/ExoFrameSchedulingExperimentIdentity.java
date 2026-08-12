package com.fongmi.android.tv.player.exo;

import android.os.Build;

import com.fongmi.android.tv.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Privacy-safe identities used by frame scheduling assignments and reports. */
public final class ExoFrameSchedulingExperimentIdentity {

    public static final int DIGEST_HEX_LENGTH = 24;
    private static final String DEVICE_NAMESPACE =
            "webhtv-exo-frame-scheduling-device-v1:";
    private static final String DECODER_NAMESPACE =
            "webhtv-exo-frame-scheduling-decoder-v1:";
    private static final String CODEC_NAMESPACE =
            "webhtv-exo-frame-scheduling-codec-v1:";

    private ExoFrameSchedulingExperimentIdentity() {
    }

    public static String currentDeviceDigest() {
        try {
            return deviceDigest(
                    Build.FINGERPRINT,
                    Build.MANUFACTURER,
                    Build.MODEL,
                    BuildConfig.VERSION_CODE,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.MEDIA3_VERSION);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String deviceDigest(
            String fingerprint,
            String manufacturer,
            String model,
            int appVersionCode,
            String appVersionName,
            String media3Version) {
        String canonical = safe(fingerprint) + '\n'
                + safe(manufacturer) + '\n'
                + safe(model) + '\n'
                + Math.max(0, appVersionCode) + '\n'
                + safe(appVersionName) + '\n'
                + safe(media3Version);
        return digest(DEVICE_NAMESPACE, canonical);
    }

    static String decoderDigest(String decoderName) {
        return digest(DECODER_NAMESPACE, normalize(decoderName));
    }

    static String codecDigest(String codecs) {
        return digest(CODEC_NAMESPACE, normalize(codecs));
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

    static String shortDigest(String value) {
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
                    .digest((namespace + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(DIGEST_HEX_LENGTH);
            for (int index = 0; index < DIGEST_HEX_LENGTH / 2; index++) {
                result.append(String.format(
                        Locale.US, "%02x", bytes[index] & 0xff));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
