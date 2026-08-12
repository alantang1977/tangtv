package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackThroughputHistoryTest {

    @Test
    public void trustedEvidenceUsesConservativeMinimumForSameNetworkAndPath() {
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        String digest = PlaybackNetworkIdentityPolicy.digest(11);

        assertTrue(history.record(
                digest,
                PlaybackAutoContext.PathKind.REMOTE,
                trustedEvidence(24_000_000L, 20_000_000L, 18_000_000L),
                1_000));

        PlaybackThroughputHistory.Match match = history.lookup(
                digest, PlaybackAutoContext.PathKind.REMOTE, 2_000);
        assertTrue(match.usable());
        assertEquals(18_000_000L, match.bitsPerSecond());
        assertEquals(1_000L, match.ageMs());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, match.confidence());
    }

    @Test
    public void networkAndRealPathAreBothPartOfTheLookupKey() {
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        String first = PlaybackNetworkIdentityPolicy.digest(21);
        String second = PlaybackNetworkIdentityPolicy.digest(22);
        assertTrue(history.record(
                first,
                PlaybackAutoContext.PathKind.LAN_PRIVATE,
                trustedEvidence(16_000_000L, 15_000_000L, 14_000_000L),
                0));

        assertFalse(history.lookup(
                second, PlaybackAutoContext.PathKind.LAN_PRIVATE, 1).usable());
        assertFalse(history.lookup(
                first, PlaybackAutoContext.PathKind.REMOTE, 1).usable());
        assertTrue(history.lookup(
                first, PlaybackAutoContext.PathKind.LAN_PRIVATE, 1).usable());
    }

    @Test
    public void evidenceExpiresAfterFiveMinutes() {
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        String digest = PlaybackNetworkIdentityPolicy.digest(31);
        assertTrue(history.record(
                digest,
                PlaybackAutoContext.PathKind.REMOTE,
                trustedEvidence(12_000_000L, 11_000_000L, 10_000_000L),
                0));

        assertTrue(history.lookup(
                digest,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackThroughputHistory.EVIDENCE_TTL_MS).usable());
        PlaybackThroughputHistory.Match expired = history.lookup(
                digest,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackThroughputHistory.EVIDENCE_TTL_MS + 1);
        assertFalse(expired.usable());
        assertEquals(PlaybackThroughputHistory.Reason.NO_MATCH,
                expired.reason());
    }

    @Test
    public void preloadContentionAndWeakLongWindowCannotSeedStartupHistory() {
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        String digest = PlaybackNetworkIdentityPolicy.digest(41);
        PlaybackThroughputHistory.Evidence contended = new PlaybackThroughputHistory.Evidence(
                20_000_000L, 18_000_000L, 16_000_000L,
                4, 15_000, 200,
                PlaybackAutoContext.Confidence.HIGH, true, true);
        PlaybackThroughputHistory.Evidence shortWindow = new PlaybackThroughputHistory.Evidence(
                20_000_000L, 18_000_000L, 16_000_000L,
                3, 10_000, 200,
                PlaybackAutoContext.Confidence.HIGH, true, false);

        assertFalse(history.record(
                digest, PlaybackAutoContext.PathKind.REMOTE, contended, 0));
        assertFalse(history.record(
                digest, PlaybackAutoContext.PathKind.REMOTE, shortWindow, 0));
        assertEquals(0, history.size());
    }

    @Test
    public void allThreeEstimatorWindowsMustBePresent() {
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        String digest = PlaybackNetworkIdentityPolicy.digest(42);
        PlaybackThroughputHistory.Evidence missingLong =
                new PlaybackThroughputHistory.Evidence(
                        20_000_000L, 18_000_000L, 0,
                        4, 15_000, 200,
                        PlaybackAutoContext.Confidence.HIGH, true, false);

        assertFalse(history.record(
                digest, PlaybackAutoContext.PathKind.REMOTE,
                missingLong, 0));
        assertEquals(0, history.size());
    }

    @Test
    public void visibleUpstreamPathWinsOverLoopbackPlayerLeg() {
        long now = 10;
        PlaybackAutoContext context = context(
                PlaybackNetworkIdentityPolicy.digest(51),
                PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE,
                now);

        assertEquals(PlaybackAutoContext.PathKind.REMOTE,
                PlaybackThroughputHistory.effectivePath(context, now));
    }

    @Test
    public void conflictingSnapshotAndLiveNetworkIdentityRejectHistory() {
        long now = 10;
        String snapshotDigest = PlaybackNetworkIdentityPolicy.digest(61);
        String liveDigest = PlaybackNetworkIdentityPolicy.digest(62);
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        PlaybackAutoContext context = context(
                snapshotDigest,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                now);
        assertTrue(history.record(
                snapshotDigest,
                PlaybackAutoContext.PathKind.REMOTE,
                trustedEvidence(18_000_000L, 16_000_000L, 14_000_000L),
                now));

        PlaybackThroughputHistory.Match match = history.lookup(
                context, liveDigest, now);

        assertFalse(match.usable());
        assertEquals(PlaybackThroughputHistory.Reason.IDENTITY_OR_PATH_UNKNOWN,
                match.reason());
    }

    private static PlaybackThroughputHistory.Evidence trustedEvidence(
            long effective,
            long shortEstimate,
            long longEstimate) {
        return new PlaybackThroughputHistory.Evidence(
                effective,
                shortEstimate,
                longEstimate,
                4,
                15_000,
                200,
                PlaybackAutoContext.Confidence.HIGH,
                true,
                false);
    }

    private static PlaybackAutoContext context(
            String digest,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState,
            long now) {
        PlaybackAutoContext.SessionToken session = new PlaybackAutoContext.SessionToken(
                "p-throughput1-1", 1);
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> player =
                PlaybackAutoContext.Fact.forSession(
                        playerPath,
                        PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                        PlaybackAutoContext.Confidence.HIGH,
                        now);
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstream =
                PlaybackAutoContext.Fact.forSession(
                        upstreamPath,
                        PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                        PlaybackAutoContext.Confidence.HIGH,
                        now);
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> state =
                PlaybackAutoContext.Fact.forSession(
                        upstreamState,
                        PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                        PlaybackAutoContext.Confidence.HIGH,
                        now);
        PlaybackAutoContext.PathFacts path = new PlaybackAutoContext.PathFacts(
                null, null, null, null, null, null,
                player, upstream, state);
        PlaybackAutoContext.NetworkSnapshot network =
                new PlaybackAutoContext.NetworkSnapshot(
                        true, true, false, false,
                        PlaybackAutoContext.NetworkTransport.WIFI,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        digest);
        PlaybackAutoContext.DeviceFacts device = new PlaybackAutoContext.DeviceFacts(
                null, null, null, null, null, null,
                PlaybackAutoContext.Fact.forSession(
                        network,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        now));
        return new PlaybackAutoContext(
                session,
                0,
                1,
                now,
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.Kernel.EXO,
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                        PlaybackAutoContext.Confidence.HIGH,
                        now),
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.DecodeMode.HARDWARE,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        now),
                device,
                PlaybackAutoContext.ResourceFacts.unknown(),
                path,
                PlaybackAutoContext.RuntimeFacts.unknown());
    }
}
