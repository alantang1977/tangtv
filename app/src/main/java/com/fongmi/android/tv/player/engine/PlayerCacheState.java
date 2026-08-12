package com.fongmi.android.tv.player.engine;

public record PlayerCacheState(
        boolean available,
        boolean enabled,
        boolean idle,
        boolean underrun,
        boolean bofCached,
        boolean eofCached,
        int bufferingState,
        long cacheDurationMs,
        long cacheEndMs,
        long readerPositionMs,
        long forwardBytes,
        long totalBytes,
        long fileBytes,
        long rawInputBytesPerSecond,
        long maxBytes,
        long maxBackBytes,
        String timeMaster,
        String timeReason,
        int cacheSeconds,
        int readaheadSeconds,
        int hysteresisSeconds,
        int cacheTimeObservedOptions) {

    private static final PlayerCacheState EMPTY = new PlayerCacheState(false, false, false, false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "unknown", "unknown", 0, 0, 0, 0);

    public PlayerCacheState {
        timeMaster = timeMaster == null ? "unknown" : timeMaster;
        timeReason = timeReason == null ? "unknown" : timeReason;
        cacheSeconds = Math.max(0, cacheSeconds);
        readaheadSeconds = Math.max(0, readaheadSeconds);
        hysteresisSeconds = Math.max(0, hysteresisSeconds);
        cacheTimeObservedOptions = Math.max(0, cacheTimeObservedOptions);
    }

    public static PlayerCacheState empty() {
        return EMPTY;
    }
}
