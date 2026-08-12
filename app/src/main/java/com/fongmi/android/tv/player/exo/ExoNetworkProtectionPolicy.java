package com.fongmi.android.tv.player.exo;

/** Product boundaries for dynamically activated EXO network protection. */
public final class ExoNetworkProtectionPolicy {

    public static final int MODE_OFF = 0;
    public static final int MODE_AUTO = 1;
    public static final float PREFERRED_MIN_SPEED = 0.97f;
    public static final float AUTO_MIN_SPEED = 0.85f;

    private ExoNetworkProtectionPolicy() {
    }

    public static Decision resolve(int mode) {
        int normalized = mode <= MODE_OFF ? MODE_OFF : MODE_AUTO;
        boolean enabled = normalized != MODE_OFF;
        return new Decision(normalized, enabled, enabled ? AUTO_MIN_SPEED : 1.0f);
    }

    public record Decision(int mode, boolean enabled, float minimumSpeed) {
    }
}
