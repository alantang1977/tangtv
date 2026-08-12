package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class PlaybackWebhookStore {

    private static final String KEY_CONFIG = "playback_webhook_config";
    private static final String KEY_PRIVACY = "playback_webhook_privacy_accepted";
    private static final int SUSPEND_FAILURES = 3;
    private static final Type LIST_TYPE = new TypeToken<List<WebhookConfig>>() {
    }.getType();

    private PlaybackWebhookStore() {
    }

    public static synchronized List<WebhookConfig> list() {
        try {
            List<WebhookConfig> configs = App.gson().fromJson(Prefers.getString(KEY_CONFIG), LIST_TYPE);
            return configs == null ? new ArrayList<>() : normalize(configs);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void save(List<WebhookConfig> configs) {
        Prefers.put(KEY_CONFIG, App.gson().toJson(normalize(configs)));
    }

    public static synchronized void upsert(WebhookConfig config) {
        List<WebhookConfig> configs = list();
        boolean updated = false;
        for (int i = 0; i < configs.size(); i++) {
            if (!configs.get(i).id.equals(config.id)) continue;
            configs.set(i, normalize(config));
            updated = true;
            break;
        }
        if (!updated) configs.add(normalize(config));
        save(configs);
    }

    public static synchronized void remove(String id) {
        List<WebhookConfig> configs = list();
        configs.removeIf(config -> config.id.equals(id));
        save(configs);
    }

    public static synchronized void markSuccess(String id) {
        List<WebhookConfig> configs = list();
        for (WebhookConfig config : configs) {
            if (!config.id.equals(id)) continue;
            config.failureCount = 0;
            config.lastError = "";
            config.lastFailureAt = 0;
            break;
        }
        save(configs);
    }

    public static synchronized void markFailure(String id, String error) {
        List<WebhookConfig> configs = list();
        for (WebhookConfig config : configs) {
            if (!config.id.equals(id)) continue;
            config.failureCount++;
            config.lastError = error == null ? "" : error;
            config.lastFailureAt = System.currentTimeMillis();
            if (config.failureCount >= SUSPEND_FAILURES) config.suspended = true;
            break;
        }
        save(configs);
    }

    public static synchronized int totalCount() {
        return list().size();
    }

    public static synchronized int activeCount() {
        int count = 0;
        for (WebhookConfig config : list()) if (config.isUsable()) count++;
        return count;
    }

    public static boolean isPrivacyAccepted() {
        return Prefers.getBoolean(KEY_PRIVACY);
    }

    public static void acceptPrivacy() {
        Prefers.put(KEY_PRIVACY, true);
    }

    private static List<WebhookConfig> normalize(List<WebhookConfig> configs) {
        List<WebhookConfig> result = new ArrayList<>();
        if (configs == null) return result;
        for (WebhookConfig config : configs) result.add(normalize(config));
        return result;
    }

    private static WebhookConfig normalize(WebhookConfig config) {
        if (config == null) config = new WebhookConfig();
        if (config.id == null || config.id.isEmpty()) config.id = java.util.UUID.randomUUID().toString();
        if (config.name == null) config.name = "";
        if (config.url == null) config.url = "";
        config.events = WebhookConfig.defaults();
        if (config.siteKeys == null) config.siteKeys = new ArrayList<>();
        config.fieldPreset = WebhookConfig.normalizePreset(config.fieldPreset);
        if (config.fields == null) config.fields = new ArrayList<>();
        if (config.token == null) config.token = config.secret == null ? "" : config.secret;
        if (config.secret == null) config.secret = "";
        if (config.keyId == null) config.keyId = "";
        if (config.progressIntervalSec < 0) config.progressIntervalSec = 0;
        if (config.maxRetries < 0) config.maxRetries = 0;
        if (config.maxRetries > 3) config.maxRetries = 3;
        return config;
    }
}
