package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvGpuLoadTrackerTest {

    @Test
    public void sumsFreshGpuPassesAgainstContentFrameBudget() {
        MpvGpuLoadTracker tracker = new MpvGpuLoadTracker();
        MpvGpuLoadTracker.Snapshot snapshot = tracker.update("""
                {"fresh":[
                  {"desc":"map frame (hwdec)","last":2000000},
                  {"desc":"tone map","last":3000000}
                ],"redraw":[]}
                """, 25, 60);

        assertTrue(snapshot.available());
        assertEquals(12.5, snapshot.loadPercent(), 0.001);
        assertEquals(5_000_000L, snapshot.freshNs());
        assertEquals("tone map", snapshot.dominantPass().description());
    }

    @Test
    public void includesRedrawRateWhenDisplayRunsFasterThanContent() {
        MpvGpuLoadTracker tracker = new MpvGpuLoadTracker();
        MpvGpuLoadTracker.Snapshot snapshot = tracker.update("""
                {"fresh":[{"desc":"frame","last":4000000}],
                 "redraw":[{"desc":"blend","last":1000000}]}
                """, 24, 60);

        assertEquals(13.2, snapshot.loadPercent(), 0.001);
    }

    @Test
    public void malformedOrEmptyDataIsUnavailable() {
        MpvGpuLoadTracker tracker = new MpvGpuLoadTracker();
        assertFalse(tracker.update("bad", 24, 60).available());
        assertFalse(tracker.update("{\"fresh\":[],\"redraw\":[]}", 24, 60).available());
    }
}
