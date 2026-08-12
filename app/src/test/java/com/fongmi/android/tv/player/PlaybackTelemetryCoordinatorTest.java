package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlaybackTelemetryCoordinatorTest {

    @Test
    public void oldSessionCannotPublishAfterReplacement() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        List<PlaybackTelemetry.LogEntry> logs = new ArrayList<>();
        PlaybackTelemetryCoordinator coordinator = new PlaybackTelemetryCoordinator(store, logs::add);
        PlaybackAutoContext.SessionToken first = store.beginSession("p-telemetry-1", 100);
        assertTrue(coordinator.beginSession(first, 100));
        PlaybackAutoContext.SessionToken second = store.beginSession("p-telemetry-2", 200);

        assertFalse(coordinator.publishRuntime(first, observation(PlaybackAutoContext.PlaybackPhase.READY), 300));
        assertFalse(coordinator.publishDecision(first, decision(PlaybackTelemetry.DecisionOutcome.HELD), 300));
        assertTrue(coordinator.beginSession(second, 200));
        assertTrue(coordinator.publishRuntime(second, observation(PlaybackAutoContext.PlaybackPhase.READY), 300));
        assertEquals("p-telemetry-2", logs.get(0).traceId());
    }

    @Test
    public void repeatedDecisionIsSuppressedButAppliedIsImmediate() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        List<PlaybackTelemetry.LogEntry> logs = new ArrayList<>();
        PlaybackTelemetryCoordinator coordinator = new PlaybackTelemetryCoordinator(store, logs::add);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-telemetry-3", 100);
        assertTrue(coordinator.beginSession(session, 100));

        assertTrue(coordinator.publishDecision(session, decision(PlaybackTelemetry.DecisionOutcome.HELD), 100));
        assertFalse(coordinator.publishDecision(session, decision(PlaybackTelemetry.DecisionOutcome.HELD), 1_000));
        assertTrue(coordinator.publishDecision(session, decision(PlaybackTelemetry.DecisionOutcome.HELD), 10_100));
        assertTrue(coordinator.publishDecision(session, decision(PlaybackTelemetry.DecisionOutcome.APPLIED), 10_101));
        assertEquals(3, logs.size());
    }

    @Test
    public void runtimeSamplesAreRateLimitedButStateChangeIsImmediate() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        List<PlaybackTelemetry.LogEntry> logs = new ArrayList<>();
        PlaybackTelemetryCoordinator coordinator = new PlaybackTelemetryCoordinator(store, logs::add);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-telemetry-4", 100);
        assertTrue(coordinator.beginSession(session, 100));

        assertTrue(coordinator.publishRuntime(session, observation(PlaybackAutoContext.PlaybackPhase.READY), 100));
        assertTrue(coordinator.publishRuntime(session, observation(PlaybackAutoContext.PlaybackPhase.READY), 1_000));
        assertTrue(coordinator.publishRuntime(session, observation(PlaybackAutoContext.PlaybackPhase.BUFFERING), 1_001));
        assertEquals(2, logs.size());
        assertEquals(3, coordinator.snapshotStats().runtimeSamples());
        assertEquals(1, coordinator.snapshotStats().runtimeSuppressed());
        assertTrue(logs.get(1).message().contains("duplicateSuppressed=1"));
    }

    @Test
    public void summaryIncludesPeakBufferAndRebufferFacts() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        List<PlaybackTelemetry.LogEntry> logs = new ArrayList<>();
        PlaybackTelemetryCoordinator coordinator = new PlaybackTelemetryCoordinator(store, logs::add);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-telemetry-5", 100);
        assertTrue(coordinator.beginSession(session, 100));
        coordinator.publishRuntime(session, observation(PlaybackAutoContext.PlaybackPhase.READY), 100);
        coordinator.publishRuntime(session, observationWithBuffer(PlaybackAutoContext.PlaybackPhase.READY, 8_000, 4, 500), 200);

        PlaybackTelemetry.SessionSummary summary = coordinator.endSession(session, "finished", null, 1_000);

        assertNotNull(summary);
        assertEquals(8_000, summary.peakBufferedMs());
        assertEquals(4, summary.rebufferCount());
        assertEquals(500, summary.rebufferTotalMs());
        assertEquals("finished", summary.reason());
        assertTrue(logs.get(logs.size() - 1).tag().contains("session-summary"));
    }

    @Test
    public void emittedDecisionDoesNotContainUrlPathOrErrorBody() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        List<PlaybackTelemetry.LogEntry> logs = new ArrayList<>();
        PlaybackTelemetryCoordinator coordinator = new PlaybackTelemetryCoordinator(store, logs::add);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-telemetry-6", 100);
        assertTrue(coordinator.beginSession(session, 100));
        PlaybackTelemetry.DecisionEvent event = new PlaybackTelemetry.DecisionEvent(
                PlaybackTelemetry.DecisionDomain.OTHER,
                PlaybackTelemetry.DecisionOutcome.FAILED,
                "https://example.com/a?token=secret",
                "/data/user/0/cache/file",
                "java.io.IOException: /private/error",
                "upstream=https://host/path",
                "error body=secret",
                List.of(PlaybackTelemetry.DecisionInput.text("detail", "Authorization=secret",
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH)));

        assertTrue(coordinator.publishDecision(session, event, 200));
        String message = logs.get(0).message();
        assertFalse(message.contains("https://"));
        assertFalse(message.contains("/data/"));
        assertFalse(message.contains("secret"));
    }

    private static PlaybackTelemetry.DecisionEvent decision(PlaybackTelemetry.DecisionOutcome outcome) {
        return new PlaybackTelemetry.DecisionEvent(
                PlaybackTelemetry.DecisionDomain.NETWORK_PROTECTION, outcome,
                "normal", "normal", "normal", "stable", "no-change", List.of());
    }

    private static PlaybackTelemetry.RuntimeObservation observation(PlaybackAutoContext.PlaybackPhase phase) {
        return observationWithBuffer(phase, 1_000, 0, 0);
    }

    private static PlaybackTelemetry.RuntimeObservation observationWithBuffer(
            PlaybackAutoContext.PlaybackPhase phase, long buffered, int rebufferCount, long rebufferTotal) {
        return new PlaybackTelemetry.RuntimeObservation(
                metric(phase), metric(false), metric(1_000L), metric(100_000L), metric(buffered),
                metric(8_000_000L), metric(4_000_000L), metric(24f), metric(2L), metric(rebufferCount),
                metric(rebufferTotal), metric(900L), metric(100L));
    }

    private static <T> PlaybackTelemetry.Metric<T> metric(T value) {
        return PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
    }
}
