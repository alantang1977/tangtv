package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoTargetBufferPolicyTest {

    @Test
    public void tierNeighborsSupportOneStepPressureRecovery() {
        assertEquals(mib(16), ExoTargetBufferPolicy.previousTierBytes(mib(16)));
        assertEquals(mib(64), ExoTargetBufferPolicy.previousTierBytes(mib(96)));
        assertEquals(mib(24), ExoTargetBufferPolicy.nextTierBytes(mib(16), mib(96)));
        assertEquals(mib(48), ExoTargetBufferPolicy.nextTierBytes(mib(24), mib(48)));
        assertEquals(mib(48), ExoTargetBufferPolicy.nextTierBytes(mib(64), mib(48)));
    }

    @Test
    public void averageDemandUsesThirtySecondsAndFifteenPercentHeadroom() {
        ExoTargetBufferPolicy.Decision eightMbps = resolve(demand(8_000_000L, 8_000_000L), 0, budget(1024, false));
        ExoTargetBufferPolicy.Decision twentyMbps = resolve(demand(20_000_000L, 20_000_000L), 0, budget(1024, false));

        assertEquals(34_500_000L, eightMbps.averageDemandBytes());
        assertEquals(mib(48), eightMbps.targetBytes());
        assertEquals(86_250_000L, twentyMbps.averageDemandBytes());
        assertEquals(mib(96), twentyMbps.targetBytes());
    }

    @Test
    public void reliableBurstDemandCanSelectHigherTier() {
        ExoTargetBufferPolicy.Decision decision = resolve(demand(4_000_000L, 80_000_000L), 0, budget(1024, false));

        assertEquals(100_000_000L, decision.burstDemandBytes());
        assertEquals(mib(96), decision.targetBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.MEDIA_DEMAND, decision.limitingFactor());
    }

    @Test
    public void lowConfidenceDemandCannotExpandTarget() {
        ExoTargetBufferPolicy.MediaDemand demand = new ExoTargetBufferPolicy.MediaDemand(
                300_000_000L,
                ExoTargetBufferPolicy.DemandSource.OBSERVED_LOAD,
                PlaybackAutoContext.Confidence.LOW,
                600_000_000L,
                ExoTargetBufferPolicy.DemandSource.OBSERVED_LOAD,
                PlaybackAutoContext.Confidence.LOW);

        ExoTargetBufferPolicy.Decision decision = resolve(demand, 0, budget(1024, false));

        assertEquals(mib(16), decision.targetBytes());
        assertEquals(0, decision.payloadDemandBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.UNKNOWN_MEDIA, decision.limitingFactor());
    }

    @Test
    public void appOwnedProxyVodUsesConservativeUnknownMediaFallback() {
        ExoTargetBufferPolicy.UnknownMediaFallback fallback =
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(PlaybackAutoContext.Protocol.UNKNOWN, null),
                        appProxyPath(),
                        true,
                        false,
                        0);
        ExoTargetBufferPolicy.Decision decision = ExoTargetBufferPolicy.resolve(
                ExoTargetBufferPolicy.MediaDemand.unknown(),
                0,
                budget(1024, false),
                fallback,
                PlaybackAutoContext.DeviceFacts.unknown(),
                0);

        assertEquals(ExoTargetBufferPolicy.UnknownMediaFallback.APP_PROXY_VOD, fallback);
        assertEquals(mib(96), decision.targetBytes());
        assertEquals(mib(96), decision.mediaTierBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.UNKNOWN_MEDIA, decision.limitingFactor());
    }

    @Test
    public void appProxyFallbackStillObeysUserAndLowRamCapacityCaps() {
        ExoTargetBufferPolicy.Decision userCapped = ExoTargetBufferPolicy.resolve(
                ExoTargetBufferPolicy.MediaDemand.unknown(),
                mib(64),
                budget(1024, false),
                ExoTargetBufferPolicy.UnknownMediaFallback.APP_PROXY_VOD,
                PlaybackAutoContext.DeviceFacts.unknown(),
                0);
        ExoTargetBufferPolicy.Decision lowRam = ExoTargetBufferPolicy.resolve(
                ExoTargetBufferPolicy.MediaDemand.unknown(),
                0,
                budget(128, true),
                ExoTargetBufferPolicy.UnknownMediaFallback.APP_PROXY_VOD,
                PlaybackAutoContext.DeviceFacts.unknown(),
                0);

        assertEquals(mib(64), userCapped.targetBytes());
        assertEquals(mib(24), lowRam.targetBytes());
    }

    @Test
    public void adaptiveOrSegmentedProxyDoesNotUseUnknownVodFallback() {
        assertEquals(
                ExoTargetBufferPolicy.UnknownMediaFallback.MINIMUM,
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(PlaybackAutoContext.Protocol.UNKNOWN, null),
                        appProxyPath(),
                        true,
                        true,
                        0));
        assertEquals(
                ExoTargetBufferPolicy.UnknownMediaFallback.MINIMUM,
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(PlaybackAutoContext.Protocol.HLS, 1),
                        appProxyPath(),
                        true,
                        false,
                        0));
        assertEquals(
                ExoTargetBufferPolicy.UnknownMediaFallback.MINIMUM,
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(PlaybackAutoContext.Protocol.UNKNOWN, 2),
                        appProxyPath(),
                        true,
                        false,
                        0));
        assertEquals(
                ExoTargetBufferPolicy.UnknownMediaFallback.MINIMUM,
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(
                                PlaybackAutoContext.Protocol.UNKNOWN,
                                null,
                                PlaybackAutoContext.TransferUnit.SEGMENT,
                                PlaybackAutoContext.ManifestKind.UNKNOWN),
                        appProxyPath(),
                        true,
                        false,
                        0));
        assertEquals(
                ExoTargetBufferPolicy.UnknownMediaFallback.MINIMUM,
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(
                                PlaybackAutoContext.Protocol.UNKNOWN,
                                1,
                                PlaybackAutoContext.TransferUnit.UNKNOWN,
                                PlaybackAutoContext.ManifestKind.HLS_MEDIA),
                        appProxyPath(),
                        true,
                        false,
                        0));
        assertEquals(
                ExoTargetBufferPolicy.UnknownMediaFallback.MINIMUM,
                ExoTargetBufferPolicy.unknownMediaFallback(
                        proxyVodResource(PlaybackAutoContext.Protocol.UNKNOWN, null),
                        appProxyPath(),
                        false,
                        false,
                        0));
    }

    @Test
    public void media3GuardCapsVeryHighBitrateDemandAt192Mib() {
        ExoTargetBufferPolicy.Decision decision = resolve(demand(100_000_000L, 100_000_000L), 0, budget(2048, false));

        assertTrue(decision.payloadDemandBytes() > ExoTargetBufferPolicy.GUARD_TARGET_BYTES);
        assertEquals(mib(192), decision.targetBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.GUARD, decision.limitingFactor());
    }

    @Test
    public void configuredCapacityRemainsAnUpperBound() {
        ExoTargetBufferPolicy.Decision decision = resolve(demand(20_000_000L, 20_000_000L), mib(64), budget(1024, false));

        assertEquals(mib(64), decision.targetBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.USER_LIMIT, decision.limitingFactor());
    }

    @Test
    public void configured256MibStillCannotBypassGuard() {
        ExoTargetBufferPolicy.Decision decision = resolve(demand(100_000_000L, 100_000_000L), mib(256), budget(2048, false));

        assertEquals(mib(192), decision.targetBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.GUARD, decision.limitingFactor());
    }

    @Test
    public void legacyHeapBudgetIsRoundedDownToSafeTier() {
        ExoTargetBufferPolicy.Decision normal256 = resolve(demand(20_000_000L, 20_000_000L), 0, budget(256, false));
        ExoTargetBufferPolicy.Decision lowRam128 = resolve(demand(20_000_000L, 20_000_000L), 0, budget(128, true));

        assertEquals(mib(64), normal256.targetBytes());
        assertEquals(mib(24), lowRam128.targetBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.MEMORY_BUDGET, normal256.limitingFactor());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.MEMORY_BUDGET, lowRam128.limitingFactor());
    }

    @Test
    public void currentJavaHeadroomSubtractsReserve() {
        PlaybackAutoContext.DeviceFacts device = device(
                snapshotFact(memorySnapshot(
                        mibLong(1024),
                        mibLong(80),
                        false,
                        mibLong(4096),
                        mibLong(1024),
                        false), 0, 1_000),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN));

        ExoTargetBufferPolicy.Decision decision = ExoTargetBufferPolicy.resolve(
                demand(20_000_000L, 20_000_000L),
                0,
                budget(1024, false),
                device,
                100);

        assertEquals(mib(16), decision.javaHeadroomBudgetBytes());
        assertEquals(mib(16), decision.targetBytes());
        assertTrue(decision.memorySnapshotUsable());
    }

    @Test
    public void systemAvailableMinusThresholdCapsSessionBudget() {
        PlaybackAutoContext.DeviceFacts device = device(
                snapshotFact(memorySnapshot(
                        mibLong(1024),
                        mibLong(512),
                        false,
                        mibLong(150),
                        mibLong(100),
                        false), 0, 1_000),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN));

        ExoTargetBufferPolicy.Decision decision = ExoTargetBufferPolicy.resolve(
                demand(20_000_000L, 20_000_000L),
                0,
                budget(1024, false),
                device,
                100);

        assertEquals(mib(50), decision.systemBudgetBytes());
        assertEquals(mib(48), decision.targetBytes());
    }

    @Test
    public void criticalPressureAndSystemLowMemoryUseLowestTier() {
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> critical =
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.MemoryPressure.CRITICAL,
                        PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        1_000);
        PlaybackAutoContext.DeviceFacts pressureDevice = device(
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemorySnapshot.unknown()),
                critical);
        PlaybackAutoContext.DeviceFacts lowMemoryDevice = device(
                snapshotFact(memorySnapshot(
                        mibLong(1024),
                        mibLong(512),
                        false,
                        mibLong(4096),
                        mibLong(1024),
                        true), 0, 1_000),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN));

        ExoTargetBufferPolicy.Decision pressureDecision = ExoTargetBufferPolicy.resolve(
                demand(100_000_000L, 100_000_000L), 0, budget(1024, false), pressureDevice, 100);
        ExoTargetBufferPolicy.Decision lowMemoryDecision = ExoTargetBufferPolicy.resolve(
                demand(100_000_000L, 100_000_000L), 0, budget(1024, false), lowMemoryDevice, 100);

        assertEquals(mib(16), pressureDecision.targetBytes());
        assertEquals(mib(16), lowMemoryDecision.targetBytes());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.MEMORY_PRESSURE, pressureDecision.limitingFactor());
        assertEquals(ExoTargetBufferPolicy.LimitingFactor.MEMORY_PRESSURE, lowMemoryDecision.limitingFactor());
    }

    @Test
    public void expiredMemoryFactsAreIgnoredInsteadOfBecomingZero() {
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> expiredSnapshot =
                snapshotFact(memorySnapshot(
                        mibLong(1024),
                        mibLong(64),
                        false,
                        mibLong(100),
                        mibLong(100),
                        true), 0, 100);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> expiredPressure =
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.MemoryPressure.CRITICAL,
                        PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        100);

        ExoTargetBufferPolicy.Decision decision = ExoTargetBufferPolicy.resolve(
                demand(20_000_000L, 20_000_000L),
                0,
                budget(1024, false),
                device(expiredSnapshot, expiredPressure),
                100);

        assertEquals(mib(96), decision.targetBytes());
        assertFalse(decision.memorySnapshotUsable());
        assertFalse(decision.memoryPressureUsable());
    }

    @Test
    public void tierBoundariesRoundDemandUpAndCapacityDown() {
        int[] tiers = {16, 24, 48, 64, 96, 128, 192};
        for (int tier : tiers) {
            assertEquals(mib(tier), ExoTargetBufferPolicy.tierForDemand(mibLong(tier)));
            assertEquals(mib(tier), ExoTargetBufferPolicy.tierForCapacity(mibLong(tier)));
        }
        assertEquals(mib(24), ExoTargetBufferPolicy.tierForDemand(mibLong(16) + 1));
        assertEquals(mib(16), ExoTargetBufferPolicy.tierForCapacity(mibLong(24) - 1));
        assertEquals(mib(192), ExoTargetBufferPolicy.tierForDemand(Long.MAX_VALUE));
        assertEquals(mib(192), ExoTargetBufferPolicy.tierForCapacity(Long.MAX_VALUE));
    }

    @Test
    public void bitrateMathSaturatesWithoutOverflow() {
        assertEquals(Long.MAX_VALUE, ExoTargetBufferPolicy.bytesForDuration(Long.MAX_VALUE, Long.MAX_VALUE));

        ExoTargetBufferPolicy.Decision decision = resolve(
                demand(Long.MAX_VALUE, Long.MAX_VALUE),
                0,
                budget(2048, false));

        assertEquals(Long.MAX_VALUE, decision.averageDemandBytes());
        assertEquals(Long.MAX_VALUE, decision.burstDemandBytes());
        assertEquals(mib(192), decision.targetBytes());
    }

    private static ExoTargetBufferPolicy.Decision resolve(
            ExoTargetBufferPolicy.MediaDemand demand,
            int configuredTargetBytes,
            ExoBufferBudget.Budget budget) {
        return ExoTargetBufferPolicy.resolve(
                demand,
                configuredTargetBytes,
                budget,
                PlaybackAutoContext.DeviceFacts.unknown(),
                0);
    }

    private static ExoTargetBufferPolicy.MediaDemand demand(long average, long burst) {
        return new ExoTargetBufferPolicy.MediaDemand(
                average,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM,
                burst,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM);
    }

    private static ExoBufferBudget.Budget budget(int heapMib, boolean lowRam) {
        return ExoBufferBudget.calculate(
                ExoBufferBudget.MAX_TARGET_BYTES,
                mibLong(heapMib),
                lowRam);
    }

    private static PlaybackAutoContext.DeviceFacts device(
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshot,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure) {
        return new PlaybackAutoContext.DeviceFacts(
                pressure,
                snapshot,
                PlaybackAutoContext.Fact.unknown(-1L),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ThermalState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PowerState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkCost.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkSnapshot.unknown()));
    }

    private static PlaybackAutoContext.ResourceFacts proxyVodResource(
            PlaybackAutoContext.Protocol protocol,
            Integer variants) {
        return proxyVodResource(
                protocol,
                variants,
                PlaybackAutoContext.TransferUnit.UNKNOWN,
                variants == null
                        ? PlaybackAutoContext.ManifestKind.UNKNOWN
                        : PlaybackAutoContext.ManifestKind.NONE);
    }

    private static PlaybackAutoContext.ResourceFacts proxyVodResource(
            PlaybackAutoContext.Protocol protocol,
            Integer variants,
            PlaybackAutoContext.TransferUnit transferUnit,
            PlaybackAutoContext.ManifestKind manifestKind) {
        boolean manifestUnknown = variants == null
                && manifestKind == PlaybackAutoContext.ManifestKind.UNKNOWN;
        PlaybackAutoContext.ManifestFacts manifest = manifestUnknown
                ? PlaybackAutoContext.ManifestFacts.unknown()
                : new PlaybackAutoContext.ManifestFacts(
                manifestKind,
                true,
                null,
                null,
                null,
                variants,
                null,
                false);
        return new PlaybackAutoContext.ResourceFacts(
                fact(protocol),
                fact(PlaybackAutoContext.StreamKind.VOD),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RangeSupport.UNKNOWN),
                fact(transferUnit),
                manifestUnknown
                        ? PlaybackAutoContext.Fact.unknown(manifest)
                        : fact(manifest));
    }

    private static PlaybackAutoContext.PathFacts appProxyPath() {
        return new PlaybackAutoContext.PathFacts(
                fact(PlaybackRoute.APP_LOCAL_SERVICE),
                fact(PlaybackRoute.Owner.APP_MAIN_SERVER),
                fact(true),
                fact(PlaybackRouteCapabilities.ObservedLeg.APP_TO_OWNED_LOCAL_SERVICE),
                fact(PlaybackRouteCapabilities.UpstreamVisibility.APP_SERVICE_PATH),
                fact(PlaybackRouteCapabilities.ControlScope.APP_OWNED_SERVICE_CODE),
                fact(PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE),
                fact(PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE),
                fact(PlaybackAutoContext.UpstreamState.VISIBLE));
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(T value) {
        return PlaybackAutoContext.Fact.forSession(
                value,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH,
                0);
    }

    private static PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact(
            PlaybackAutoContext.MemorySnapshot snapshot,
            long sampledAt,
            long validForMs) {
        return PlaybackAutoContext.Fact.withTtl(
                snapshot,
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH,
                sampledAt,
                validForMs);
    }

    private static PlaybackAutoContext.MemorySnapshot memorySnapshot(
            long javaLimit,
            long javaHeadroom,
            boolean lowRam,
            long systemAvailable,
            long systemThreshold,
            boolean lowMemory) {
        return new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                Math.max(0, javaLimit - javaHeadroom),
                javaLimit,
                javaHeadroom,
                lowRam,
                mibLong(8192),
                systemAvailable,
                systemThreshold,
                lowMemory,
                null,
                100,
                mibLong(32));
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }

    private static long mibLong(int value) {
        return value * 1024L * 1024L;
    }
}
