package com.fongmi.android.tv.player.lut;

import android.text.TextUtils;

import java.util.Locale;

public class LutPreset {

    public enum Format {
        CUBE,
        BITMAP
    }

    private final String id;
    private final String name;
    private final String assetPath;
    private final Format format;
    private final boolean asset;

    public LutPreset(String id, String name, String assetPath, Format format) {
        this(id, name, assetPath, format, true);
    }

    public LutPreset(String id, String name, String assetPath, Format format, boolean asset) {
        this.id = id;
        this.name = name;
        this.assetPath = assetPath;
        this.format = format;
        this.asset = asset;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        if (TextUtils.isEmpty(name)) return "LUT";
        return name.length() > 10 ? name.substring(0, 10) : name;
    }

    public String getAssetPath() {
        return assetPath;
    }

    public String getPath() {
        return assetPath;
    }

    public Format getFormat() {
        return format;
    }

    public boolean isAsset() {
        return asset;
    }

    public static Format formatOf(String file) {
        String lower = file == null ? "" : file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cube")) return Format.CUBE;
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) return Format.BITMAP;
        return null;
    }
}
