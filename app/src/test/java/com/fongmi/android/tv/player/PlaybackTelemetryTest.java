package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackTelemetryTest {

    @Test
    public void unsafeLabelsBecomeUnknown() {
        assertEquals("unknown", PlaybackTelemetry.safeLabel("https://example.com/video.m3u8?token=secret"));
        assertEquals("unknown", PlaybackTelemetry.safeLabel("/data/user/0/app/cache/file"));
        assertEquals("unknown", PlaybackTelemetry.safeLabel("IOException: /private/path"));
        assertEquals("ready", PlaybackTelemetry.safeLabel(" ready "));
    }

    @Test
    public void decisionInputsAreBoundedAndSafe() {
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            inputs.add(PlaybackTelemetry.DecisionInput.text("metric-" + i,
                    "value-" + i, PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                    PlaybackAutoContext.Confidence.HIGH));
        }
        PlaybackTelemetry.DecisionEvent event = new PlaybackTelemetry.DecisionEvent(
                PlaybackTelemetry.DecisionDomain.NETWORK_PROTECTION,
                PlaybackTelemetry.DecisionOutcome.HELD,
                "old", "target", "result",
                "reason", "suppression", inputs);

        assertEquals(12, event.inputs().size());
        assertEquals("unknown", PlaybackTelemetry.DecisionInput.text(
                "bad name", "https://host/path", PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH).value());
        assertEquals(PlaybackAutoContext.ValueSource.UNKNOWN,
                PlaybackTelemetry.DecisionInput.unknown("missing").source());
    }

    @Test
    public void metricUnknownDoesNotTurnIntoZero() {
        PlaybackTelemetry.Metric<Long> metric = PlaybackTelemetry.Metric.unknown();

        assertFalse(metric.known());
        assertEquals(null, metric.value());
        assertEquals(PlaybackAutoContext.ValueSource.UNKNOWN, metric.source());
    }

    @Test
    public void eventSemanticKeyExcludesInputNoise() {
        PlaybackTelemetry.DecisionEvent first = event("a");
        PlaybackTelemetry.DecisionEvent second = event("b");

        assertEquals(first.semanticKey(), second.semanticKey());
        assertTrue(first.semanticKey().contains("network-protection"));
    }

    private static PlaybackTelemetry.DecisionEvent event(String inputValue) {
        return new PlaybackTelemetry.DecisionEvent(
                PlaybackTelemetry.DecisionDomain.NETWORK_PROTECTION,
                PlaybackTelemetry.DecisionOutcome.HELD,
                "normal", "normal", "normal", "stable", "no-change",
                List.of(PlaybackTelemetry.DecisionInput.text("sample", inputValue,
                        PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM)));
    }
}
