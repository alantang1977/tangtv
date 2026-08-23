package com.fongmi.android.tv.ad.audio;

import com.github.catvod.utils.Prefers;

public final class AdAudioSetting {

    private static final String KEY_ENABLED = "ad_audio_fingerprint_enabled";

    private AdAudioSetting() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }
}
