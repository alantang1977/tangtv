package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoFrameSchedulingExperimentMetricsTest {

    private static final String DEVICE =
            ExoFrameSchedulingExperimentIdentity.deviceDigest(
                    "private/fingerprint", "vendor", "model", 1,
                    "1", "media3");

    @Test
    public void bindsAssignmentFormatOutputAndTimingToOneSession() {
        ExoFrameSchedulingExperimentMetrics metrics =
                new ExoFrameSchedulingExperimentMetrics();
        ExoFrameSchedulingExperimentPolicy.Decision decision = decision(
                ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_75_DURATION_OFF);
        metrics.begin(
                "p-abc-1",
                decision,
                new ExoDecoderRuntimeSession.OutputConfig(
                        ExoDecoderRuntimeSession.OutputTarget.SURFACE, true),
                "auto",
                DEVICE,
                1_000);
        metrics.observeDecoder("vendor.decoder/secret/path.secure");
        metrics.observeFormat(new Format.Builder()
                .setSampleMimeType("video/hevc")
                .setCodecs("hvc1.secret-token")
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(59.94f)
                .build());
        metrics.observeFirstFrame(1_350);
        metrics.observeFirstFrame(9_999);
        metrics.observeBoundary(
                ExoFrameSchedulingExperimentMetrics.Boundary.PAUSE);
        metrics.observeBoundary(
                ExoFrameSchedulingExperimentMetrics.Boundary.SEEK);
        metrics.observeBoundary(
                ExoFrameSchedulingExperimentMetrics.Boundary.REBUFFER);
        metrics.observeBoundary(
                ExoFrameSchedulingExperimentMetrics.Boundary.FORMAT_CHANGE);
        ExoFrameTimingMetrics timing = new ExoFrameTimingMetrics();
        timing.observeProcessingOffset(-10_000, 1);
        timing.observeFrameRelease(
                0, 1_010_000_000L, 1_000_000_000L);

        ExoFrameSchedulingExperimentMetrics.Snapshot snapshot =
                metrics.snapshot(timing.snapshot(), 7, 2);

        assertTrue(snapshot.active());
        assertEquals("early-75-duration-off", snapshot.unitId());
        assertEquals(350, snapshot.firstFrameMs());
        assertEquals(12, snapshot.deviceId().length());
        assertEquals(12, snapshot.decoderId().length());
        assertEquals("video/hevc", snapshot.mimeType());
        assertEquals(3840, snapshot.width());
        assertEquals(2160, snapshot.height());
        assertEquals(59_940, snapshot.frameRateMilli());
        assertEquals(1, snapshot.secureHint());
        assertEquals(1, snapshot.pauseBoundaries());
        assertEquals(1, snapshot.seekBoundaries());
        assertEquals(1, snapshot.rebufferBoundaries());
        assertEquals(1, snapshot.formatBoundaries());
        assertEquals(7, snapshot.droppedFrames());
        assertEquals(2, snapshot.rebufferCount());
    }

    @Test
    public void summaryContainsOnlyDigestsEnumsAndNumbers() {
        ExoFrameSchedulingExperimentMetrics metrics =
                new ExoFrameSchedulingExperimentMetrics();
        metrics.begin(
                "p-safe-2",
                decision(ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_100_DURATION_ON),
                new ExoDecoderRuntimeSession.OutputConfig(
                        ExoDecoderRuntimeSession.OutputTarget.TEXTURE, false),
                "async",
                DEVICE,
                100);
        metrics.observeDecoder("https://secret.example/decoder/local/path");
        metrics.observeFormat(new Format.Builder()
                .setSampleMimeType("video/avc")
                .setCodecs("token@example.com/private/path")
                .setWidth(1920)
                .setHeight(1080)
                .build());
        metrics.observeFirstFrame(200);

        String summary = metrics.snapshot(
                new ExoFrameTimingMetrics().snapshot(), 0, 0)
                .logSummary();

        assertFalse(summary.contains("secret.example"));
        assertFalse(summary.contains("local/path"));
        assertFalse(summary.contains("token@example.com"));
        assertFalse(summary.contains("private/fingerprint"));
        assertTrue(summary.contains("unit=early-100-duration-on"));
        assertTrue(summary.contains("decoder="));
        assertTrue(summary.contains("codec="));
    }

    @Test
    public void newSessionClearsPreviousEvidenceAndInvalidTraceIsInactive() {
        ExoFrameSchedulingExperimentMetrics metrics =
                new ExoFrameSchedulingExperimentMetrics();
        metrics.begin(
                "p-first-1",
                decision(ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_50_DURATION_ON),
                ExoDecoderRuntimeSession.OutputConfig.unknown(),
                "auto", DEVICE, 100);
        metrics.observeDecoder("decoder.one");
        metrics.observeFirstFrame(200);

        metrics.begin(
                "not-a-trace",
                decision(ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_75_DURATION_ON),
                ExoDecoderRuntimeSession.OutputConfig.unknown(),
                "auto", DEVICE, 300);
        ExoFrameSchedulingExperimentMetrics.Snapshot snapshot =
                metrics.snapshot(new ExoFrameTimingMetrics().snapshot(), 0, 0);

        assertFalse(snapshot.active());
        assertEquals("unknown", snapshot.decoderId());
        assertEquals(-1, snapshot.firstFrameMs());
    }

    @Test
    public void stableSessionsDoNotCollectExperimentEvidence() {
        ExoFrameSchedulingExperimentMetrics metrics =
                new ExoFrameSchedulingExperimentMetrics();

        boolean active = metrics.begin(
                "p-stable-1",
                ExoFrameSchedulingExperimentPolicy.stableDecision(
                        false, true, false),
                ExoDecoderRuntimeSession.OutputConfig.unknown(),
                "auto", DEVICE, 100);
        metrics.observeDecoder("decoder.should.not.be.collected");
        metrics.observeFirstFrame(200);

        ExoFrameSchedulingExperimentMetrics.Snapshot snapshot =
                metrics.snapshot(new ExoFrameTimingMetrics().snapshot(), 0, 0);
        assertFalse(active);
        assertFalse(snapshot.active());
        assertEquals("unknown", snapshot.decoderId());
        assertEquals(-1, snapshot.firstFrameMs());
    }

    private static ExoFrameSchedulingExperimentPolicy.Decision decision(
            ExoFrameSchedulingExperimentPolicy.Unit unit) {
        return ExoFrameSchedulingExperimentPolicy.decide(
                new ExoFrameSchedulingExperimentPolicy.Input(
                        true, true, true, true, false, true,
                        ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                                new ExoFrameSchedulingExperimentPolicy
                                        .RawAssignment(1, DEVICE, unit.id()),
                                DEVICE)));
    }
}
