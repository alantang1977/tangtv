package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackProfileAbAcceptancePolicyTest {

    @Test
    public void emptyAndSparseReportsNeverClaimAcceptance() {
        PlaybackProfileAbAcceptancePolicy.Report empty =
                PlaybackProfileAbAcceptancePolicy.evaluate(
                        new PlaybackProfileAbStore.Snapshot(List.of()));
        PlaybackProfileAbAcceptancePolicy.Report sparse = report(
                group(false),
                samples(19, PlaybackProfileAbPolicy.Arm.AUTO,
                        1_050, 105L << 20, 1_200, 2, -1, 0),
                samples(20, PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        1_000, 100L << 20, 1_000, 2, -1, 0));

        assertEquals(PlaybackProfileAbAcceptancePolicy.Status.NO_DATA,
                empty.status());
        assertFalse(empty.mergeEligible());
        assertEquals(
                PlaybackProfileAbAcceptancePolicy.Status
                        .INSUFFICIENT_SAMPLES,
                sparse.status());
        assertFalse(sparse.mergeEligible());
    }

    @Test
    public void matchingGroupsPassConservativeP50AndP95Bounds() {
        PlaybackProfileAbAcceptancePolicy.Report report = report(
                group(false),
                samples(20, PlaybackProfileAbPolicy.Arm.AUTO,
                        1_050, 105L << 20, 1_200, 2, -1, 0),
                samples(20, PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        1_000, 100L << 20, 1_000, 2, -1, 0));

        assertEquals(PlaybackProfileAbAcceptancePolicy.Status.PASSED,
                report.status());
        assertTrue(report.mergeEligible());
        assertEquals(1, report.passedGroups());
    }

    @Test
    public void p95RegressionFailsEvenWhenMostSamplesAreHealthy() {
        List<PlaybackProfileAbStore.Sample> automatic = samples(
                20, PlaybackProfileAbPolicy.Arm.AUTO,
                1_000, 100L << 20, 1_000, 2, -1, 0);
        automatic.set(18, sample(group(false),
                PlaybackProfileAbPolicy.Arm.AUTO,
                5_000, 100L << 20, 1_000, 2, -1, false, 18));
        automatic.set(19, sample(group(false),
                PlaybackProfileAbPolicy.Arm.AUTO,
                5_000, 100L << 20, 1_000, 2, -1, false, 19));
        PlaybackProfileAbAcceptancePolicy.Report report = report(
                group(false),
                automatic,
                samples(20, PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        1_000, 100L << 20, 1_000, 2, -1, 0));

        assertEquals(PlaybackProfileAbAcceptancePolicy.Status.FAILED,
                report.status());
        assertTrue(report.groups().get(0).failedMetrics().contains(
                PlaybackProfileAbAcceptancePolicy.Metric.FIRST_FRAME_MS));
        assertFalse(report.mergeEligible());
    }

    @Test
    public void missingPssOrLiveLagKeepsGroupUnaccepted() {
        PlaybackProfileAbAcceptancePolicy.Report missingPss = report(
                group(false),
                samples(20, PlaybackProfileAbPolicy.Arm.AUTO,
                        1_000, -1, 1_000, 2, -1, 0),
                samples(20, PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        1_000, -1, 1_000, 2, -1, 0));
        PlaybackProfileAbAcceptancePolicy.Report missingLiveLag = report(
                group(true),
                samples(20, PlaybackProfileAbPolicy.Arm.AUTO,
                        1_000, 100L << 20, 1_000, 2, -1, 0),
                samples(20, PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        1_000, 100L << 20, 1_000, 2, -1, 0));

        assertEquals(
                PlaybackProfileAbAcceptancePolicy.Status.METRICS_MISSING,
                missingPss.status());
        assertEquals(
                PlaybackProfileAbAcceptancePolicy.Status.METRICS_MISSING,
                missingLiveLag.status());
    }

    @Test
    public void playbackErrorsAreComparedInsteadOfDroppingFailedSessions() {
        List<PlaybackProfileAbStore.Sample> automatic = samples(
                20, PlaybackProfileAbPolicy.Arm.AUTO,
                1_000, 100L << 20, 1_000, 2, -1, 0);
        automatic.set(0, withError(automatic.get(0)));
        automatic.set(1, withError(automatic.get(1)));
        PlaybackProfileAbAcceptancePolicy.Report report = report(
                group(false),
                automatic,
                samples(20, PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        1_000, 100L << 20, 1_000, 2, -1, 0));

        assertEquals(PlaybackProfileAbAcceptancePolicy.Status.FAILED,
                report.status());
        assertTrue(report.groups().get(0).failedMetrics().contains(
                PlaybackProfileAbAcceptancePolicy.Metric.ERROR_RATE_PPM));
    }

    private static PlaybackProfileAbAcceptancePolicy.Report report(
            PlaybackProfileAbPolicy.GroupKey key,
            List<PlaybackProfileAbStore.Sample> automatic,
            List<PlaybackProfileAbStore.Sample> recommended) {
        return PlaybackProfileAbAcceptancePolicy.evaluate(
                new PlaybackProfileAbStore.Snapshot(List.of(
                        new PlaybackProfileAbStore.GroupSamples(
                                key, automatic, recommended))));
    }

    private static List<PlaybackProfileAbStore.Sample> samples(
            int count,
            PlaybackProfileAbPolicy.Arm arm,
            long firstFrame,
            long pss,
            long rebufferTotal,
            long dropped,
            long liveLag,
            int errorCount) {
        List<PlaybackProfileAbStore.Sample> result = new ArrayList<>();
        PlaybackProfileAbPolicy.GroupKey key = group(liveLag >= 0);
        for (int index = 0; index < count; index++) {
            result.add(sample(
                    key,
                    arm,
                    firstFrame,
                    pss,
                    rebufferTotal,
                    dropped,
                    liveLag,
                    index < errorCount,
                    index));
        }
        return result;
    }

    private static PlaybackProfileAbStore.Sample withError(
            PlaybackProfileAbStore.Sample sample) {
        return new PlaybackProfileAbStore.Sample(
                sample.version(),
                sample.groupKey(),
                sample.arm(),
                sample.firstFrameMs(),
                sample.rebufferCount(),
                sample.rebufferTotalMs(),
                sample.activePlaybackMs(),
                sample.peakPssBytes(),
                sample.maxDroppedFrames(),
                sample.maxLiveLagMs(),
                true,
                sample.recordedAtEpochMs());
    }

    private static PlaybackProfileAbStore.Sample sample(
            PlaybackProfileAbPolicy.GroupKey key,
            PlaybackProfileAbPolicy.Arm arm,
            long firstFrame,
            long pss,
            long rebufferTotal,
            long dropped,
            long liveLag,
            boolean error,
            int index) {
        return new PlaybackProfileAbStore.Sample(
                PlaybackProfileAbStore.SAMPLE_VERSION,
                key,
                arm,
                firstFrame,
                1,
                rebufferTotal,
                60_000,
                pss,
                dropped,
                liveLag,
                error,
                1_000L + index);
    }

    private static PlaybackProfileAbPolicy.GroupKey group(boolean live) {
        return new PlaybackProfileAbPolicy.GroupKey(
                PlaybackProfileAbIdentity.deviceDigest(
                        "fingerprint", 10, "1", "media3"),
                PlaybackAutoContext.Kernel.EXO,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                PlaybackProfileAbIdentity.decoderDigest("decoder"),
                PlaybackAutoContext.Protocol.HLS,
                live ? PlaybackAutoContext.StreamKind.LIVE
                        : PlaybackAutoContext.StreamKind.VOD,
                PlaybackProfileAbPolicy.VideoMimeClass.HEVC,
                PlaybackAutoContext.HdrType.HDR10,
                PlaybackAutoContext.PathKind.REMOTE);
    }
}
