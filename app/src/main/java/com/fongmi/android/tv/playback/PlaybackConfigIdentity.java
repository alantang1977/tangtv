package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class PlaybackConfigIdentity {

    private PlaybackConfigIdentity() {
    }

    public static String currentKey() {
        return keyForUrl(VodConfig.getUrl());
    }

    public static String currentName() {
        return safe(VodConfig.getDesc());
    }

    public static String keyForCid(int cid) {
        Config config = Config.find(cid);
        if (config == null || TextUtils.isEmpty(config.getUrl())) return currentKey();
        return keyForUrl(config.getUrl());
    }

    public static String nameForCid(int cid) {
        Config config = Config.find(cid);
        if (config == null || TextUtils.isEmpty(config.getDesc())) return currentName();
        return config.getDesc();
    }

    public static int cidForKey(String configKey) {
        configKey = normalizeKey(configKey);
        if (TextUtils.isEmpty(configKey)) return 0;
        for (Config config : Config.getAll(0)) {
            if (config == null || TextUtils.isEmpty(config.getUrl())) continue;
            if (TextUtils.equals(configKey, keyForUrl(config.getUrl()))) return config.getId();
        }
        return 0;
    }

    public static String keyForUrl(String url) {
        url = safe(url).trim();
        return TextUtils.isEmpty(url) ? "" : sha256(url);
    }

    public static String normalizeKey(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format(Locale.ROOT, "%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
