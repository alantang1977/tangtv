package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoThroughputPathPolicyTest {

    @Test
    public void directRemoteCanDriveUpgrade() {
        ExoThroughputPathPolicy.Decision decision = ExoThroughputPathPolicy.resolve(
                context(path(
                        PlaybackRoute.DIRECT_REMOTE_HTTP,
                        false,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE,
                        PlaybackRouteCapabilities.ObservedLeg.PLAYER_TO_REMOTE_HTTP,
                        PlaybackRouteCapabilities.UpstreamVisibility.REQUEST_LEVEL_ONLY),
                        PlaybackAutoContext.NetworkTransport.WIFI),
                1_000,
                false);

        assertEquals(ExoThroughputPathPolicy.Trust.TRUSTED, decision.trust());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, decision.confidence());
        assertTrue(decision.upgradeEligible());
    }

    @Test
    public void directLanIsTrustedButCappedAtMediumConfidence() {
        ExoThroughputPathPolicy.Decision decision = ExoThroughputPathPolicy.resolve(
                context(path(
                        PlaybackRoute.OTHER,
                        false,
                        PlaybackAutoContext.PathKind.LAN_PRIVATE,
                        PlaybackAutoContext.PathKind.LAN_PRIVATE,
                        PlaybackAutoContext.UpstreamState.VISIBLE,
                        PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC,
                        PlaybackRouteCapabilities.UpstreamVisibility.REQUEST_LEVEL_ONLY),
                        PlaybackAutoContext.NetworkTransport.ETHERNET),
                1_000,
                false);

        assertEquals(ExoThroughputPathPolicy.Trust.TRUSTED, decision.trust());
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM, decision.confidence());
        assertTrue(decision.upgradeEligible());
    }

    @Test
    public void ownedAppServiceLocalLegCannotDriveUpgradeWithoutUpstreamSample() {
        PlaybackAutoContext.PathFacts visible = path(
                PlaybackRoute.APP_LOCAL_SERVICE,
                true,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE,
                PlaybackRouteCapabilities.ObservedLeg.APP_TO_OWNED_LOCAL_SERVICE,
                PlaybackRouteCapabilities.UpstreamVisibility.APP_SERVICE_PATH);
        PlaybackAutoContext.PathFacts upstreamMeasured = path(
                PlaybackRoute.APP_LOCAL_SERVICE,
                true,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE,
                PlaybackRouteCapabilities.ObservedLeg.PLAYER_TO_REMOTE_HTTP,
                PlaybackRouteCapabilities.UpstreamVisibility.APP_SERVICE_PATH);
        PlaybackAutoContext.PathFacts unknown = path(
                PlaybackRoute.APP_LOCAL_SERVICE,
                true,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.UNKNOWN,
                PlaybackRouteCapabilities.ObservedLeg.APP_TO_OWNED_LOCAL_SERVICE,
                PlaybackRouteCapabilities.UpstreamVisibility.APP_SERVICE_PATH);

        ExoThroughputPathPolicy.Decision localLeg = ExoThroughputPathPolicy.resolve(
                context(visible, PlaybackAutoContext.NetworkTransport.WIFI), 1_000, false);
        ExoThroughputPathPolicy.Decision trusted = ExoThroughputPathPolicy.resolve(
                context(upstreamMeasured, PlaybackAutoContext.NetworkTransport.WIFI),
                1_000,
                false);
        ExoThroughputPathPolicy.Decision limited = ExoThroughputPathPolicy.resolve(
                context(unknown, PlaybackAutoContext.NetworkTransport.WIFI), 1_000, false);

        assertEquals(ExoThroughputPathPolicy.Trust.LIMITED, localLeg.trust());
        assertEquals(ExoThroughputPathPolicy.Reason.APP_SERVICE_LOCAL_LEG,
                localLeg.reason());
        assertEquals(ExoThroughputPathPolicy.Trust.TRUSTED, trusted.trust());
        assertEquals(ExoThroughputPathPolicy.Reason.APP_SERVICE_VISIBLE, trusted.reason());
        assertEquals(ExoThroughputPathPolicy.Trust.LIMITED, limited.trust());
        assertFalse(limited.upgradeEligible());
    }

    @Test
    public void externalLoopbackAndOpaqueUpstreamAreBlocked() {
        PlaybackAutoContext.PathFacts external = path(
                PlaybackRoute.EXTERNAL_LOOPBACK_PROXY,
                true,
                PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.OPAQUE,
                PlaybackRouteCapabilities.ObservedLeg.APP_TO_LOCAL_ENDPOINT_ONLY,
                PlaybackRouteCapabilities.UpstreamVisibility.OPAQUE_EXTERNAL_PROCESS);
        PlaybackAutoContext.PathFacts opaque = path(
                PlaybackRoute.APP_LOCAL_SERVICE,
                true,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE,
                PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.UpstreamState.OPAQUE,
                PlaybackRouteCapabilities.ObservedLeg.APP_TO_OWNED_LOCAL_SERVICE,
                PlaybackRouteCapabilities.UpstreamVisibility.OPAQUE_EXTERNAL_PROCESS);

        ExoThroughputPathPolicy.Decision externalDecision =
                ExoThroughputPathPolicy.resolve(
                        context(external, PlaybackAutoContext.NetworkTransport.WIFI),
                        1_000, false);
        ExoThroughputPathPolicy.Decision opaqueDecision =
                ExoThroughputPathPolicy.resolve(
                        context(opaque, PlaybackAutoContext.NetworkTransport.WIFI),
                        1_000, false);

        assertEquals(ExoThroughputPathPolicy.Trust.BLOCKED, externalDecision.trust());
        assertEquals(ExoThroughputPathPolicy.Trust.BLOCKED, opaqueDecision.trust());
        assertFalse(externalDecision.downgradeEligible());
        assertFalse(opaqueDecision.upgradeEligible());
    }

    @Test
    public void vpnUnknownPathAndPreloadContentionCannotDriveUpgrade() {
        PlaybackAutoContext direct = context(path(
                PlaybackRoute.DIRECT_REMOTE_HTTP,
                false,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE,
                PlaybackRouteCapabilities.ObservedLeg.PLAYER_TO_REMOTE_HTTP,
                PlaybackRouteCapabilities.UpstreamVisibility.REQUEST_LEVEL_ONLY),
                PlaybackAutoContext.NetworkTransport.VPN);
        PlaybackAutoContext unknown = context(
                PlaybackAutoContext.PathFacts.unknown(),
                PlaybackAutoContext.NetworkTransport.UNKNOWN);

        ExoThroughputPathPolicy.Decision vpn =
                ExoThroughputPathPolicy.resolve(direct, 1_000, false);
        ExoThroughputPathPolicy.Decision unknownDecision =
                ExoThroughputPathPolicy.resolve(unknown, 1_000, false);
        ExoThroughputPathPolicy.Decision contended =
                ExoThroughputPathPolicy.resolve(direct, 1_000, true);

        assertEquals(ExoThroughputPathPolicy.Reason.VPN, vpn.reason());
        assertEquals(ExoThroughputPathPolicy.Trust.LIMITED, unknownDecision.trust());
        assertEquals(ExoThroughputPathPolicy.Reason.PRELOAD_CONTENTION,
                contended.reason());
        assertFalse(vpn.upgradeEligible());
        assertFalse(unknownDecision.upgradeEligible());
        assertFalse(contended.upgradeEligible());
    }

    private static PlaybackAutoContext context(
            PlaybackAutoContext.PathFacts path,
            PlaybackAutoContext.NetworkTransport transport) {
        long now = 0;
        PlaybackAutoContext.SessionToken session =
                new PlaybackAutoContext.SessionToken("p-path-1", 1);
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> network =
                transport == PlaybackAutoContext.NetworkTransport.UNKNOWN
                        ? PlaybackAutoContext.Fact.unknown(
                        PlaybackAutoContext.NetworkSnapshot.unknown())
                        : PlaybackAutoContext.Fact.withTtl(
                        new PlaybackAutoContext.NetworkSnapshot(
                                true, true, false, false, transport,
                                PlaybackAutoContext.DataSaverState.DISABLED),
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        now,
                        60_000);
        PlaybackAutoContext.DeviceFacts device = new PlaybackAutoContext.DeviceFacts(
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemorySnapshot.unknown()),
                PlaybackAutoContext.Fact.unknown(-1L),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ThermalState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PowerState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkCost.UNKNOWN),
                network);
        return new PlaybackAutoContext(
                session,
                now,
                0,
                now,
                fact(PlaybackAutoContext.Kernel.EXO),
                fact(PlaybackAutoContext.DecodeMode.HARDWARE),
                device,
                PlaybackAutoContext.ResourceFacts.unknown(),
                path,
                PlaybackAutoContext.RuntimeFacts.unknown(),
                PlaybackAutoContext.MediaFacts.unknown());
    }

    private static PlaybackAutoContext.PathFacts path(
            PlaybackRoute route,
            boolean loopback,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState,
            PlaybackRouteCapabilities.ObservedLeg observedLeg,
            PlaybackRouteCapabilities.UpstreamVisibility visibility) {
        return new PlaybackAutoContext.PathFacts(
                fact(route),
                fact(PlaybackRoute.Owner.REMOTE_ORIGIN),
                fact(loopback),
                fact(observedLeg),
                fact(visibility),
                fact(PlaybackRouteCapabilities.ControlScope.PLAYER_REQUEST_OPTIONS),
                fact(playerPath),
                fact(upstreamPath),
                fact(upstreamState));
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(T value) {
        return PlaybackAutoContext.Fact.forSession(
                value,
                PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                PlaybackAutoContext.Confidence.HIGH,
                0);
    }
}
