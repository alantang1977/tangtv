package com.fongmi.android.tv.player.exo;

import androidx.media3.datasource.cache.CacheDataSource;

import java.util.concurrent.atomic.AtomicLong;

final class PlaybackCacheMetrics implements CacheDataSource.EventListener {

    private static final PlaybackCacheMetrics LISTENER = new PlaybackCacheMetrics();
    private static final AtomicLong CACHED_BYTES_READ = new AtomicLong();
    private static final AtomicLong LAST_CACHE_SIZE_BYTES = new AtomicLong();
    private static final AtomicLong CACHE_IGNORED_COUNT = new AtomicLong();

    private PlaybackCacheMetrics() {
    }

    static void reset() {
        CACHED_BYTES_READ.set(0);
        LAST_CACHE_SIZE_BYTES.set(0);
        CACHE_IGNORED_COUNT.set(0);
    }

    static CacheDataSource.EventListener listener() {
        return LISTENER;
    }

    @Override
    public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
        LAST_CACHE_SIZE_BYTES.set(Math.max(0, cacheSizeBytes));
        if (cachedBytesRead > 0) CACHED_BYTES_READ.addAndGet(cachedBytesRead);
    }

    @Override
    public void onCacheIgnored(int reason) {
        CACHE_IGNORED_COUNT.incrementAndGet();
    }

    static Snapshot snapshot() {
        return new Snapshot(CACHED_BYTES_READ.get(), LAST_CACHE_SIZE_BYTES.get(), CACHE_IGNORED_COUNT.get());
    }

    record Snapshot(long cachedBytesRead, long cacheSizeBytes, long cacheIgnoredCount) {
    }
}
