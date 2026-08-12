package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HlsProxyLiveLagTrackerTest {

    @Test
    public void requestedSegmentAndNativeQueueFormConservativeLowerBound() {
        HlsProxyLiveLagTracker tracker = new HlsProxyLiveLagTracker();
        tracker.observePlaylist("playlist", List.of(
                unit("a", 6, 0, false, false),
                unit("b", 6, 6, false, false),
                unit("c", 6, 12, false, false)), 1_000);

        tracker.observeMediaRequest("a", 1_000);
        HlsProxyLiveLagTracker.Snapshot snapshot =
                tracker.snapshot(1_000, 2_000);

        assertTrue(snapshot.known());
        assertEquals(14_000, snapshot.lowerBoundMs());
        assertEquals(2_000, snapshot.nativeBufferedDurationMs());
        assertFalse(snapshot.outsideWindow());
    }

    @Test
    public void refreshMarksRequestedUnitOutsideWindowWithoutUrls() {
        HlsProxyLiveLagTracker tracker = new HlsProxyLiveLagTracker();
        tracker.observePlaylist("playlist", List.of(
                unit("a", 6, 0, false, false),
                unit("b", 6, 6, false, false),
                unit("c", 6, 12, false, false)), 1_000);
        tracker.observeMediaRequest("a", 1_000);

        tracker.observePlaylist("playlist", List.of(
                unit("b", 6, 0, false, false),
                unit("c", 6, 6, false, false),
                unit("d", 6, 12, false, false)), 2_000);
        HlsProxyLiveLagTracker.Snapshot snapshot =
                tracker.snapshot(2_000, 0);

        assertTrue(snapshot.known());
        assertTrue(snapshot.outsideWindow());
        assertEquals(18_000, snapshot.lowerBoundMs());
        assertFalse(snapshot.toString().contains("playlist"));
        assertFalse(snapshot.toString().contains("https://"));

        tracker.observePlaylist("playlist", List.of(
                unit("c", 6, 0, false, false),
                unit("d", 6, 6, false, false),
                unit("e", 6, 12, false, false)), 3_000);

        assertEquals(24_000,
                tracker.snapshot(3_000, 0).lowerBoundMs());
    }

    @Test
    public void lowLatencyPartsProvideSubsecondEvidence() {
        HlsProxyLiveLagTracker tracker = new HlsProxyLiveLagTracker();
        tracker.observePlaylist("ll", List.of(
                unit("p1", 0.333, 0, false, true),
                unit("p2", 0.333, 0.333, false, true),
                unit("p3", 0.333, 0.666, false, true)), 100);

        tracker.observeMediaRequest("p1", 100);
        HlsProxyLiveLagTracker.Snapshot snapshot =
                tracker.snapshot(100, 200);

        assertTrue(snapshot.known());
        assertEquals(866, snapshot.lowerBoundMs());
    }

    @Test
    public void byteRangeOrUnknownRequestClearsEvidence() {
        HlsProxyLiveLagTracker tracker = new HlsProxyLiveLagTracker();
        tracker.observePlaylist("range", List.of(
                unit("same", 6, 0, true, false)), 100);

        tracker.observeMediaRequest("same", 100);

        assertFalse(tracker.snapshot(100, 0).known());
    }

    @Test
    public void oldEvidenceDecaysInsteadOfCreatingFalseHighLag() {
        HlsProxyLiveLagTracker tracker = new HlsProxyLiveLagTracker();
        tracker.observePlaylist("playlist", List.of(
                unit("a", 6, 0, false, false),
                unit("b", 6, 6, false, false)), 0);
        tracker.observeMediaRequest("a", 0);

        HlsProxyLiveLagTracker.Snapshot snapshot =
                tracker.snapshot(10_000, 500);

        assertTrue(snapshot.known());
        assertEquals(500, snapshot.lowerBoundMs());
    }

    @Test
    public void completeUriRotationWithoutOverlapBecomesUnknown() {
        HlsProxyLiveLagTracker tracker = new HlsProxyLiveLagTracker();
        tracker.observePlaylist("playlist", List.of(
                unit("a?token=old", 6, 0, false, false),
                unit("b?token=old", 6, 6, false, false),
                unit("c?token=old", 6, 12, false, false)), 1_000);
        tracker.observeMediaRequest("a?token=old", 1_000);

        tracker.observePlaylist("playlist", List.of(
                unit("a?token=new", 6, 0, false, false),
                unit("b?token=new", 6, 6, false, false),
                unit("c?token=new", 6, 12, false, false)), 2_000);

        assertFalse(tracker.snapshot(2_000, 2_000).known());
    }

    private static HlsPlaylistRewriter.MediaUnit unit(
            String uri,
            double duration,
            double start,
            boolean byteRange,
            boolean partial) {
        return new HlsPlaylistRewriter.MediaUnit(
                uri, duration, start, byteRange, partial);
    }
}
