package com.fongmi.android.tv.player.exo;

/** Detects a tunneled player that is READY/playing but no longer advances its media position. */
public final class ExoTunnelingProgressWatchdog {

    public static final long STALL_TIMEOUT_MS = 3_000L;

    private boolean armed;
    private long lastProgressAtMs;
    private long lastPositionMs;

    public void arm(long nowMs, long positionMs) {
        armed = true;
        lastProgressAtMs = nowMs;
        lastPositionMs = positionMs;
    }

    public void observe(long nowMs, long positionMs) {
        if (!armed) arm(nowMs, positionMs);
        else if (positionMs > lastPositionMs) {
            lastPositionMs = positionMs;
            lastProgressAtMs = nowMs;
        }
    }

    public void reset() {
        armed = false;
        lastProgressAtMs = 0;
        lastPositionMs = 0;
    }

    public boolean shouldTimeout(long nowMs, long positionMs) {
        return armed && positionMs <= lastPositionMs && nowMs - lastProgressAtMs >= STALL_TIMEOUT_MS;
    }
}
