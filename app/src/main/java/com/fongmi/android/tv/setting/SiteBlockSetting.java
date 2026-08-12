package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SiteBlockSetting {

    public static final String KEY = "site_block_keys";
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    public static boolean isBlocked(Site site) {
        return site != null && isBlocked(site.getKey());
    }

    public static boolean isBlocked(String key) {
        return !TextUtils.isEmpty(key) && keys().contains(key);
    }

    public static boolean toggle(Site site) {
        boolean blocked = !isBlocked(site);
        setBlocked(site, blocked);
        return blocked;
    }

    public static void setBlocked(Site site, boolean blocked) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        Set<String> keys = keys();
        if (blocked) keys.add(site.getKey());
        else keys.remove(site.getKey());
        save(keys);
    }

    public static List<Site> filter(List<Site> sites, boolean includeBlocked) {
        List<Site> items = new ArrayList<>();
        if (sites == null) return items;
        Set<String> blockedKeys = includeBlocked ? new LinkedHashSet<>() : keys();
        for (Site site : sites) {
            if (site == null || site.isHide()) continue;
            if (!includeBlocked && blockedKeys.contains(site.getKey())) continue;
            items.add(site);
        }
        return items;
    }

    private static Set<String> keys() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(KEY, "[]"), TYPE);
            return new LinkedHashSet<>(items == null ? new ArrayList<>() : items);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private static void save(Set<String> keys) {
        Prefers.put(KEY, App.gson().toJson(new ArrayList<>(keys)));
    }
}
