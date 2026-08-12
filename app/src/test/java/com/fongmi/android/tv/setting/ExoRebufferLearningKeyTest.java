package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackNetworkIdentityPolicy;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class ExoRebufferLearningKeyTest {

    private static final long NOW = 10_000L;
    private static final String NETWORK_A = PlaybackNetworkIdentityPolicy.digest(100L);
    private static final String NETWORK_B = PlaybackNetworkIdentityPolicy.digest(101L);

    @Test
    public void networkPathProtocolAndStreamAllSeparateLearning() {
        ExoRebufferLearningKey.Key baseline = key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD);

        assertNotEquals(baseline, key(
                NETWORK_B,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD));
        assertNotEquals(baseline, key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.LAN_PRIVATE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD));
        assertNotEquals(baseline, key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.DASH,
                PlaybackAutoContext.StreamKind.VOD));
        assertNotEquals(baseline, key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.LIVE));
    }

    @Test
    public void appInternalServiceUsesVisibleUpstreamPath() {
        ExoRebufferLearningKey.Key resolved = key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD);

        assertEquals(PlaybackAutoContext.PathKind.REMOTE, resolved.pathKind());
    }

    @Test
    public void opaqueInternalUpstreamDoesNotCreateMixedBucket() {
        assertNull(key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.OPAQUE,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD));
        assertNull(key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.OPAQUE,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD));
    }

    @Test
    public void unknownDimensionsDoNotPersistLearning() {
        assertNull(key(
                "",
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD));
        assertNull(key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD));
        assertNull(key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.UNKNOWN,
                PlaybackAutoContext.StreamKind.VOD));
        assertNull(key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.UNKNOWN));
    }

    @Test
    public void localResourcesNeverUsePersistentNetworkLearning() {
        assertNull(key(
                NETWORK_A,
                PlaybackAutoContext.PathKind.LOCAL,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.NOT_APPLICABLE,
                PlaybackAutoContext.Protocol.LOCAL,
                PlaybackAutoContext.StreamKind.VOD));
    }

    @Test
    public void directCurrentNetworkDigestOverridesStaleContextDigest() {
        PlaybackAutoContext context = context(
                NETWORK_A,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD);

        ExoRebufferLearningKey.Key resolved =
                ExoRebufferLearningKey.resolve(context, NETWORK_B, NOW);

        assertEquals(NETWORK_B, resolved.networkDigest());
    }

    private static ExoRebufferLearningKey.Key key(
            String networkDigest,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind) {
        return ExoRebufferLearningKey.resolve(
                context(
                        networkDigest,
                        playerPath,
                        upstreamPath,
                        upstreamState,
                        protocol,
                        streamKind),
                networkDigest,
                NOW);
    }

    private static PlaybackAutoContext context(
            String networkDigest,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> network =
                PlaybackAutoContext.Fact.withTtl(
                        new PlaybackAutoContext.NetworkSnapshot(
                                true,
                                true,
                                false,
                                false,
                                PlaybackAutoContext.NetworkTransport.WIFI,
                                PlaybackAutoContext.DataSaverState.DISABLED,
                                networkDigest),
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW,
                        60_000);
        PlaybackAutoContext.DeviceFacts device = new PlaybackAutoContext.DeviceFacts(
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemorySnapshot.unknown()),
                PlaybackAutoContext.Fact.unknown(-1L),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ThermalState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PowerState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkCost.UNKNOWN),
                network);
        PlaybackAutoContext.ResourceFacts resource = new PlaybackAutoContext.ResourceFacts(
                fact(protocol),
                fact(streamKind),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RangeSupport.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.TransferUnit.UNKNOWN));
        PlaybackAutoContext.PathFacts path = new PlaybackAutoContext.PathFacts(
                PlaybackAutoContext.Fact.unknown(PlaybackRoute.OTHER),
                PlaybackAutoContext.Fact.unknown(PlaybackRoute.Owner.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(false),
                PlaybackAutoContext.Fact.unknown(
                        PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC),
                PlaybackAutoContext.Fact.unknown(
                        PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(
                        PlaybackRouteCapabilities.ControlScope.NONE),
                fact(playerPath),
                fact(upstreamPath),
                fact(upstreamState));
        return new PlaybackAutoContext(
                new PlaybackAutoContext.SessionToken("p-1-1", 1),
                NOW,
                1,
                NOW,
                fact(PlaybackAutoContext.Kernel.EXO),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DecodeMode.UNKNOWN),
                device,
                resource,
                path,
                PlaybackAutoContext.RuntimeFacts.unknown(),
                PlaybackAutoContext.MediaFacts.unknown());
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(T value) {
        return PlaybackAutoContext.Fact.forSession(
                value,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH,
                NOW);
    }
}
