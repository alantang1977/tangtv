package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class PlaybackWebhookSender {

    private static final String EVENT_PROGRESS = "playback.progress";
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Map<String, Runnable> progressTasks = new ConcurrentHashMap<>();
    private final Map<String, Object> endpointLocks = new ConcurrentHashMap<>();

    public void sendImmediate(PlaybackRecord record) {
        if (!ViewingRecordSyncStore.isEnabled()) return;
        for (WebhookConfig config : PlaybackWebhookStore.list()) {
            if (!matches(config, record) || !config.acceptsEvent(record.event)) continue;
            enqueue(config, delivery(config, record));
        }
    }

    public void scheduleProgress(PlaybackRecord record) {
        if (!ViewingRecordSyncStore.isEnabled()) return;
        for (WebhookConfig config : PlaybackWebhookStore.list()) {
            if (!matches(config, record) || !config.acceptsEvent(EVENT_PROGRESS)) continue;
            if (config.progressIntervalSec <= 0) continue;
            if (progressTasks.containsKey(config.id)) continue;
            PlaybackRecord progress = record.withEvent(EVENT_PROGRESS);
            Runnable task = () -> {
                progressTasks.remove(config.id);
                enqueue(config, delivery(config, progress));
            };
            progressTasks.put(config.id, task);
            App.post(task, TimeUnit.SECONDS.toMillis(config.progressIntervalSec));
        }
    }

    public void sendFinalThen(PlaybackRecord record, String event) {
        if (!ViewingRecordSyncStore.isEnabled()) return;
        for (WebhookConfig config : PlaybackWebhookStore.list()) {
            if (!matches(config, record)) continue;
            cancelProgress(config.id);
            PlaybackRecord progress = record.withEvent(EVENT_PROGRESS);
            PlaybackRecord target = record.withEvent(event);
            Delivery progressDelivery = config.acceptsEvent(EVENT_PROGRESS) ? delivery(config, progress) : null;
            Delivery targetDelivery = config.acceptsEvent(event) ? delivery(config, target) : null;
            if (progressDelivery == null && targetDelivery == null) continue;
            enqueue(config, progressDelivery, targetDelivery);
        }
    }

    public void cancelAllProgress() {
        for (String id : progressTasks.keySet()) cancelProgress(id);
    }

    private void cancelProgress(String id) {
        Runnable task = progressTasks.remove(id);
        if (task != null) App.removeCallbacks(task);
    }

    private boolean matches(WebhookConfig config, PlaybackRecord record) {
        return config != null && config.isUsable() && record != null
                && ("all".equals(record.scope) || config.matchesSite(record.siteKey));
    }

    private Delivery delivery(WebhookConfig config, PlaybackRecord record) {
        JsonObject object = record.toJson(PlaybackFieldPolicy.webhook(config));
        return new Delivery(object.toString(), value(object, "eventId"), value(object, "dedupeKey"), record.configKey, record.configName);
    }

    private String value(JsonObject object, String key) {
        try {
            return object.has(key) ? object.getAsJsonPrimitive(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void enqueue(WebhookConfig config, Delivery delivery) {
        enqueue(config, delivery, null);
    }

    private void enqueue(WebhookConfig config, Delivery first, Delivery second) {
        Task.execute(() -> {
            Object lock = endpointLocks.computeIfAbsent(config.id, key -> new Object());
            synchronized (lock) {
                if (first != null && !TextUtils.isEmpty(first.payload)) deliver(config, first);
                if (second != null && !TextUtils.isEmpty(second.payload)) deliver(config, second);
            }
        });
    }

    private void deliver(WebhookConfig config, Delivery delivery) {
        int attempts = Math.max(1, Math.min(4, config.maxRetries + 1));
        String lastError = "";
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                post(config, delivery);
                PlaybackWebhookStore.markSuccess(config.id);
                return;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                SpiderDebug.log("playback-webhook", "send failed endpoint=%s attempt=%s/%s error=%s", config.displayName(), attempt + 1, attempts, lastError);
                if (attempt + 1 < attempts) sleep(attempt);
            }
        }
        PlaybackWebhookStore.markFailure(config.id, lastError);
    }

    private void post(WebhookConfig config, Delivery delivery) throws Exception {
        RequestBody body = RequestBody.create(delivery.payload, JSON);
        Request.Builder builder = new Request.Builder().url(config.url).post(body);
        builder.header("Content-Type", "application/json");
        builder.header("X-WebHTV-Timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        if (!TextUtils.isEmpty(config.token)) builder.header("X-WebHTV-Token", config.token);
        if (!TextUtils.isEmpty(delivery.eventId)) {
            builder.header("X-WebHTV-Webhook-Id", delivery.eventId);
            builder.header("Idempotency-Key", delivery.eventId);
        }
        if (!TextUtils.isEmpty(delivery.dedupeKey)) builder.header("X-WebHTV-Dedupe-Key", delivery.dedupeKey);
        PlaybackHttpHeaders.header(builder, "X-WebHTV-Config-Key", delivery.configKey);
        PlaybackHttpHeaders.header(builder, "X-WebHTV-Config-Name", delivery.configName);
        try (Response response = OkHttp.client(TIMEOUT_MS).newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class Delivery {

        private final String payload;
        private final String eventId;
        private final String dedupeKey;
        private final String configKey;
        private final String configName;

        private Delivery(String payload, String eventId, String dedupeKey, String configKey, String configName) {
            this.payload = payload;
            this.eventId = eventId;
            this.dedupeKey = dedupeKey;
            this.configKey = configKey;
            this.configName = configName;
        }
    }
}
