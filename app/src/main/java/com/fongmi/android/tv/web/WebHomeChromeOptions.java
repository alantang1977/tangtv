package com.fongmi.android.tv.web;

import android.graphics.Color;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.Locale;

public final class WebHomeChromeOptions {

    public static final String STYLE_AUTO = "auto";
    public static final String STYLE_LIGHT = "light";
    public static final String STYLE_DARK = "dark";
    public static final String RESTORE_NONE = "none";
    public static final String RESTORE_NATIVE = "native";

    public final String mode;
    public final String statusBarStyle;
    public final String navigationBarStyle;
    public final String restoreAffordance;
    public final int topScrim;
    public final int bottomScrim;

    private WebHomeChromeOptions(String mode, String statusBarStyle, String navigationBarStyle, String restoreAffordance, int topScrim, int bottomScrim) {
        this.mode = mode;
        this.statusBarStyle = normalizeStyle(statusBarStyle);
        this.navigationBarStyle = normalizeStyle(navigationBarStyle);
        this.restoreAffordance = TextUtils.isEmpty(restoreAffordance) ? STYLE_AUTO : restoreAffordance.trim().toLowerCase(Locale.ROOT);
        this.topScrim = topScrim;
        this.bottomScrim = bottomScrim;
    }

    public static WebHomeChromeOptions normal() {
        return new WebHomeChromeOptions(WebHomeChrome.NORMAL, STYLE_AUTO, STYLE_AUTO, STYLE_AUTO, Color.TRANSPARENT, Color.TRANSPARENT);
    }

    public static WebHomeChromeOptions legacyImmersive() {
        return new WebHomeChromeOptions(WebHomeChrome.IMMERSIVE, STYLE_AUTO, STYLE_AUTO, RESTORE_NATIVE, Color.TRANSPARENT, Color.TRANSPARENT);
    }

    public static WebHomeChromeOptions fromSite(Site site) {
        JsonObject object = site == null ? null : site.getWebHomeChrome();
        String mode = site == null ? WebHomeChrome.NORMAL : site.getChromeMode();
        if (TextUtils.isEmpty(mode)) mode = WebHomeChrome.EDGE;
        return from(object, mode);
    }

    public static WebHomeChromeOptions from(JsonObject object, String fallbackMode) {
        String mode = WebHomeChrome.normalize(Json.safeString(object, "mode"), fallbackMode);
        String status = Json.safeString(object, "statusBarStyle");
        String navigation = Json.safeString(object, "navigationBarStyle");
        String restore = Json.safeString(object, "restoreAffordance");
        int topScrim = scrim(object, "top");
        int bottomScrim = scrim(object, "bottom");
        return new WebHomeChromeOptions(mode, status, navigation, restore, topScrim, bottomScrim);
    }

    private static int scrim(JsonObject object, String key) {
        if (object == null || !object.has("scrim") || !object.get("scrim").isJsonObject()) return Color.TRANSPARENT;
        String value = Json.safeString(object.getAsJsonObject("scrim"), key);
        String lower = TextUtils.isEmpty(value) ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value) || STYLE_AUTO.equals(lower) || "transparent".equals(lower)) return Color.TRANSPARENT;
        try {
            value = value.trim();
            if (value.startsWith("#") && value.length() == 9) value = "#" + value.substring(7, 9) + value.substring(1, 7);
            return Color.parseColor(value);
        } catch (Throwable e) {
            return Color.TRANSPARENT;
        }
    }

    private static String normalizeStyle(String style) {
        String value = TextUtils.isEmpty(style) ? STYLE_AUTO : style.trim().toLowerCase(Locale.ROOT);
        return STYLE_LIGHT.equals(value) || STYLE_DARK.equals(value) ? value : STYLE_AUTO;
    }
}
