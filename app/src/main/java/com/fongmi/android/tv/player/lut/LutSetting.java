package com.fongmi.android.tv.player.lut;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LutSetting {

    private static final String KEY_ENABLED = "lut_enabled";
    private static final String KEY_PRESET = "lut_preset";
    private static final String KEY_STRENGTH = "lut_strength";
    private static final String KEY_PREVIEW_SECONDS = "lut_preview_seconds";
    private static final String KEY_FAVORITES = "lut_favorites";
    private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }

    public static String getPresetId() {
        return Prefers.getString(KEY_PRESET);
    }

    public static void putPresetId(String presetId) {
        Prefers.put(KEY_PRESET, presetId == null ? "" : presetId);
    }

    public static int getStrength() {
        int strength = Prefers.getInt(KEY_STRENGTH, 100);
        return Math.min(Math.max(strength, 25), 100);
    }

    public static void putStrength(int strength) {
        Prefers.put(KEY_STRENGTH, Math.min(Math.max(strength, 25), 100));
    }

    public static int getPreviewSeconds() {
        return Math.min(Math.max(Prefers.getInt(KEY_PREVIEW_SECONDS, 3), 1), 8);
    }

    public static void putPreviewSeconds(int seconds) {
        Prefers.put(KEY_PREVIEW_SECONDS, Math.min(Math.max(seconds, 1), 8));
    }

    public static void select(LutPreset preset) {
        putEnabled(preset != null);
        putPresetId(preset == null ? "" : preset.getId());
    }

    public static boolean isFavorite(String presetId) {
        return !TextUtils.isEmpty(presetId) && favoriteIds().contains(presetId);
    }

    public static boolean toggleFavorite(LutPreset preset) {
        return preset != null && toggleFavorite(preset.getId());
    }

    public static boolean toggleFavorite(String presetId) {
        if (TextUtils.isEmpty(presetId)) return false;
        Set<String> ids = favoriteIds();
        boolean favorite;
        if (ids.contains(presetId)) {
            ids.remove(presetId);
            favorite = false;
        } else {
            ids.add(presetId);
            favorite = true;
        }
        saveFavorites(ids);
        return favorite;
    }

    public static Set<String> favoriteIds() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(KEY_FAVORITES, "[]"), STRING_LIST);
            return new LinkedHashSet<>(items == null ? new ArrayList<>() : items);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private static void saveFavorites(Set<String> ids) {
        Prefers.put(KEY_FAVORITES, App.gson().toJson(new ArrayList<>(ids)));
    }

    public static String getSummary() {
        if (!isEnabled()) return ResUtil.getString(R.string.setting_off);
        LutPreset preset = LutStore.find(getPresetId());
        if (preset == null || TextUtils.isEmpty(preset.getName())) return ResUtil.getString(R.string.lut_missing);
        return ResUtil.getString(R.string.lut_summary, preset.getName(), getStrength());
    }

    public static String getButtonText() {
        String name = LutStore.getSelectedShortName();
        return TextUtils.isEmpty(name) ? ResUtil.getString(R.string.play_lut) : name;
    }
}
