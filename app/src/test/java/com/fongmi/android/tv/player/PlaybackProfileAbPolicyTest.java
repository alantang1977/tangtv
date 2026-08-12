package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlaybackProfileAbPolicyTest {

    private static final String DEVICE =
            PlaybackProfileAbIdentity.deviceDigest(
                    "fingerprint", 10, "1.0", "media3");

    @Test
    public void enrollmentDefaultsOffAndCurrentDeviceMustMatch() {
        PlaybackProfileAbPolicy.EnrollmentResolution missing =
                PlaybackProfileAbPolicy.resolveEnrollment(null, DEVICE);
        PlaybackProfileAbPolicy.EnrollmentResolution current =
                PlaybackProfileAbPolicy.resolveEnrollment(
                        new PlaybackProfileAbPolicy.RawEnrollment(
                                1, DEVICE, true), DEVICE);
        PlaybackProfileAbPolicy.EnrollmentResolution stale =
                PlaybackProfileAbPolicy.resolveEnrollment(
                        new PlaybackProfileAbPolicy.RawEnrollment(
                                1, DEVICE, true),
                        PlaybackProfileAbIdentity.deviceDigest(
                                "other", 10, "1.0", "media3"));

        assertEquals(
                PlaybackProfileAbPolicy.EnrollmentStatus.NOT_ENROLLED,
                missing.status());
        assertFalse(missing.active());
        assertTrue(current.active());
        assertEquals(
                PlaybackProfileAbPolicy.EnrollmentStatus.STALE_DEVICE,
                stale.status());
        assertTrue(stale.failClosed());
    }

    @Test
    public void corruptAndFutureEnrollmentFailClosed() {
        PlaybackProfileAbPolicy.EnrollmentResolution corrupt =
                PlaybackProfileAbPolicy.resolveEnrollment(
                        new PlaybackProfileAbPolicy.RawEnrollment(
                                1.0d, DEVICE, true), DEVICE);
        PlaybackProfileAbPolicy.EnrollmentResolution future =
                PlaybackProfileAbPolicy.resolveEnrollment(
                        new PlaybackProfileAbPolicy.RawEnrollment(
                                99, DEVICE, true), DEVICE);

        assertEquals(
                PlaybackProfileAbPolicy.EnrollmentStatus.CORRUPT,
                corrupt.status());
        assertTrue(corrupt.failClosed());
        assertEquals(
                PlaybackProfileAbPolicy.EnrollmentStatus.FUTURE_SCHEMA,
                future.status());
        assertTrue(future.failClosed());
    }

    @Test
    public void onlyAutoAndRecommendedProfilesMapToArms() {
        assertEquals(PlaybackProfileAbPolicy.Arm.AUTO,
                PlaybackProfileAbPolicy.armForProfile(
                        PlaybackPerformanceSetting.PROFILE_AUTO));
        assertEquals(PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                PlaybackProfileAbPolicy.armForProfile(
                        PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertNull(PlaybackProfileAbPolicy.armForProfile(
                PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
        assertNull(PlaybackProfileAbPolicy.armForProfile(
                PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
        assertNull(PlaybackProfileAbPolicy.armForProfile(
                PlaybackPerformanceSetting.PROFILE_CUSTOM));
    }

    @Test
    public void internalExperimentAndCurrentKernelDomainMustBothAllowCollection() {
        PlaybackExperimentPolicy.State exoOnly =
                new PlaybackExperimentPolicy.State(
                        PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                        true, true, false, false);
        PlaybackExperimentPolicy.State stable =
                PlaybackExperimentPolicy.State.stable();

        assertTrue(PlaybackProfileAbPolicy.gateAllows(
                exoOnly, PlaybackAutoContext.Kernel.EXO));
        assertFalse(PlaybackProfileAbPolicy.gateAllows(
                exoOnly, PlaybackAutoContext.Kernel.MPV));
        assertFalse(PlaybackProfileAbPolicy.gateAllows(
                exoOnly, PlaybackAutoContext.Kernel.IJK));
        assertFalse(PlaybackProfileAbPolicy.gateAllows(
                stable, PlaybackAutoContext.Kernel.EXO));
        assertFalse(PlaybackProfileAbPolicy.gateAllows(
                exoOnly, PlaybackAutoContext.Kernel.UNKNOWN));
    }

    @Test
    public void completeFactsCreatePrivacySafeResolutionIndependentGroup() {
        PlaybackProfileAbPolicy.GroupResolution first =
                PlaybackProfileAbPolicy.resolveGroup(
                        context(1920, PlaybackAutoContext.HdrType.HDR10),
                        DEVICE,
                        1_000);
        PlaybackProfileAbPolicy.GroupResolution adaptedResolution =
                PlaybackProfileAbPolicy.resolveGroup(
                        context(3840, PlaybackAutoContext.HdrType.HDR10),
                        DEVICE,
                        1_000);

        assertTrue(first.ready());
        assertEquals(first.key(), adaptedResolution.key());
        assertTrue(PlaybackProfileAbIdentity.validDigest(
                first.key().decoderDigest()));
        assertFalse(first.key().canonical().contains("secret-decoder"));
        assertEquals(PlaybackProfileAbPolicy.VideoMimeClass.HEVC,
                first.key().videoMime());
    }

    @Test
    public void unknownCriticalFactsNeverEnterStatistics() {
        PlaybackProfileAbPolicy.GroupResolution unknownHdr =
                PlaybackProfileAbPolicy.resolveGroup(
                        context(1920, PlaybackAutoContext.HdrType.UNKNOWN),
                        DEVICE,
                        1_000);
        PlaybackProfileAbPolicy.GroupResolution invalidDevice =
                PlaybackProfileAbPolicy.resolveGroup(
                        context(1920, PlaybackAutoContext.HdrType.HDR10),
                        "raw-fingerprint",
                        1_000);

        assertFalse(unknownHdr.ready());
        assertEquals(PlaybackProfileAbPolicy.GroupReason.HDR_UNKNOWN,
                unknownHdr.reason());
        assertFalse(invalidDevice.ready());
        assertEquals(PlaybackProfileAbPolicy.GroupReason.INVALID_DEVICE,
                invalidDevice.reason());
    }

    private static PlaybackAutoContext context(
            int width,
            PlaybackAutoContext.HdrType hdrType) {
        long now = 100;
        PlaybackAutoContext.SessionToken session =
                new PlaybackAutoContext.SessionToken("p-a-1", 1);
        PlaybackAutoContext.TrackFacts video =
                new PlaybackAutoContext.TrackFacts(
                        fact("video/hevc", now),
                        fact("hvc1.2.4", now),
                        fact(2, now),
                        fact(4, now),
                        fact(width, now),
                        fact(1080, now),
                        fact(24f, now),
                        hdrType == PlaybackAutoContext.HdrType.UNKNOWN
                                ? PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.HdrType.UNKNOWN)
                                : fact(hdrType, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ColorSnapshot.unknown()),
                        fact(4_000_000L, now),
                        fact(6_000_000L, now));
        PlaybackAutoContext.DecoderFacts decoder =
                new PlaybackAutoContext.DecoderFacts(
                        1,
                        fact("vendor.secret-decoder/path", now),
                        PlaybackAutoContext.Fact.unknown(""),
                        fact(PlaybackAutoContext.DecodeMode.HARDWARE, now),
                        fact(false, now));
        PlaybackAutoContext.MediaFacts media =
                new PlaybackAutoContext.MediaFacts(
                        1,
                        video,
                        PlaybackAutoContext.TrackFacts.unknown(),
                        decoder,
                        PlaybackAutoContext.OutputFacts.unknown(),
                        PlaybackAutoContext.DisplayFacts.unknown());
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
                fact(PlaybackAutoContext.Kernel.EXO, now),
                fact(PlaybackAutoContext.DecodeMode.HARDWARE, now),
                PlaybackAutoContext.DeviceFacts.unknown(),
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
