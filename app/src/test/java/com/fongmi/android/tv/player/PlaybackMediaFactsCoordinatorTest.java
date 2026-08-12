package com.fongmi.android.tv.player;

import androidx.media3.common.Format;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackMediaFactsCoordinatorTest {

    @Test
    public void trackSequenceAdvanceClearsDecoderUntilASynchronizedFollowUp() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMediaFactsCoordinator coordinator = new PlaybackMediaFactsCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-media-1", 10);
        assertTrue(coordinator.beginSession(session));
        Format avc = video("video/avc", "avc1.640028", 1920, 1080);
        PlayerEngine.PlaybackFactsSnapshot observed = snapshot(avc, "c2.vendor.avc.decoder");

        assertTrue(coordinator.publishEngineFacts(session, 2, observed, false, 11));
        PlaybackAutoContext.MediaFacts switching = store.snapshot().media();
        assertEquals(2, switching.trackSequence());
        assertEquals("video/avc", switching.videoTrack().mimeType().value());
        assertFalse(switching.decoder().hasEvidence());

        assertTrue(coordinator.publishEngineFacts(session, 2, observed, true, 12));
        PlaybackAutoContext.MediaFacts synchronizedFacts = store.snapshot().media();
        assertEquals(2, synchronizedFacts.decoder().trackSequence());
        assertEquals("c2.vendor.avc.decoder", synchronizedFacts.decoder().videoDecoderName().value());
        assertEquals(PlaybackAutoContext.DecodeMode.HARDWARE,
                synchronizedFacts.decoder().videoDecodeMode().value());
    }

    @Test
    public void staleTrackSequenceAndOldSessionCannotOverwriteCurrentFacts() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMediaFactsCoordinator coordinator = new PlaybackMediaFactsCoordinator(store);
        PlaybackAutoContext.SessionToken first = store.beginSession("p-media-2a", 20);
        assertTrue(coordinator.beginSession(first));
        Format avc = video("video/avc", "avc1.640028", 1920, 1080);
        assertTrue(coordinator.publishEngineFacts(first, 5, snapshot(avc, "decoder.avc"), true, 21));

        assertFalse(coordinator.publishEngineFacts(first, 4,
                snapshot(video("video/hevc", "hvc1.2.4.L153", 3840, 2160), "decoder.hevc"), true, 22));
        assertEquals("video/avc", store.snapshot().media().videoTrack().mimeType().value());

        PlaybackAutoContext.SessionToken second = store.beginSession("p-media-2b", 30);
        assertTrue(coordinator.beginSession(second));
        assertFalse(coordinator.publishEngineFacts(first, 6,
                snapshot(video("video/hevc", "hvc1.2.4.L153", 3840, 2160), "decoder.hevc"), true, 31));
        assertEquals(second, store.snapshot().session());
        assertFalse(store.snapshot().media().videoTrack().hasEvidence());
    }

    @Test
    public void renderAndDisplayUpdatesPreserveTrackAndDecoderFacts() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMediaFactsCoordinator coordinator = new PlaybackMediaFactsCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-media-3", 40);
        assertTrue(coordinator.beginSession(session));
        Format hevc = video("video/hevc", "hvc1.2.4.L153", 3840, 2160);
        assertTrue(coordinator.publishEngineFacts(session, 3, snapshot(hevc, "decoder.hevc"), true, 41));

        assertTrue(coordinator.publishRenderTarget(session, PlaybackAutoContext.RenderTarget.SURFACE_VIEW, 42));
        assertTrue(coordinator.publishDisplayFacts(session,
                new PlaybackAutoContext.DisplayMode(1, 3840, 2160, 60_000),
                new PlaybackAutoContext.DisplayMode(2, 3840, 2160, 23_976), 43));

        PlaybackAutoContext.MediaFacts media = store.snapshot().media();
        assertEquals("video/hevc", media.videoTrack().mimeType().value());
        assertEquals("decoder.hevc", media.decoder().videoDecoderName().value());
        assertEquals(PlaybackAutoContext.RenderTarget.SURFACE_VIEW, media.output().renderTarget().value());
        assertEquals(1, media.display().currentMode().value().modeId());
        assertEquals(2, media.display().requestedMode().value().modeId());
    }

    @Test
    public void duplicateRuntimeObservationDoesNotCreateAnotherRevision() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMediaFactsCoordinator coordinator = new PlaybackMediaFactsCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-media-4", 50);
        assertTrue(coordinator.beginSession(session));
        Format avc = video("video/avc", "avc1.640028", 1280, 720);
        PlayerEngine.PlaybackFactsSnapshot snapshot = snapshot(avc, "decoder.avc");

        assertTrue(coordinator.publishEngineFacts(session, 1, snapshot, true, 51));
        long revision = store.snapshot().revision();
        assertFalse(coordinator.publishEngineFacts(session, 1, snapshot, true, 52));
        assertEquals(revision, store.snapshot().revision());
    }

    @Test
    public void mismatchedDecoderClearsPreviouslyPublishedDecoderForTheSameSequence() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMediaFactsCoordinator coordinator = new PlaybackMediaFactsCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-media-5", 60);
        assertTrue(coordinator.beginSession(session));
        Format avc = video("video/avc", "avc1.640028", 1920, 1080);
        assertTrue(coordinator.publishEngineFacts(session, 1, snapshot(avc, "decoder.avc"), true, 61));

        PlayerEngine.PlaybackFactsSnapshot mismatch = new PlayerEngine.PlaybackFactsSnapshot(
                avc, null, video("video/hevc", "hvc1.2.4.L153", 3840, 2160), null,
                "decoder.hevc", "", PlayerEngine.DecoderKind.HARDWARE,
                null, "", "", false);
        assertTrue(coordinator.publishEngineFacts(session, 1, mismatch, true, 62));

        assertFalse(store.snapshot().media().decoder().hasEvidence());
        assertEquals(1, store.snapshot().media().decoder().trackSequence());
    }

    private static PlayerEngine.PlaybackFactsSnapshot snapshot(Format video, String decoderName) {
        return new PlayerEngine.PlaybackFactsSnapshot(
                video, null, video, null, decoderName, "",
                PlayerEngine.DecoderKind.HARDWARE, null, "", "", false);
    }

    private static Format video(String mime, String codecs, int width, int height) {
        return new Format.Builder()
                .setSampleMimeType(mime)
                .setCodecs(codecs)
                .setWidth(width)
                .setHeight(height)
                .build();
    }
}
