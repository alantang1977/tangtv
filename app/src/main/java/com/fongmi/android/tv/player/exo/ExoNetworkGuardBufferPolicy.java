package com.fongmi.android.tv.player.exo;

/** Derives a safety reserve above the active rebuffer-resume threshold. */
public final class ExoNetworkGuardBufferPolicy {

    static final long DIRECT_FLOOR_MS = 20_000;
    static final long LOOPBACK_FLOOR_MS = 25_000;
    static final long REBUFFER_HEADROOM_MS = 10_000;
    static final long MAX_SAFE_BUFFER_MS = 45_000;

    private ExoNetworkGuardBufferPolicy() {
    }

    public static long resolve(boolean loopback, int rebufferMs) {
        long routeFloorMs = loopback ? LOOPBACK_FLOOR_MS : DIRECT_FLOOR_MS;
        long recoveryFloorMs = Math.max(0, rebufferMs) + REBUFFER_HEADROOM_MS;
        return Math.min(MAX_SAFE_BUFFER_MS, Math.max(routeFloorMs, recoveryFloorMs));
    }
}
