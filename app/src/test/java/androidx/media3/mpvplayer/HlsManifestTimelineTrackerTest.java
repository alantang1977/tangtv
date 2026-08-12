package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HlsManifestTimelineTrackerTest {

    private static final String PLAYER = "https://proxy.invalid/index.m3u8";
    private static final String UPSTREAM = "https://origin.invalid/index.m3u8";

    @Test
    public void masterAloneDoesNotGuessScene() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        assertTrue(tracker.observe("master", hls(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\na.m3u8\n", 100)));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 200);

        assertEquals(PlaybackAutoContext.StreamKind.UNKNOWN,
                snapshot.classification().streamKind());
        assertEquals(PlaybackAutoContext.ManifestKind.HLS_MASTER,
                snapshot.classification().manifest().kind());
        assertEquals(HlsManifestTimelineTracker.SceneReason.NO_FRESH_MEDIA,
                snapshot.reason());
    }

    @Test
    public void endListVodDoesNotExpire() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("vod", hls(
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n",
                100));

        HlsManifestTimelineTracker.Snapshot snapshot =
                tracker.snapshot(fallback(), Long.MAX_VALUE);

        assertEquals(PlaybackAutoContext.StreamKind.VOD,
                snapshot.classification().streamKind());
        assertEquals(Boolean.TRUE, snapshot.classification().manifest().endList());
        assertEquals(1, snapshot.freshMediaPlaylists());
        assertEquals(HlsManifestTimelineTracker.SceneReason.ENDLIST_EVIDENCE,
                snapshot.reason());
    }

    @Test
    public void liveExpiresAtManifestCadenceBoundary() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("live", live(100));

        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                tracker.snapshot(fallback(), 6_099).classification().streamKind());
        HlsManifestTimelineTracker.Snapshot expired = tracker.snapshot(fallback(), 6_100);
        assertEquals(PlaybackAutoContext.StreamKind.UNKNOWN,
                expired.classification().streamKind());
        assertEquals(1, expired.staleMediaPlaylists());
    }

    @Test
    public void refreshExtendsLiveValidityWithoutKeepingOldObservation() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("live", live(100));
        tracker.observe("live", live(5_000));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 10_999);

        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                snapshot.classification().streamKind());
        assertEquals(5_000, snapshot.classification().observedAtElapsedMs());
        assertEquals(1, snapshot.freshMediaPlaylists());
    }

    @Test
    public void lowLatencyEvidenceDominatesOrdinaryLive() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("regular", live(100));
        tracker.observe("ll", hls(
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
                        + "#EXT-X-PART-INF:PART-TARGET=0.333\n"
                        + "#EXT-X-PART:DURATION=0.333,URI=p.m4s\n",
                200));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 300);

        assertEquals(PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE,
                snapshot.classification().streamKind());
        assertEquals(PlaybackAutoContext.TransferUnit.PART,
                snapshot.classification().transferUnit());
        assertEquals(Long.valueOf(333),
                snapshot.classification().manifest().partDurationMs());
        assertEquals(HlsManifestTimelineTracker.SceneReason.LOW_LATENCY_EVIDENCE,
                snapshot.reason());
    }

    @Test
    public void liveDominatesConflictingVodVariant() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("vod", hls(
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n",
                100));
        tracker.observe("live", live(200));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 300);

        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                snapshot.classification().streamKind());
        assertEquals(Boolean.FALSE, snapshot.classification().manifest().endList());
        assertEquals(HlsManifestTimelineTracker.SceneReason.LIVE_DOMINATES,
                snapshot.reason());
    }

    @Test
    public void masterRefreshDoesNotEraseMediaSceneAndAddsVariantCount() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("media", live(100));
        tracker.observe("master", hls(
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1000000\na.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\nb.m3u8\n",
                200));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 300);

        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                snapshot.classification().streamKind());
        assertEquals(PlaybackAutoContext.ManifestKind.HLS_MEDIA,
                snapshot.classification().manifest().kind());
        assertEquals(Integer.valueOf(2), snapshot.classification().manifest().variantCount());
    }

    @Test
    public void outOfOrderObservationForSamePlaylistIsRejected() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        assertTrue(tracker.observe("media", live(500)));
        assertFalse(tracker.observe("media", hls(
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n",
                400)));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 600);

        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                snapshot.classification().streamKind());
        assertEquals(500, snapshot.classification().observedAtElapsedMs());
    }

    @Test
    public void eventPlaylistCanTransitionFromLiveToCompletedVod() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        tracker.observe("event", hls(
                "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n"
                        + "#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n",
                100));
        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                tracker.snapshot(fallback(), 200).classification().streamKind());

        tracker.observe("event", hls(
                "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n"
                        + "#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n",
                300));

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 400);
        assertEquals(PlaybackAutoContext.StreamKind.VOD,
                snapshot.classification().streamKind());
        assertEquals(300, snapshot.classification().observedAtElapsedMs());
    }

    @Test
    public void mediaPlaylistCountIsBounded() {
        HlsManifestTimelineTracker tracker = new HlsManifestTimelineTracker();
        for (int i = 0; i < HlsManifestTimelineTracker.MAX_MEDIA_PLAYLISTS + 1; i++) {
            tracker.observe("media-" + i, hls(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n",
                    100 + i));
        }

        HlsManifestTimelineTracker.Snapshot snapshot = tracker.snapshot(fallback(), 1_000);

        assertEquals(HlsManifestTimelineTracker.MAX_MEDIA_PLAYLISTS,
                snapshot.freshMediaPlaylists());
        assertEquals(PlaybackAutoContext.StreamKind.VOD,
                snapshot.classification().streamKind());
    }

    @Test
    public void trackersAreSessionLocalAndClearable() {
        HlsManifestTimelineTracker first = new HlsManifestTimelineTracker();
        HlsManifestTimelineTracker second = new HlsManifestTimelineTracker();
        first.observe("media", live(100));

        assertEquals(PlaybackAutoContext.StreamKind.LIVE,
                first.snapshot(fallback(), 200).classification().streamKind());
        assertEquals(PlaybackAutoContext.StreamKind.UNKNOWN,
                second.snapshot(fallback(), 200).classification().streamKind());

        first.clear();
        assertEquals(PlaybackAutoContext.StreamKind.UNKNOWN,
                first.snapshot(fallback(), 200).classification().streamKind());
    }

    private static PlaybackResourceClassifier.Classification live(long observedAtMs) {
        return hls("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n",
                observedAtMs);
    }

    private static PlaybackResourceClassifier.Classification hls(
            String text,
            long observedAtMs) {
        return PlaybackResourceClassifier.classifyHls(
                PLAYER, UPSTREAM, text, observedAtMs);
    }

    private static PlaybackResourceClassifier.Classification fallback() {
        return PlaybackResourceClassifier.classifyRequest(PLAYER, "hls", "hls");
    }
}
