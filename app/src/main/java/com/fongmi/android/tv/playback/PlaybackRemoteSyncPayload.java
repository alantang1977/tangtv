package com.fongmi.android.tv.playback;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parser for both legacy upsert-only responses and delete-aware sync responses. */
public final class PlaybackRemoteSyncPayload {

    public final List<PlaybackProgressInput> upserts = new ArrayList<>();
    public final List<PlaybackProgressDeleteInput> deletions = new ArrayList<>();
    public String nextSince = "";

    private PlaybackRemoteSyncPayload() {
    }

    public static PlaybackRemoteSyncPayload fromJson(String text) {
        PlaybackRemoteSyncPayload payload = new PlaybackRemoteSyncPayload();
        if (empty(text)) return payload;
        JsonElement root = JsonParser.parseString(text);
        payload.parse(root);
        return payload;
    }

    public int total() {
        return upserts.size() + deletions.size();
    }

    public void limit(int maxItems) {
        if (maxItems <= 0 || total() <= maxItems) return;
        trim(deletions, maxItems);
        trim(upserts, Math.max(0, maxItems - deletions.size()));
    }

    private void parse(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            route(element.getAsJsonArray());
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String cursor = firstString(object, "nextSince", "next_since", "nextCursor", "cursor");
        if (!empty(cursor)) nextSince = cursor;
        if (isOperationWrapper(object)) {
            route(object);
            return;
        }
        boolean envelope = false;

        JsonArray changes = firstArray(object, "changes", "operations");
        if (changes != null) {
            envelope = true;
            route(changes, object);
        }

        JsonArray deleted = firstArray(object, "deleted", "deletions", "tombstones", "removed", "deletedItems");
        if (deleted != null) {
            envelope = true;
            for (JsonElement item : deleted) {
                if (item == null || item.isJsonNull()) continue;
                if (item.isJsonObject()) addDeletion(item.getAsJsonObject(), object);
                else if (item.isJsonPrimitive()) {
                    JsonObject marker = new JsonObject();
                    marker.addProperty("historyKey", item.getAsString());
                    addDeletion(marker, object);
                }
            }
        }

        JsonArray items = firstArray(object, "items", "records", "list", "upserts");
        if (items != null) {
            envelope = true;
            route(items, object);
        }

        JsonElement data = object.get("data");
        if (data != null && !data.isJsonNull()) {
            envelope = true;
            if (data.isJsonArray()) route(data.getAsJsonArray(), object);
            else if (data.isJsonObject()) {
                JsonObject nested = data.getAsJsonObject().deepCopy();
                inheritWrapperFields(nested, object);
                parse(nested);
            } else parse(data);
        }

        if (!envelope) route(object);
    }

    private void route(JsonArray array) {
        route(array, null);
    }

    private void route(JsonArray array, JsonObject parent) {
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject child = item.getAsJsonObject();
            if (parent != null) {
                child = child.deepCopy();
                inheritWrapperFields(child, parent);
            }
            route(child);
        }
    }

    private void route(JsonObject object) {
        if (isOperationWrapper(object)) {
            JsonObject item = object.getAsJsonObject("data").deepCopy();
            inheritWrapperFields(item, object);
            route(item);
        } else if (isDelete(object)) addDeletion(object);
        else upserts.add(PlaybackProgressInput.fromJson(object));
    }

    private void addDeletion(JsonObject object) {
        addDeletion(object, null);
    }

    private void addDeletion(JsonObject object, JsonObject parent) {
        JsonObject item = object;
        if (object.has("data") && object.get("data").isJsonObject()) {
            item = object.getAsJsonObject("data").deepCopy();
            inheritWrapperFields(item, object);
        }
        if (parent != null) inheritWrapperFields(item, parent);
        deletions.add(PlaybackProgressDeleteInput.fromJson(item));
    }

    private boolean isOperationWrapper(JsonObject object) {
        if (object == null || !object.has("data") || !object.get("data").isJsonObject()) return false;
        JsonObject data = object.getAsJsonObject("data");
        if (firstArray(data, "changes", "operations", "deleted", "deletions", "tombstones", "removed",
                "deletedItems", "items", "records", "list", "upserts", "data") != null) return false;
        if (scalar(object, "action") || scalar(object, "op") || scalar(object, "operation") || scalar(object, "deleted")) return true;
        String event = firstString(object, "event").toLowerCase(Locale.ROOT);
        return event.startsWith("playback.");
    }

    private static boolean scalar(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.isJsonPrimitive();
    }

    private boolean isDelete(JsonObject object) {
        if (booleanValue(object, "deleted")) return true;
        String action = firstString(object, "action", "op", "operation").toLowerCase(Locale.ROOT);
        if ("delete".equals(action) || "deleted".equals(action) || "remove".equals(action) || "removed".equals(action)) return true;
        return "playback.deleted".equals(firstString(object, "event").toLowerCase(Locale.ROOT));
    }

    private static JsonArray firstArray(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonArray()) return value.getAsJsonArray();
        }
        return null;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value != null && !value.isJsonNull() && !value.isJsonArray() && !value.isJsonObject()) return value.getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static boolean booleanValue(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return false;
            if (value.getAsJsonPrimitive().isNumber()) return value.getAsInt() != 0;
            String text = value.getAsString().trim();
            return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
        } catch (Exception e) {
            return false;
        }
    }

    private static void inherit(JsonObject target, JsonObject source, String... keys) {
        for (String key : keys) {
            if (target.has(key) || !source.has(key)) continue;
            JsonElement value = source.get(key);
            // Never attach an envelope array/object to one of its child items; doing
            // so creates a cyclic JsonTree and can overflow during deepCopy/Gson.
            if (value == null || value.isJsonArray() || value.isJsonObject()) continue;
            target.add(key, value);
        }
    }

    private static void inheritWrapperFields(JsonObject target, JsonObject source) {
        inherit(target, source, "action", "op", "operation", "event", "deleted", "scope", "confirm",
                "deletedAt", "timestamp", "updatedAt", "cid", "configKey", "configUrl");
    }

    private static <T> void trim(List<T> items, int maxItems) {
        while (items.size() > maxItems) items.remove(items.size() - 1);
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}
