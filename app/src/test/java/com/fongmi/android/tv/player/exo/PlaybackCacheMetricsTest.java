package com.fongmi.android.tv.player.exo;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackCacheMetricsTest {

    @After
    public void tearDown() {
        PlaybackCacheMetrics.reset();
    }

    @Test
    public void accumulatesPlaybackCacheHits() {
        PlaybackCacheMetrics.listener().onCachedBytesRead(1000, 200);
        PlaybackCacheMetrics.listener().onCachedBytesRead(1200, 300);

        PlaybackCacheMetrics.Snapshot snapshot = PlaybackCacheMetrics.snapshot();
        assertEquals(500, snapshot.cachedBytesRead());
        assertEquals(1200, snapshot.cacheSizeBytes());
    }
}
