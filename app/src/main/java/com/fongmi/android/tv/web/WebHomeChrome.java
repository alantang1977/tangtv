package com.fongmi.android.tv.web;

import android.text.TextUtils;

import java.util.Locale;

public final class WebHomeChrome {

    public static final String NORMAL = "normal";
    public static final String EDGE = "edge";
    public static final String IMMERSIVE = "immersive";

    private WebHomeChrome() {
    }

    public static String normalize(String mode, String fallback) {
        String value = TextUtils.isEmpty(mode) ? fallback : mode.trim().toLowerCase(Locale.ROOT);
        if (NORMAL.equals(value) || EDGE.equals(value) || IMMERSIVE.equals(value)) return value;
        return TextUtils.isEmpty(fallback) ? NORMAL : fallback;
    }

    public static boolean hidesNativeChrome(String mode) {
        return EDGE.equals(mode) || IMMERSIVE.equals(mode);
    }

    public static boolean hidesSystemBars(String mode) {
        return IMMERSIVE.equals(mode);
    }
}
