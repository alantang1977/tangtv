package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAutoControlPolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void ignoresNonAutomaticAndNonMpvRequests() {
        assertEquals(MpvAutoControlPolicy.Reason.NOT_AUTOMATIC_MPV,
                MpvAutoControlPolicy.resolve(request(false, true, true,
                        false, PlaybackAutoContext.MemoryPressure.UNKNOWN,
                        false, PlaybackAutoContext.MemorySnapshot.unknown(),
                        PlaybackAutoContext.Protocol.UNKNOWN,
                        PlaybackAutoContext.StreamKind.UNKNOWN,
                        PlaybackAutoContext.PathKind.UNKNOWN,
                        PlaybackAutoContext.PathKind.UNKNOWN,
                        PlaybackAutoContext.UpstreamState.UNKNOWN)).reason());
        assertEquals(MpvAutoControlPolicy.Reason.NOT_AUTOMATIC_MPV,
                MpvAutoControlPolicy.resolve(request(true, false, true,
                        false, PlaybackAutoContext.MemoryPressure.UNKNOWN,
                        false, PlaybackAutoContext.MemorySnapshot.unknown(),
                        PlaybackAutoContext.Protocol.UNKNOWN,
                        PlaybackAutoContext.StreamKind.UNKNOWN,
                        PlaybackAutoContext.PathKind.UNKNOWN,
                        PlaybackAutoContext.PathKind.UNKNOWN,
                        PlaybackAutoContext.UpstreamState.UNKNOWN)).reason());
    }

    @Test
    public void configPriorityNeverRequestsRuntimeOverlay() {
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(
                request(true, true, false, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, normalMemory(256, 1024, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));

        assertFalse(decision.requestsApply());
        assertEquals(MpvAutoControlPolicy.Reason.CONFIG_PRIORITY, decision.reason());
    }

    @Test
    public void unknownMemoryStartsAtMinimumWithNoBackCache() {
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(
                request(true, true, true, false,
                        PlaybackAutoContext.MemoryPressure.UNKNOWN,
                        false, PlaybackAutoContext.MemorySnapshot.unknown(),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));

        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, decision.forwardBytes());
        assertEquals(0, decision.backBytes());
        assertEquals(MpvAutoControlPolicy.Reason.MEMORY_UNKNOWN, decision.reason());
    }

    @Test
    public void normalPressureWithoutMemorySnapshotStillStartsAtMinimum() {
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        false, PlaybackAutoContext.MemorySnapshot.unknown(),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));

        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, decision.forwardBytes());
        assertEquals(MpvAutoControlPolicy.Reason.MEMORY_UNKNOWN, decision.reason());
    }

    @Test
    public void pressureAndLowMemoryFactsStayAtMinimum() {
        MpvAutoControlPolicy.Decision critical = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.CRITICAL,
                        true, normalMemory(512, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));
        MpvAutoControlPolicy.Decision moderate = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.MODERATE,
                        true, normalMemory(512, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));
        MpvAutoControlPolicy.Decision systemLow = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, memory(512, 400, false, 128, 128, true),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));

        assertEquals(MpvAutoControlPolicy.Reason.CRITICAL_PRESSURE, critical.reason());
        assertEquals(MpvAutoControlPolicy.Reason.MODERATE_PRESSURE, moderate.reason());
        assertEquals(MpvAutoControlPolicy.Reason.SYSTEM_LOW_MEMORY, systemLow.reason());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, critical.forwardBytes());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, moderate.forwardBytes());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, systemLow.forwardBytes());
    }

    @Test
    public void lowRamSmallHeapAndLowHeadroomStayAtMinimum() {
        assertEquals(MpvAutoControlPolicy.Reason.LOW_RAM,
                MpvAutoControlPolicy.resolve(request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, memory(512, 400, true, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE)).reason());
        assertEquals(MpvAutoControlPolicy.Reason.SMALL_HEAP,
                MpvAutoControlPolicy.resolve(request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, memory(192, 128, false, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE)).reason());
        assertEquals(MpvAutoControlPolicy.Reason.LOW_JAVA_HEADROOM,
                MpvAutoControlPolicy.resolve(request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, memory(512, 64, false, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE)).reason());
    }

    @Test
    public void ordinaryKnownMemoryUsesConservativeTier() {
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, normalMemory(384, 1024, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));

        assertEquals(MpvAutoControlPolicy.CONSERVATIVE_FORWARD_BYTES, decision.forwardBytes());
        assertEquals(MpvAutoControlPolicy.Reason.NORMAL_MEMORY, decision.reason());
    }

    @Test
    public void strongFreshHeadroomAllowsBalancedInitialTier() {
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, normalMemory(512, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));

        assertEquals(MpvAutoControlPolicy.BALANCED_FORWARD_BYTES, decision.forwardBytes());
        assertEquals(MpvAutoControlPolicy.Reason.HIGH_HEADROOM, decision.reason());
    }

    @Test
    public void resourceAndPathFactsOnlyTightenHighInitialTier() {
        MpvAutoControlPolicy.Decision local = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, normalMemory(512, 2048, 128, false),
                        PlaybackAutoContext.Protocol.LOCAL,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.LOCAL,
                        PlaybackAutoContext.PathKind.LOCAL,
                        PlaybackAutoContext.UpstreamState.NOT_APPLICABLE));
        MpvAutoControlPolicy.Decision live = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, normalMemory(512, 2048, 128, false),
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.LIVE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.PathKind.REMOTE,
                        PlaybackAutoContext.UpstreamState.VISIBLE));
        MpvAutoControlPolicy.Decision opaque = MpvAutoControlPolicy.resolve(
                request(true, true, true, true,
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        true, normalMemory(512, 2048, 128, false),
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                        PlaybackAutoContext.PathKind.UNKNOWN,
                        PlaybackAutoContext.UpstreamState.OPAQUE));

        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, local.forwardBytes());
        assertEquals(MpvAutoControlPolicy.Reason.LOCAL_RESOURCE_CAP, local.reason());
        assertEquals(MpvAutoControlPolicy.CONSERVATIVE_FORWARD_BYTES, live.forwardBytes());
        assertEquals(MpvAutoControlPolicy.Reason.LIVE_RESOURCE_CAP, live.reason());
        assertEquals(MpvAutoControlPolicy.CONSERVATIVE_FORWARD_BYTES, opaque.forwardBytes());
        assertEquals(MpvAutoControlPolicy.Reason.OPAQUE_PATH_CAP, opaque.reason());
        assertTrue(local.capped());
        assertTrue(live.capped());
        assertTrue(opaque.capped());
    }

    @Test
    public void expiredMemoryFactsAreUnknownForInitialDecision() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken token = store.beginSession("p-expired-1", 0);
        PlaybackAutoContext.MemorySnapshot snapshot = normalMemory(512, 2048, 128, false);
        store.publishMemoryFacts(
                token,
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        10),
                PlaybackAutoContext.Fact.withTtl(
                        snapshot,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        10),
                null,
                0);

        MpvAutoControlPolicy.Request request = MpvAutoControlPolicy.requestFrom(
                store.snapshot(), true, true, true, 10);
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(request);

        assertFalse(request.pressureUsable());
        assertFalse(request.snapshotUsable());
        assertEquals(MpvAutoControlPolicy.Reason.MEMORY_UNKNOWN, decision.reason());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, decision.forwardBytes());
    }

    @Test
    public void freshSnapshotWithoutEvidenceIsStillUnknown() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken token = store.beginSession("p-empty-1", 0);
        store.publishMemoryFacts(
                token,
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        10_000),
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.MemorySnapshot.unknown(),
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        10_000),
                null,
                0);

        MpvAutoControlPolicy.Request request = MpvAutoControlPolicy.requestFrom(
                store.snapshot(), true, true, true, 1);
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(request);

        assertFalse(request.snapshotUsable());
        assertEquals(MpvAutoControlPolicy.Reason.MEMORY_UNKNOWN, decision.reason());
        assertEquals(MpvAutoControlPolicy.MIN_FORWARD_BYTES, decision.forwardBytes());
    }

    private static MpvAutoControlPolicy.Request request(
            boolean automatic,
            boolean mpv,
            boolean performancePriority,
            boolean pressureUsable,
            PlaybackAutoContext.MemoryPressure pressure,
            boolean snapshotUsable,
            PlaybackAutoContext.MemorySnapshot snapshot,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind stream,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState) {
        return new MpvAutoControlPolicy.Request(
                automatic,
                mpv,
                performancePriority,
                pressureUsable,
                pressure,
                snapshotUsable,
                snapshot,
                protocol != PlaybackAutoContext.Protocol.UNKNOWN,
                protocol,
                stream != PlaybackAutoContext.StreamKind.UNKNOWN,
                stream,
                playerPath != PlaybackAutoContext.PathKind.UNKNOWN,
                playerPath,
                upstreamPath != PlaybackAutoContext.PathKind.UNKNOWN,
                upstreamPath,
                upstreamState != PlaybackAutoContext.UpstreamState.UNKNOWN,
                upstreamState);
    }

    private static PlaybackAutoContext.MemorySnapshot normalMemory(
            long heapLimitMib,
            long availableMib,
            long thresholdMib,
            boolean lowRam) {
        return memory(heapLimitMib, heapLimitMib - 96, lowRam,
                availableMib, thresholdMib, false);
    }

    private static PlaybackAutoContext.MemorySnapshot memory(
            long heapLimitMib,
            long heapHeadroomMib,
            boolean lowRam,
            long availableMib,
            long thresholdMib,
            boolean systemLow) {
        return new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.SESSION_START,
                Math.max(0, heapLimitMib - heapHeadroomMib) * MIB,
                heapLimitMib * MIB,
                heapHeadroomMib * MIB,
                lowRam,
                4096L * MIB,
                availableMib * MIB,
                thresholdMib * MIB,
                systemLow,
                null,
                null,
                64L * MIB);
    }
}
