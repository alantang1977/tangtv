package com.fongmi.android.tv.remote;

import android.text.TextUtils;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

public final class RemoteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RemoteTokens() {
    }

    public static String normalizeOrigin(String serverUrl) {
        String value = serverUrl == null ? "" : serverUrl.trim();
        if (TextUtils.isEmpty(value)) return "";
        if (!value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) value = "https://" + value;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(host)) return "";
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            return scheme + "://" + host + (port >= 0 ? ":" + port : "");
        } catch (Throwable e) {
            return "";
        }
    }

    public static String randomCapability(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + hex(bytes);
    }

    public static String deriveId(String prefix, String serverOrigin, String token) {
        String hash = sha256(serverOrigin + ":" + token);
        return prefix + "_" + hash.substring(0, Math.min(24, hash.length()));
    }

    public static String deviceId(String serverOrigin, String deviceToken) {
        return deriveId("dev", serverOrigin, deviceToken);
    }

    public static String groupId(String serverOrigin, String groupToken) {
        return deriveId("grp", serverOrigin, groupToken);
    }

    public static String bindGrantId(String serverOrigin, String bindGrantToken) {
        return deriveId("bnd", serverOrigin, bindGrantToken);
    }

    public static String groupTokenHash(String serverOrigin, String groupToken) {
        return sha256(serverOrigin + ":" + groupToken);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Throwable e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(out);
    }
}
