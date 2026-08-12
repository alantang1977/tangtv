package com.fongmi.android.tv.setting;

public final class BackgroundPlaybackPolicy {

    public static final int OFF = 0;
    public static final int ON = 1;

    private BackgroundPlaybackPolicy() {
    }

    public static int normalize(int storedMode) {
        return storedMode > OFF ? ON : OFF;
    }

    public static boolean isEnabled(int storedMode) {
        return normalize(storedMode) == ON;
    }

    public static boolean shouldUsePictureInPicture(int storedMode, boolean audioMode) {
        return isEnabled(storedMode) && !audioMode;
    }
}
