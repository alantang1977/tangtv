package com.fongmi.android.tv.player.ijk;

import androidx.media3.common.PlaybackException;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackErrorClassifier;

import org.junit.Test;

import java.util.EnumSet;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkRuntimeProfilePolicyTest {

    @Test
    public void confirmedNetworkAndDrmNeverTriggerKernelFallback() {
        IjkRuntimeProfilePolicy.FailureAssessment network = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.NETWORK_IO,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                IMediaPlayer.MEDIA_ERROR_IO,
                false,
                false,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);
        IjkRuntimeProfilePolicy.FailureAssessment drm = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.DRM,
                PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
                0,
                true,
                true,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                true);

        assertEquals(IjkRuntimeProfilePolicy.FailureKind.NETWORK,
                network.kind());
        assertFalse(network.fallbackEligible());
        assertFalse(network.persistable());
        assertEquals(IjkRuntimeProfilePolicy.FailureKind.DRM, drm.kind());
        assertFalse(drm.fallbackEligible());
        assertFalse(drm.persistable());
    }

    @Test
    public void nativeIoAndTimeoutRemainNetworkEvenWhenClassifierIsUnknown() {
        IjkRuntimeProfilePolicy.FailureAssessment io = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.UNKNOWN,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                IMediaPlayer.MEDIA_ERROR_IO,
                false,
                false,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);
        IjkRuntimeProfilePolicy.FailureAssessment timeout = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.UNKNOWN,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                IMediaPlayer.MEDIA_ERROR_TIMED_OUT,
                false,
                false,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);

        assertEquals(IjkRuntimeProfilePolicy.FailureKind.NETWORK, io.kind());
        assertFalse(io.fallbackEligible());
        assertEquals(IjkRuntimeProfilePolicy.FailureKind.NETWORK,
                timeout.kind());
        assertFalse(timeout.fallbackEligible());
    }

    @Test
    public void unknownIjkStartupFailureFallsBackWithoutPersistence() {
        IjkRuntimeProfilePolicy.FailureAssessment assessment = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.UNKNOWN,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                0,
                false,
                false,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);

        assertEquals(IjkRuntimeProfilePolicy.FailureKind.STARTUP,
                assessment.kind());
        assertTrue(assessment.fallbackEligible());
        assertFalse(assessment.persistable());
        assertEquals(IjkRuntimeProfilePolicy.Reason.IJK_STARTUP_FAILURE,
                assessment.reason());
    }

    @Test
    public void unknownIjkRuntimeFailurePersistsOnlyWithFirstFrameAndDecoder() {
        IjkRuntimeProfilePolicy.FailureAssessment runtime = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.UNKNOWN,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                0,
                true,
                true,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                true);
        IjkRuntimeProfilePolicy.FailureAssessment decoderUnknown = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.UNKNOWN,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                0,
                true,
                true,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);

        assertEquals(IjkRuntimeProfilePolicy.FailureKind.RUNTIME,
                runtime.kind());
        assertTrue(runtime.persistable());
        assertEquals(IjkRuntimeProfilePolicy.FailureKind.STARTUP,
                decoderUnknown.kind());
        assertFalse(decoderUnknown.persistable());
    }

    @Test
    public void explicitDecoderAndOutputFailuresCanPersistExactProfile() {
        IjkRuntimeProfilePolicy.FailureAssessment decoder = assess(
                IjkRuntimeProfilePolicy.Path.MPV,
                PlaybackErrorClassifier.Stage.DECODER,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                0,
                true,
                true,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                true);
        IjkRuntimeProfilePolicy.FailureAssessment noProfile = assess(
                IjkRuntimeProfilePolicy.Path.EXO,
                PlaybackErrorClassifier.Stage.OUTPUT,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
                0,
                true,
                true,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                false);

        assertTrue(decoder.fallbackEligible());
        assertTrue(decoder.persistable());
        assertEquals(IjkRuntimeProfilePolicy.Reason.DECODER_FAILURE,
                decoder.reason());
        assertTrue(noProfile.fallbackEligible());
        assertFalse(noProfile.persistable());
        assertEquals(IjkRuntimeProfilePolicy.Reason.OUTPUT_FAILURE,
                noProfile.reason());
    }

    @Test
    public void unsupportedMediaPersistsButMalformedMediaOnlyFallsBack() {
        IjkRuntimeProfilePolicy.FailureAssessment unsupported = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.MEDIA_PARSING,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                IMediaPlayer.MEDIA_ERROR_UNSUPPORTED,
                false,
                false,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);
        IjkRuntimeProfilePolicy.FailureAssessment malformed = assess(
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                PlaybackErrorClassifier.Stage.MEDIA_PARSING,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                IMediaPlayer.MEDIA_ERROR_MALFORMED,
                false,
                false,
                PlaybackAutoContext.DecodeMode.UNKNOWN,
                true);

        assertEquals(IjkRuntimeProfilePolicy.FailureKind.UNSUPPORTED,
                unsupported.kind());
        assertTrue(unsupported.persistable());
        assertEquals(IjkRuntimeProfilePolicy.FailureKind.MEDIA,
                malformed.kind());
        assertTrue(malformed.fallbackEligible());
        assertFalse(malformed.persistable());
    }

    @Test
    public void externalUnknownFailureDoesNotContinueBlindFallback() {
        IjkRuntimeProfilePolicy.FailureAssessment assessment = assess(
                IjkRuntimeProfilePolicy.Path.MPV,
                PlaybackErrorClassifier.Stage.UNKNOWN,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                0,
                true,
                true,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                true);

        assertFalse(assessment.fallbackEligible());
        assertFalse(assessment.persistable());
        assertEquals(IjkRuntimeProfilePolicy.Reason.EXTERNAL_UNKNOWN_FAILURE,
                assessment.reason());
    }

    @Test
    public void safeSoftwareFallbackAcceptsLandscapeAndPortrait1080p() {
        assertTrue(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 30_500, 8_000_000,
                false, 128L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertTrue(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_080, 1_920, 30_000, 6_000_000,
                false, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.MODERATE,
                PlaybackAutoContext.ThermalState.MODERATE)));
    }

    @Test
    public void softwareFallbackRejectsUnknownOversizedHotOrLowMemoryCases() {
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(
                IjkRuntimeProfilePolicy.SoftEligibilityInput.unknown()));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                3_840, 2_160, 30_000, 6_000_000,
                false, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 60_000, 6_000_000,
                false, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 30_000, 9_000_000,
                false, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 30_000, 6_000_000,
                true, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 30_000, 6_000_000,
                false, 127L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 30_000, 6_000_000,
                false, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.CRITICAL,
                PlaybackAutoContext.ThermalState.NOMINAL)));
        assertFalse(IjkRuntimeProfilePolicy.allowIjkSoftware(soft(
                1_920, 1_080, 30_000, 6_000_000,
                false, 256L * 1024 * 1024,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                PlaybackAutoContext.ThermalState.SEVERE)));
    }

    @Test
    public void fallbackOrderUsesVerifiedPathThenSoftMpvAndExo() {
        assertEquals(IjkRuntimeProfilePolicy.Path.EXO,
                IjkRuntimeProfilePolicy.nextFallback(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        EnumSet.of(IjkRuntimeProfilePolicy.Path.IJK_HARD),
                        true,
                        IjkRuntimeProfilePolicy.Path.EXO));
        assertEquals(IjkRuntimeProfilePolicy.Path.IJK_SOFT,
                IjkRuntimeProfilePolicy.nextFallback(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        EnumSet.of(IjkRuntimeProfilePolicy.Path.IJK_HARD),
                        true,
                        null));
        assertEquals(IjkRuntimeProfilePolicy.Path.MPV,
                IjkRuntimeProfilePolicy.nextFallback(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        EnumSet.of(IjkRuntimeProfilePolicy.Path.IJK_HARD),
                        false,
                        null));
        assertEquals(IjkRuntimeProfilePolicy.Path.EXO,
                IjkRuntimeProfilePolicy.nextFallback(
                        IjkRuntimeProfilePolicy.Path.MPV,
                        EnumSet.of(
                                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                                IjkRuntimeProfilePolicy.Path.MPV),
                        false,
                        null));
    }

    @Test
    public void stableHealthRequiresThirtySecondsFramesDropsAndNoRebuffer() {
        IjkRuntimeProfilePolicy.HealthAssessment stable =
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        true, true, 30_000, 0,
                        true, 30f, true, 30f, true, 29f,
                        true, 40, false, 0));
        IjkRuntimeProfilePolicy.HealthAssessment early =
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        true, true, 29_999, 0,
                        true, 30f, true, 30f, true, 30f,
                        true, 0, false, 0));

        assertTrue(stable.stable());
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.STABLE,
                stable.reason());
        assertFalse(early.stable());
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.CONFIRMING,
                early.reason());
    }

    @Test
    public void unhealthyOrMissingRuntimeEvidenceNeverCountsAsStable() {
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.REBUFFERED,
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        true, true, 30_000, 1,
                        true, 30f, true, 30f, true, 30f,
                        true, 0, false, 0)).reason());
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.FRAME_EVIDENCE_UNKNOWN,
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        true, true, 30_000, 0,
                        false, 0f, false, 0f, false, 0f,
                        true, 0, false, 0)).reason());
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.FRAME_RATE_UNHEALTHY,
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        true, true, 30_000, 0,
                        true, 30f, true, 30f, true, 20f,
                        true, 0, false, 0)).reason());
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.DROP_EVIDENCE_UNKNOWN,
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.IJK_HARD,
                        true, true, 30_000, 0,
                        true, 30f, true, 30f, true, 30f,
                        false, 0, false, 0)).reason());
        assertEquals(IjkRuntimeProfilePolicy.HealthReason.DROPS_UNHEALTHY,
                IjkRuntimeProfilePolicy.assessHealth(health(
                        IjkRuntimeProfilePolicy.Path.EXO,
                        true, true, 30_000, 0,
                        true, 30f, false, 0f, true, 30f,
                        false, 0, true, 24)).reason());
    }

    private static IjkRuntimeProfilePolicy.FailureAssessment assess(
            IjkRuntimeProfilePolicy.Path path,
            PlaybackErrorClassifier.Stage stage,
            int errorCode,
            int what,
            boolean prepared,
            boolean firstFrame,
            PlaybackAutoContext.DecodeMode decode,
            boolean profile) {
        return IjkRuntimeProfilePolicy.assessFailure(
                new IjkRuntimeProfilePolicy.FailureInput(
                        path, stage, errorCode, what, 0, prepared,
                        firstFrame, decode, profile));
    }

    private static IjkRuntimeProfilePolicy.SoftEligibilityInput soft(
            int width,
            int height,
            int frameRateMilli,
            long bitrate,
            boolean lowRam,
            long headroom,
            PlaybackAutoContext.MemoryPressure memory,
            PlaybackAutoContext.ThermalState thermal) {
        return new IjkRuntimeProfilePolicy.SoftEligibilityInput(
                true, true, width, height, frameRateMilli, bitrate,
                true, lowRam, headroom, memory, thermal);
    }

    private static IjkRuntimeProfilePolicy.HealthInput health(
            IjkRuntimeProfilePolicy.Path path,
            boolean active,
            boolean firstFrame,
            long duration,
            int rebuffers,
            boolean targetUsable,
            float target,
            boolean decodeUsable,
            float decode,
            boolean outputUsable,
            float output,
            boolean dropRateUsable,
            int dropRate,
            boolean droppedFramesUsable,
            long droppedFrames) {
        return new IjkRuntimeProfilePolicy.HealthInput(
                path, active, firstFrame, duration, rebuffers,
                targetUsable, target, decodeUsable, decode,
                outputUsable, output, dropRateUsable, dropRate,
                droppedFramesUsable, droppedFrames);
    }
}
