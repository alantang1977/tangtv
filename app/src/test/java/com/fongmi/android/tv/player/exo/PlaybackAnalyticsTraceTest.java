package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackTrace;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackAnalyticsTraceTest {

    @After
    public void tearDown() {
        PlaybackAnalyticsListener.reset();
    }

    @Test
    public void analyticsSessionKeepsManagerTrace() {
        PlaybackAnalyticsListener.beginSession("p-abc-1");

        assertEquals("p-abc-1", PlaybackAnalyticsListener.getPlaybackTraceId());
    }

    @Test
    public void invalidTraceFallsBackToNone() {
        PlaybackAnalyticsListener.beginSession("movie-token-url");

        assertEquals(PlaybackTrace.NONE, PlaybackAnalyticsListener.getPlaybackTraceId());
    }

    @Test
    public void schedulingUnitIsBoundToTheAnalyticsTraceSession() {
        String device = ExoFrameSchedulingExperimentIdentity.deviceDigest(
                "f", "v", "m", 1, "1", "media3");
        ExoFrameSchedulingExperimentPolicy.Decision decision =
                ExoFrameSchedulingExperimentPolicy.decide(
                        new ExoFrameSchedulingExperimentPolicy.Input(
                                true, true, true, true, false, true,
                                ExoFrameSchedulingExperimentPolicy
                                        .resolveAssignment(
                                                new ExoFrameSchedulingExperimentPolicy
                                                        .RawAssignment(
                                                        1,
                                                        device,
                                                        ExoFrameSchedulingExperimentPolicy.Unit
                                                                .EARLY_75_DURATION_ON
                                                                .id()),
                                                device)));

        PlaybackAnalyticsListener.beginSession(
                "p-frame-1",
                decision,
                new ExoDecoderRuntimeSession.OutputConfig(
                        ExoDecoderRuntimeSession.OutputTarget.SURFACE,
                        false),
                "auto");

        ExoFrameSchedulingExperimentMetrics.Snapshot snapshot =
                PlaybackAnalyticsListener
                        .getFrameSchedulingExperimentSnapshot();
        assertTrue(snapshot.active());
        assertTrue(snapshot.experimentApplied());
        assertEquals("p-frame-1", snapshot.traceId());
        assertEquals("early-75-duration-on", snapshot.unitId());

        PlaybackAnalyticsListener.reset();
        assertFalse(PlaybackAnalyticsListener
                .getFrameSchedulingExperimentSnapshot().active());
    }
}
