package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackAutoContextTest {

    @Test
    public void emptyContextUsesUnknownFactsWithoutAActiveSession() {
        PlaybackAutoContext context = PlaybackAutoContext.empty();

        assertFalse(context.active());
        assertEquals(PlaybackAutoContext.Kernel.UNKNOWN, context.kernel().value());
        assertEquals(PlaybackAutoContext.ValueSource.UNKNOWN, context.kernel().source());
        assertEquals(PlaybackAutoContext.Confidence.UNKNOWN, context.kernel().confidence());
        assertFalse(context.kernel().isUsable(0));
        assertEquals(PlaybackRoute.OTHER, context.path().route().value());
    }

    @Test
    public void factCarriesSourceConfidenceSampleAndSessionExpiry() {
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> fact = PlaybackAutoContext.Fact.forSession(
                PlaybackAutoContext.Kernel.MPV,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH,
                100);

        assertEquals(PlaybackAutoContext.ValueSource.PLAYER_MANAGER, fact.source());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, fact.confidence());
        assertEquals(100, fact.sampledAtElapsedMs());
        assertEquals(PlaybackAutoContext.ExpiryRule.SESSION, fact.expiryRule());
        assertTrue(fact.isUsable(100));
        assertFalse(fact.isExpired(100_000));
        assertEquals(25, fact.ageMs(125));
    }

    @Test
    public void ttlFactExpiresAtTheBoundary() {
        PlaybackAutoContext.Fact<Integer> fact = PlaybackAutoContext.Fact.withTtl(
                7,
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM,
                100,
                50);

        assertFalse(fact.isExpired(149));
        assertTrue(fact.isExpired(150));
        assertTrue(fact.isUsable(149));
        assertFalse(fact.isUsable(150));
        assertEquals(150, fact.expiresAtElapsedMs());
    }

    @Test
    public void pathFactsKeepClassificationMetadataWithoutKeepingUrl() {
        PlaybackRoute.Resolution resolution = PlaybackRoute.resolve("https://secret.example.com/private/movie.m3u8?token=secret");
        PlaybackAutoContext.PathFacts path = PlaybackAutoContext.PathFacts.fromResolution(resolution, 10);
        String summary = new PlaybackAutoContext(
                new PlaybackAutoContext.SessionToken("p-abc-1", 1),
                10,
                1,
                10,
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.Kernel.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DecodeMode.UNKNOWN),
                PlaybackAutoContext.DeviceFacts.unknown(),
                PlaybackAutoContext.ResourceFacts.unknown(),
                path,
                PlaybackAutoContext.RuntimeFacts.unknown()).logSummary();

        assertEquals(PlaybackRoute.DIRECT_REMOTE_HTTP, path.route().value());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, path.route().confidence());
        assertFalse(summary.contains("secret.example.com"));
        assertFalse(summary.contains("private"));
        assertFalse(summary.contains("token"));
    }

    @Test
    public void pathAndResourceFactsExposeBothObservedLegs() {
        PlaybackResourceClassifier.Classification classification = PlaybackResourceClassifier.classifyHls(
                "http://127.0.0.1:7777/mpv/index.m3u8",
                "https://origin.example/live.m3u8",
                "#EXTM3U\n#EXTINF:2,\na.ts\n");
        PlaybackAutoContext.PathFacts path = classification.toPathFacts(PlaybackRoute.resolve("http://127.0.0.1:7777/mpv/index.m3u8"), 20);
        PlaybackAutoContext.ResourceFacts resource = classification.toResourceFacts(20);

        assertEquals(PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK, path.playerPath().value());
        assertEquals(PlaybackAutoContext.PathKind.UNKNOWN, path.upstreamPath().value());
        assertEquals(PlaybackAutoContext.UpstreamState.OPAQUE, path.upstreamState().value());
        assertEquals(PlaybackAutoContext.StreamKind.LIVE, resource.streamKind().value());
        assertEquals(PlaybackAutoContext.ManifestKind.HLS_MEDIA, resource.manifest().value().kind());
    }

    @Test
    public void localPathHasNoUpstreamLeg() {
        PlaybackAutoContext.PathFacts path = PlaybackAutoContext.PathFacts.fromResolution(
                PlaybackRoute.resolve("file:///storage/movie.mkv"), 30);

        assertEquals(PlaybackAutoContext.PathKind.LOCAL, path.playerPath().value());
        assertEquals(PlaybackAutoContext.PathKind.LOCAL, path.upstreamPath().value());
        assertEquals(PlaybackAutoContext.UpstreamState.NOT_APPLICABLE, path.upstreamState().value());
    }

    @Test
    public void networkSnapshotNeverPrintsItsIdentityDigest() {
        String digest = PlaybackNetworkIdentityPolicy.digest(42L);
        PlaybackAutoContext.NetworkSnapshot snapshot =
                new PlaybackAutoContext.NetworkSnapshot(
                        true,
                        true,
                        false,
                        false,
                        PlaybackAutoContext.NetworkTransport.WIFI,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        digest);

        assertTrue(snapshot.toString().contains("networkIdentity=known"));
        assertFalse(snapshot.toString().contains(digest));
    }
}
