package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlaybackLightweightAssessmentPolicyTest {

    private static final String DEVICE =
            PlaybackProfileAbIdentity.deviceDigest(
                    "fingerprint", 10, "1", "media3");

    @Test
    public void onlyAutoAndLightweightProfilesEnterAssessment() {
        assertEquals(PlaybackProfileAbPolicy.Arm.AUTO,
                PlaybackLightweightAssessmentPolicy.armForProfile(
                        PlaybackPerformanceSetting.PROFILE_AUTO));
        assertEquals(PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT,
                PlaybackLightweightAssessmentPolicy.armForProfile(
                        PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
        assertNull(PlaybackLightweightAssessmentPolicy.armForProfile(
                PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertNull(PlaybackLightweightAssessmentPolicy.armForProfile(
                PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
        assertNull(PlaybackLightweightAssessmentPolicy.armForProfile(
                PlaybackPerformanceSetting.PROFILE_CUSTOM));
    }

    @Test
    public void admissionAllowsOnlyLowRamHardwareOrActualSoftwareDecode() {
        PlaybackProfileAbPolicy.GroupResolution normalHardware =
                PlaybackLightweightAssessmentPolicy.resolveGroup(
                        context(PlaybackAutoContext.Kernel.EXO,
                                PlaybackAutoContext.DecodeMode.HARDWARE,
                                false),
                        DEVICE,
                        1_000);
        PlaybackProfileAbPolicy.GroupResolution lowRamHardware =
                PlaybackLightweightAssessmentPolicy.resolveGroup(
                        context(PlaybackAutoContext.Kernel.EXO,
                                PlaybackAutoContext.DecodeMode.HARDWARE,
                                true),
                        DEVICE,
                        1_000);
        PlaybackProfileAbPolicy.GroupResolution software =
                PlaybackLightweightAssessmentPolicy.resolveGroup(
                        context(PlaybackAutoContext.Kernel.EXO,
                                PlaybackAutoContext.DecodeMode.SOFTWARE,
                                null),
                        DEVICE,
                        1_000);

        assertFalse(normalHardware.ready());
        assertEquals(
                PlaybackProfileAbPolicy.GroupReason
                        .NOT_LIGHTWEIGHT_SCENARIO,
                normalHardware.reason());
        assertTrue(lowRamHardware.ready());
        assertTrue(software.ready());
    }

    @Test
    public void unknownMemoryClassRejectsHardwareWithoutRejectingSoftware() {
        PlaybackProfileAbPolicy.GroupResolution hardware =
                PlaybackLightweightAssessmentPolicy.resolveGroup(
                        context(PlaybackAutoContext.Kernel.MPV,
                                PlaybackAutoContext.DecodeMode.HARDWARE,
                                null),
                        DEVICE,
                        1_000);
        PlaybackProfileAbPolicy.GroupResolution software =
                PlaybackLightweightAssessmentPolicy.resolveGroup(
                        context(PlaybackAutoContext.Kernel.MPV,
                                PlaybackAutoContext.DecodeMode.SOFTWARE,
                                null),
                        DEVICE,
                        1_000);

        assertFalse(hardware.ready());
        assertEquals(
                PlaybackProfileAbPolicy.GroupReason.MEMORY_CLASS_UNKNOWN,
                hardware.reason());
        assertTrue(software.ready());
    }

    @Test
    public void allSixPassedCoverageCellsPermitOnlyFurtherReview() {
        PlaybackLightweightAssessmentPolicy.Report report =
                PlaybackLightweightAssessmentPolicy.evaluate(
                        snapshot(false, false));

        assertEquals(PlaybackLightweightAssessmentPolicy.Status.PASSED,
                report.status());
        assertTrue(report.reviewEligible());
        assertEquals(6, report.covered().size());
        assertTrue(report.missingCoverage().isEmpty());
        assertEquals(120, report.comparison().automaticSamples());
        assertEquals(120, report.comparison().comparisonSamples());
        assertEquals(PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT,
                report.comparison().comparisonArm());
    }

    @Test
    public void missingCoverageCannotBeHiddenByPassingAvailableGroups() {
        PlaybackLightweightAssessmentPolicy.Report report =
                PlaybackLightweightAssessmentPolicy.evaluate(
                        snapshot(true, false));

        assertEquals(
                PlaybackLightweightAssessmentPolicy.Status.COVERAGE_MISSING,
                report.status());
        assertFalse(report.reviewEligible());
        assertEquals(1, report.missingCoverage().size());
        assertEquals(
                PlaybackLightweightAssessmentPolicy.CoverageCell
                        .IJK_SOFTWARE,
                report.missingCoverage().get(0));
    }

    @Test
    public void anyFailedGroupBlocksFurtherReview() {
        PlaybackLightweightAssessmentPolicy.Report report =
                PlaybackLightweightAssessmentPolicy.evaluate(
                        snapshot(false, true));

        assertEquals(PlaybackLightweightAssessmentPolicy.Status.FAILED,
                report.status());
        assertFalse(report.reviewEligible());
        assertEquals(1, report.comparison().failedGroups());
    }

    private static PlaybackProfileAbStore.Snapshot snapshot(
            boolean omitIjkSoftware,
            boolean failExoSoftware) {
        List<PlaybackProfileAbStore.GroupSamples> groups =
                new ArrayList<>();
        for (PlaybackLightweightAssessmentPolicy.CoverageCell cell
                : PlaybackLightweightAssessmentPolicy.CoverageCell.values()) {
            if (omitIjkSoftware
                    && cell == PlaybackLightweightAssessmentPolicy
                    .CoverageCell.IJK_SOFTWARE) continue;
            PlaybackAutoContext.DecodeMode decodeMode = cell.kind()
                    == PlaybackLightweightAssessmentPolicy
                    .CoverageKind.SOFTWARE
                    ? PlaybackAutoContext.DecodeMode.SOFTWARE
                    : PlaybackAutoContext.DecodeMode.HARDWARE;
            PlaybackProfileAbPolicy.GroupKey key = group(
                    cell.kernel(), decodeMode, cell.ordinal());
            boolean fail = failExoSoftware
                    && cell == PlaybackLightweightAssessmentPolicy
                    .CoverageCell.EXO_SOFTWARE;
            groups.add(new PlaybackProfileAbStore.GroupSamples(
                    key,
                    samples(key, PlaybackProfileAbPolicy.Arm.AUTO,
                            fail ? 5_000 : 1_000),
                    List.of(),
                    samples(key,
                            PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT,
                            1_000)));
        }
        return new PlaybackProfileAbStore.Snapshot(groups);
    }

    private static List<PlaybackProfileAbStore.Sample> samples(
            PlaybackProfileAbPolicy.GroupKey key,
            PlaybackProfileAbPolicy.Arm arm,
            long firstFrameMs) {
        List<PlaybackProfileAbStore.Sample> samples = new ArrayList<>();
        for (int index = 0;
             index < PlaybackProfileAbAcceptancePolicy.MIN_SAMPLES_PER_ARM;
             index++) {
            samples.add(new PlaybackProfileAbStore.Sample(
                    PlaybackProfileAbStore.SAMPLE_VERSION,
                    key,
                    arm,
                    firstFrameMs,
                    1,
                    1_000,
                    60_000,
                    96L << 20,
                    2,
                    -1,
                    false,
                    1_000L + index));
        }
        return samples;
    }

    private static PlaybackProfileAbPolicy.GroupKey group(
            PlaybackAutoContext.Kernel kernel,
            PlaybackAutoContext.DecodeMode decodeMode,
            int suffix) {
        return new PlaybackProfileAbPolicy.GroupKey(
                DEVICE,
                kernel,
                decodeMode,
                PlaybackProfileAbIdentity.decoderDigest(
                        "decoder-" + suffix),
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackProfileAbPolicy.VideoMimeClass.HEVC,
                PlaybackAutoContext.HdrType.SDR,
                PlaybackAutoContext.PathKind.REMOTE);
    }

    private static PlaybackAutoContext context(
            PlaybackAutoContext.Kernel kernel,
            PlaybackAutoContext.DecodeMode decodeMode,
            Boolean lowRamDevice) {
        long now = 100;
        PlaybackAutoContext.SessionToken session =
                new PlaybackAutoContext.SessionToken("p-l-1", 1);
        PlaybackAutoContext.TrackFacts video =
                new PlaybackAutoContext.TrackFacts(
                        fact("video/hevc", now),
                        fact("hvc1.2.4", now),
                        fact(2, now),
                        fact(4, now),
                        fact(1920, now),
                        fact(1080, now),
                        fact(24f, now),
                        fact(PlaybackAutoContext.HdrType.SDR, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ColorSnapshot.unknown()),
                        fact(4_000_000L, now),
                        fact(6_000_000L, now));
        PlaybackAutoContext.DecoderFacts decoder =
                new PlaybackAutoContext.DecoderFacts(
                        1,
                        fact("decoder", now),
                        PlaybackAutoContext.Fact.unknown(""),
                        fact(decodeMode, now),
                        fact(false, now));
        PlaybackAutoContext.MediaFacts media =
                new PlaybackAutoContext.MediaFacts(
                        1,
                        video,
                        PlaybackAutoContext.TrackFacts.unknown(),
                        decoder,
                        PlaybackAutoContext.OutputFacts.unknown(),
                        PlaybackAutoContext.DisplayFacts.unknown());
        PlaybackAutoContext.MemorySnapshot memorySnapshot =
                new PlaybackAutoContext.MemorySnapshot(
                        PlaybackAutoContext.MemoryTrigger.PERIODIC,
                        32L << 20,
                        128L << 20,
                        96L << 20,
                        lowRamDevice,
                        1L << 30,
                        512L << 20,
                        128L << 20,
                        false,
                        0,
                        100,
                        24L << 20);
        PlaybackAutoContext.DeviceFacts device =
                new PlaybackAutoContext.DeviceFacts(
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.MemoryPressure.UNKNOWN),
                        lowRamDevice == null
                                ? PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.MemorySnapshot.unknown())
                                : fact(memorySnapshot, now),
                        fact(96L << 20, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ThermalState.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.PowerState.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.NetworkCost.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.NetworkSnapshot.unknown()));
        PlaybackAutoContext.ResourceFacts resource =
                new PlaybackAutoContext.ResourceFacts(
                        fact(PlaybackAutoContext.Protocol.HLS, now),
                        fact(PlaybackAutoContext.StreamKind.VOD, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.RangeSupport.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.TransferUnit.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ManifestFacts.unknown()));
        PlaybackAutoContext.PathFacts path =
                new PlaybackAutoContext.PathFacts(
                        PlaybackAutoContext.Fact.unknown(PlaybackRoute.OTHER),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRoute.Owner.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(false),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRouteCapabilities.ObservedLeg
                                        .SOURCE_SPECIFIC),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRouteCapabilities.UpstreamVisibility
                                        .UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRouteCapabilities.ControlScope.NONE),
                        fact(PlaybackAutoContext.PathKind.REMOTE, now),
                        fact(PlaybackAutoContext.PathKind.REMOTE, now),
                        fact(PlaybackAutoContext.UpstreamState.VISIBLE, now));
        return new PlaybackAutoContext(
                session,
                0,
                1,
                now,
                fact(kernel, now),
                fact(decodeMode, now),
                device,
                resource,
                path,
                PlaybackAutoContext.RuntimeFacts.unknown(),
                media);
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(T value, long now) {
        return PlaybackAutoContext.Fact.forSession(
                value,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH,
                now);
    }
}
