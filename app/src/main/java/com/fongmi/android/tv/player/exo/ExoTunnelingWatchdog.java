package com.fongmi.android.tv.player.exo;

/** Small state machine used to detect a tunneled session that never produces its first frame. */
public final class ExoTunnelingWatchdog {

    public static final long FIRST_FRAME_TIMEOUT_MS = 8_000L;

    private boolean armed;
    private boolean completed;
    private long armedAtMs;

    public void arm(long nowMs) {
        armed = true;
        completed = false;
        armedAtMs = nowMs;
    }

    public void onFirstFrame() {
        completed = true;
        armed = false;
    }

    public void onError() {
        armed = false;
    }

    public void reset() {
        armed = false;
        completed = false;
        armedAtMs = 0;
    }

    public boolean shouldTimeout(long nowMs) {
        return armed && !completed && nowMs - armedAtMs >= FIRST_FRAME_TIMEOUT_MS;
    }
}
