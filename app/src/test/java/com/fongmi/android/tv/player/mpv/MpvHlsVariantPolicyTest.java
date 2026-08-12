package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackThroughputHistory;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvHlsVariantPolicyTest {

    @Test
    public void missingTrustedHistoryStartsWithConservativeFifteenMbpsCeiling() {
        MpvHlsVariantPolicy.InitialAssessment result =
                MpvHlsVariantPolicy.resolveInitial(
                        true, true, true,
                        PlaybackThroughputHistory.Match.unavailable(
                                PlaybackThroughputHistory.Reason.NO_MATCH));

        assertTrue(result.active());
        assertEquals(15_000_000L, result.ceilingBitsPerSecond());
        assertEquals("15000000", result.option());
        assertEquals(MpvHlsVariantPolicy.Reason.CONSERVATIVE_BOOTSTRAP,
                result.reason());
    }

    @Test
    public void trustedHistoryUsesSeventyFivePercentSafetyCeiling() {
        PlaybackThroughputHistory.Match history = new PlaybackThroughputHistory.Match(
                true,
                40_000_000L,
                12_000L,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackThroughputHistory.Reason.MATCHED);

        MpvHlsVariantPolicy.InitialAssessment result =
                MpvHlsVariantPolicy.resolveInitial(true, true, true, history);

        assertEquals(30_000_000L, result.ceilingBitsPerSecond());
        assertEquals("30000000", result.option());
        assertEquals(MpvHlsVariantPolicy.Reason.TRUSTED_HISTORY,
                result.reason());
    }

    @Test
    public void manualModeAndConfigPriorityNeverApplyAutomaticCeiling() {
        assertFalse(MpvHlsVariantPolicy.resolveInitial(
                false, true, true, null).active());
        MpvHlsVariantPolicy.InitialAssessment config =
                MpvHlsVariantPolicy.resolveInitial(true, true, false, null);
        assertFalse(config.active());
        assertEquals(MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY,
                config.reason());
    }

    @Test
    public void runtimeSelectionUsesPeakBandwidthAndDropsExactlyOneVariant() {
        MpvHlsVariantPolicy.Variant low = variant(4_000_000L, 3_500_000L);
        MpvHlsVariantPolicy.Variant middle = variant(8_000_000L, 7_000_000L);
        MpvHlsVariantPolicy.Variant high = variant(16_000_000L, 10_000_000L);
        MpvHlsVariantPolicy.Assessment result = MpvHlsVariantPolicy.assess(
                request(List.of(high, low, middle), high,
                        true, true, true, true, 6_000, 10_000_000L));

        assertTrue(result.active());
        assertEquals(MpvHlsVariantPolicy.Reason.DOWNGRADE_EVIDENCE,
                result.reason());
        assertEquals(16_000_000L, result.selectedBitsPerSecond());
        assertEquals(8_000_000L, result.nextLowerBitsPerSecond());
    }

    @Test
    public void unknownBufferCannotCompleteDowngradeEvidence() {
        MpvHlsVariantPolicy.Variant low = variant(4_000_000L, 3_500_000L);
        MpvHlsVariantPolicy.Variant high = variant(8_000_000L, 7_000_000L);
        MpvHlsVariantPolicy.Assessment result = MpvHlsVariantPolicy.assess(
                request(List.of(low, high), high,
                        true, true, false, false, 0, 5_000_000L));

        assertTrue(result.hardRisk());
        assertTrue(result.throughputShortfall());
        assertFalse(result.bufferRisk());
        assertEquals(MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE,
                result.reason());
    }

    @Test
    public void lowestVariantAndUnknownStreamKindDoNotRequestReload() {
        MpvHlsVariantPolicy.Variant low = variant(4_000_000L, 3_500_000L);
        MpvHlsVariantPolicy.Assessment lowest = MpvHlsVariantPolicy.assess(
                request(List.of(low), low,
                        true, true, true, true, 2_000, 1_000_000L));
        assertEquals(MpvHlsVariantPolicy.Reason.LOWEST_VARIANT,
                lowest.reason());
        assertEquals(0, lowest.nextLowerBitsPerSecond());

        MpvHlsVariantPolicy.RuntimeRequest unknown = new MpvHlsVariantPolicy.RuntimeRequest(
                true, true, true,
                PlaybackAutoContext.Protocol.HLS, true,
                PlaybackAutoContext.StreamKind.UNKNOWN, false,
                List.of(low), low,
                true, true, true, false, true,
                1_000, 1_000_000L, true);
        assertEquals(MpvHlsVariantPolicy.Reason.STREAM_UNKNOWN,
                MpvHlsVariantPolicy.assess(unknown).reason());
    }

    @Test
    public void observedZeroThroughputIsShortfallButUnknownZeroIsNot() {
        MpvHlsVariantPolicy.Variant low = variant(4_000_000L, 3_500_000L);
        MpvHlsVariantPolicy.Variant high = variant(8_000_000L, 7_000_000L);

        MpvHlsVariantPolicy.Assessment observed = MpvHlsVariantPolicy.assess(
                request(List.of(low, high), high,
                        true, true, true, true, 1_000, 0, true));
        MpvHlsVariantPolicy.Assessment unknown = MpvHlsVariantPolicy.assess(
                request(List.of(low, high), high,
                        true, true, true, true, 1_000, 0, false));

        assertTrue(observed.throughputShortfall());
        assertEquals(MpvHlsVariantPolicy.Reason.DOWNGRADE_EVIDENCE,
                observed.reason());
        assertFalse(unknown.throughputShortfall());
        assertEquals(MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE,
                unknown.reason());
    }

    private static MpvHlsVariantPolicy.RuntimeRequest request(
            List<MpvHlsVariantPolicy.Variant> variants,
            MpvHlsVariantPolicy.Variant selected,
            boolean underrun,
            boolean rebuffer,
            boolean buffering,
            boolean bufferUsable,
            long bufferedMs,
            long rawThroughput) {
        return request(variants, selected, underrun, rebuffer, buffering,
                bufferUsable, bufferedMs, rawThroughput, true);
    }

    private static MpvHlsVariantPolicy.RuntimeRequest request(
            List<MpvHlsVariantPolicy.Variant> variants,
            MpvHlsVariantPolicy.Variant selected,
            boolean underrun,
            boolean rebuffer,
            boolean buffering,
            boolean bufferUsable,
            long bufferedMs,
            long rawThroughput,
            boolean rawThroughputUsable) {
        return new MpvHlsVariantPolicy.RuntimeRequest(
                true,
                true,
                true,
                PlaybackAutoContext.Protocol.HLS,
                true,
                PlaybackAutoContext.StreamKind.VOD,
                true,
                variants,
                selected,
                underrun,
                rebuffer,
                buffering,
                false,
                bufferUsable,
                bufferedMs,
                rawThroughput,
                rawThroughputUsable);
    }

    private static MpvHlsVariantPolicy.Variant variant(long peak, long average) {
        return new MpvHlsVariantPolicy.Variant(peak, average, 1920, 1080);
    }
}
