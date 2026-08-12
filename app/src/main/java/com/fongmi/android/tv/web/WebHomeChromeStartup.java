package com.fongmi.android.tv.web;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

public final class WebHomeChromeStartup {

    private static final String KEY_CONFIG_URL = "web_home_startup_config_url";
    private static final String KEY_HOME_KEY = "web_home_startup_home_key";
    private static final String KEY_CHROME = "web_home_startup_chrome";
    private static final String KEY_USER = "web_home_startup_user_chrome";

    private WebHomeChromeStartup() {
    }

    public static JsonObject restore(Config config) {
        if (!Setting.isWebHomeFullscreen()) return null;
        if (!matches(config)) return null;
        JsonObject object = parse(Prefers.getString(KEY_CHROME));
        String mode = WebHomeChrome.normalize(Json.safeString(object, "mode"), WebHomeChrome.EDGE);
        return WebHomeChrome.hidesNativeChrome(mode) ? object : null;
    }

    public static void remember(Config config, Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            clear();
            return;
        }
        if (config == null || TextUtils.isEmpty(config.getUrl()) || site == null || !site.hasHomePage()) {
            clear();
            return;
        }
        if (matches(config) && Prefers.getBoolean(KEY_USER)) return;
        save(config, site, chromeFromSite(site), false);
    }

    public static void remember(Config config, Site site, JsonObject chrome) {
        if (!Setting.isWebHomeFullscreen()) {
            clear();
            return;
        }
        if (config == null || TextUtils.isEmpty(config.getUrl()) || site == null || !site.hasHomePage() || chrome == null) return;
        JsonObject object = chrome.deepCopy();
        String mode = WebHomeChrome.normalize(Json.safeString(object, "mode"), WebHomeChrome.EDGE);
        if (!WebHomeChrome.NORMAL.equals(mode) && !WebHomeChrome.EDGE.equals(mode)) return;
        object.addProperty("mode", mode);
        save(config, site, object, true);
    }

    public static JsonObject resolve(Config config, Site site) {
        if (!Setting.isWebHomeFullscreen()) return normal();
        if (site == null) return new JsonObject();
        if (matches(config) && Prefers.getBoolean(KEY_USER)) return parse(Prefers.getString(KEY_CHROME));
        return chromeFromSite(site);
    }

    private static JsonObject chromeFromSite(Site site) {
        JsonObject object = site.getWebHomeChrome();
        String mode = WebHomeChrome.normalize(site.getChromeMode(), WebHomeChrome.EDGE);
        if (!object.has("mode")) object.addProperty("mode", mode);
        return object;
    }

    private static JsonObject normal() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", WebHomeChrome.NORMAL);
        return object;
    }

    private static void save(Config config, Site site, JsonObject object, boolean user) {
        if (!object.has("mode")) object.addProperty("mode", WebHomeChrome.EDGE);
        object.remove("startup");
        Prefers.put(KEY_CONFIG_URL, config.getUrl());
        Prefers.put(KEY_HOME_KEY, site.getKey());
        Prefers.put(KEY_CHROME, object.toString());
        Prefers.put(KEY_USER, user);
    }

    private static boolean matches(Config config) {
        if (config == null || TextUtils.isEmpty(config.getUrl())) return false;
        if (!TextUtils.equals(Prefers.getString(KEY_CONFIG_URL), config.getUrl())) return false;
        return TextUtils.equals(Prefers.getString(KEY_HOME_KEY), config.getHome());
    }

    private static JsonObject parse(String value) {
        try {
            JsonObject object = App.gson().fromJson(value, JsonObject.class);
            return object == null ? new JsonObject() : object;
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private static void clear() {
        Prefers.remove(KEY_CONFIG_URL);
        Prefers.remove(KEY_HOME_KEY);
        Prefers.remove(KEY_CHROME);
        Prefers.remove(KEY_USER);
    }
}
