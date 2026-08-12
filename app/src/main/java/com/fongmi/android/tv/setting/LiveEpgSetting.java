package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LiveEpgSetting {

    private static final String KEY_URL = "live_epg_url";
    private static final String KEY_HISTORY = "live_epg_history";
    private static final int MAX_HISTORY = 20;
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    public static String getUrl() {
        return Prefers.getString(KEY_URL, "");
    }

    public static void putUrl(String url) {
        url = normalize(url);
        Prefers.put(KEY_URL, url);
        addHistory(url);
    }

    public static List<String> getHistory() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(KEY_HISTORY, "[]"), TYPE);
            return items == null ? new ArrayList<>() : new ArrayList<>(items);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void addHistory(String url) {
        url = normalize(url);
        if (url.isEmpty()) return;
        List<String> items = getHistory();
        items.remove(url);
        items.add(0, url);
        while (items.size() > MAX_HISTORY) items.remove(items.size() - 1);
        Prefers.put(KEY_HISTORY, App.gson().toJson(items));
    }

    public static void removeHistory(String url) {
        url = normalize(url);
        if (url.isEmpty()) return;
        List<String> items = getHistory();
        if (items.remove(url)) Prefers.put(KEY_HISTORY, App.gson().toJson(items));
        if (getUrl().equals(url)) Prefers.put(KEY_URL, "");
    }

    public static void replaceHistory(String oldUrl, String newUrl) {
        oldUrl = normalize(oldUrl);
        newUrl = normalize(newUrl);
        if (!oldUrl.isEmpty() && !oldUrl.equals(newUrl)) removeHistory(oldUrl);
        putUrl(newUrl);
    }

    public static void clearHistory() {
        Prefers.put(KEY_HISTORY, App.gson().toJson(Collections.emptyList()));
    }

    public static void apply(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        for (Group group : live.getGroups()) for (Channel channel : group.getChannel()) apply(live, channel);
    }

    public static void apply(Live live, Channel channel) {
        if (live == null || channel == null) return;
        channel.setDataList(Collections.emptyList());
        String template = getEffectiveUrl(live);
        if (isGlobalXmlUrl(template)) {
            channel.setEpg("");
            return;
        }
        if (!template.contains("{")) {
            channel.setEpg(template);
            return;
        }
        channel.setEpg(template.replace("{id}", channel.getTvgId()).replace("{name}", channel.getTvgName()).replace("{epg}", channel.getEpg()));
    }

    public static String getEffectiveUrl(Live live) {
        String custom = getUrl();
        return custom.isEmpty() && live != null ? live.getEpgApi() : custom;
    }

    public static List<String> getXmlUrls(Live live) {
        Set<String> items = new LinkedHashSet<>();
        String custom = getUrl();
        if (isGlobalXmlUrl(custom)) items.add(custom);
        if (live != null) items.addAll(live.getEpgXml());
        return new ArrayList<>(items);
    }

    public static boolean isGlobalXmlUrl(String url) {
        url = normalize(url);
        return !url.isEmpty() && !url.contains("{");
    }

    private static String normalize(String url) {
        return url == null ? "" : url.trim();
    }
}
