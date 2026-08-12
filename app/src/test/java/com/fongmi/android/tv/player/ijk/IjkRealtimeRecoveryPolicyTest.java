package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkRealtimeRecoveryPolicyTest {

    private static final IjkBufferPolicy.Config REALTIME =
            new IjkBufferPolicy.Config(4, 100, 300, 1_000);

    @Test
    public void onlyAutomaticIjkRtspOrRtmpIsManaged() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = queue(20_000, 1_000_000, 500);

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.NOT_AUTOMATIC_IJK,
                IjkRealtimeRecoveryPolicy.resolve(request(false, true,
                        PlaybackAutoContext.Protocol.RTSP, queue,
                        Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                        3, 0, 10_000)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.NOT_REALTIME_PROTOCOL,
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.HLS, queue,
                        Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                        3, 0, 10_000)).reason());
        assertTrue(IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                PlaybackAutoContext.Protocol.RTMP, queue,
                Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                3, 0, 10_000)).requestsRecovery());
    }

    @Test
    public void inactiveStartupSeekAndPendingActionsAreSuppressed() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = queue(20_000, 1_000_000, 500);
        IjkRealtimeRecoveryPolicy.Request base = request(true, true,
                PlaybackAutoContext.Protocol.RTSP, queue,
                Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                3, 0, 10_000);

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.INACTIVE,
                IjkRealtimeRecoveryPolicy.resolve(copy(base,
                        false, true, true, false, false)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.STARTUP,
                IjkRealtimeRecoveryPolicy.resolve(copy(base,
                        true, false, true, false, false)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.NON_UNIT_SPEED,
                IjkRealtimeRecoveryPolicy.resolve(copy(base,
                        true, true, false, false, false)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.USER_SEEK,
                IjkRealtimeRecoveryPolicy.resolve(copy(base,
                        true, true, true, true, false)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.ACTION_PENDING,
                IjkRealtimeRecoveryPolicy.resolve(copy(base,
                        true, true, true, false, true)).reason());
    }

    @Test
    public void dualTrackBacklogUsesShorterPlayableQueue() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue =
                new IjkRealtimeRecoveryPolicy.QueueSnapshot(
                        true, true, true, 20_000, 7_000,
                        1_000, 2_000, 10, 20);

        assertEquals(7_000, queue.playableDurationMs());
        assertEquals(3_000, queue.totalBytes());
        assertEquals(30, queue.totalPackets());
        assertFalse(IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                PlaybackAutoContext.Protocol.RTSP, queue,
                Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                3, 0, 10_000)).requestsRecovery());
    }

    @Test
    public void missingOrOneSidedUnknownTrackEvidenceNeverRebuilds() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot unavailable =
                IjkRealtimeRecoveryPolicy.QueueSnapshot.unknown();
        IjkRealtimeRecoveryPolicy.QueueSnapshot oneSidedUnknown =
                new IjkRealtimeRecoveryPolicy.QueueSnapshot(
                        true, false, false, 20_000, 0,
                        4_000_000, 0, 1_000, 0);

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.EVIDENCE_UNKNOWN,
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, unavailable,
                        1_000, 1_000_000, 100,
                        3, 0, 10_000)).reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.EVIDENCE_UNKNOWN,
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, oneSidedUnknown,
                        1_000, 1_000_000, 100,
                        3, 0, 10_000)).reason());
    }

    @Test
    public void absoluteBacklogNeedsThreeSamplesAndTenSeconds() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = queue(15_000, 1_000_000, 500);

        IjkRealtimeRecoveryPolicy.Decision confirming =
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, queue,
                        Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                        2, 0, 5_000));
        IjkRealtimeRecoveryPolicy.Decision rebuild =
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, queue,
                        Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                        3, 0, 10_000));

        assertEquals(IjkRealtimeRecoveryPolicy.Reason.CONFIRMING,
                confirming.reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Trigger.BACKLOG_EXCEEDED,
                rebuild.trigger());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.REBUILD_BACKLOG,
                rebuild.reason());
    }

    @Test
    public void urgentBacklogUsesTwoSamplesAndFiveSeconds() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = queue(35_000, 1_000_000, 500);

        IjkRealtimeRecoveryPolicy.Decision decision =
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTMP, queue,
                        Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                        2, 0, 5_000));

        assertTrue(decision.requestsRecovery());
        assertTrue(decision.confirmed());
    }

    @Test
    public void growingDurationCanRecoverBeforeAbsoluteLimit() {
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = queue(6_000, 1_000_000, 500);

        IjkRealtimeRecoveryPolicy.Decision decision =
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, queue,
                        600, 0, 0, 3, 0, 10_000));

        assertEquals(IjkRealtimeRecoveryPolicy.Trigger.BACKLOG_GROWING,
                decision.trigger());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.REBUILD_GROWTH,
                decision.reason());
    }

    @Test
    public void capacityPressureRequiresDurationAndQueueGrowth() {
        long bytes = REALTIME.maxBufferBytes() * 9 / 10;
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = queue(5_000, bytes, 2_000);

        IjkRealtimeRecoveryPolicy.Decision growing =
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, queue,
                        0, 64 * 1024, 0, 3, 0, 10_000));
        IjkRealtimeRecoveryPolicy.Decision staticQueue =
                IjkRealtimeRecoveryPolicy.resolve(request(true, true,
                        PlaybackAutoContext.Protocol.RTSP, queue,
                        0, 0, 0, 3, 0, 10_000));

        assertEquals(IjkRealtimeRecoveryPolicy.Trigger.CAPACITY_PRESSURE,
                growing.trigger());
        assertEquals(IjkRealtimeRecoveryPolicy.Reason.REBUILD_CAPACITY,
                growing.reason());
        assertEquals(IjkRealtimeRecoveryPolicy.Trigger.NONE,
                staticQueue.trigger());
    }

    @Test
    public void largerWatermarkRaisesGrowthThresholdButKeepsSafeFloor() {
        IjkRealtimeRecoveryPolicy.Thresholds realtime =
                IjkRealtimeRecoveryPolicy.thresholds(REALTIME);
        IjkRealtimeRecoveryPolicy.Thresholds larger =
                IjkRealtimeRecoveryPolicy.thresholds(
                        new IjkBufferPolicy.Config(8, 100, 1_000, 5_000));

        assertEquals(4_000, realtime.growingBacklogMs());
        assertEquals(12_000, realtime.absoluteBacklogMs());
        assertEquals(20_000, larger.growingBacklogMs());
        assertEquals(40_000, larger.absoluteBacklogMs());
    }

    private static IjkRealtimeRecoveryPolicy.QueueSnapshot queue(
            long durationMs,
            long bytes,
            long packets) {
        return new IjkRealtimeRecoveryPolicy.QueueSnapshot(
                true, false, true, 0, durationMs,
                0, bytes, 0, packets);
    }

    private static IjkRealtimeRecoveryPolicy.Request request(
            boolean automatic,
            boolean ijk,
            PlaybackAutoContext.Protocol protocol,
            IjkRealtimeRecoveryPolicy.QueueSnapshot queue,
            long durationGrowth,
            long bytesGrowth,
            long packetsGrowth,
            int samples,
            long riskSince,
            long now) {
        return new IjkRealtimeRecoveryPolicy.Request(
                automatic, ijk, true, protocol,
                true, true, true, false, false,
                queue, REALTIME,
                durationGrowth, bytesGrowth, packetsGrowth,
                samples, riskSince, now);
    }

    private static IjkRealtimeRecoveryPolicy.Request copy(
            IjkRealtimeRecoveryPolicy.Request source,
            boolean active,
            boolean startupComplete,
            boolean normalSpeed,
            boolean userSeeking,
            boolean actionPending) {
        return new IjkRealtimeRecoveryPolicy.Request(
                source.automatic(), source.ijk(), source.protocolUsable(),
                source.protocol(), active, startupComplete, normalSpeed,
                userSeeking,
                actionPending, source.queue(), source.appliedConfig(),
                source.durationGrowthMsPerSecond(),
                source.bytesGrowthPerSecond(),
                source.packetsGrowthPerSecond(),
                source.consecutiveRiskSamples(), source.riskSinceMs(),
                source.nowMs());
    }
}
