package com.fongmi.android.tv.player;

import java.util.EnumMap;
import java.util.Map;

/** Session-safe, rate-limited publisher for runtime facts, decision events and end summaries. */
public final class PlaybackTelemetryCoordinator {

    static final long DECISION_REPEAT_LOG_INTERVAL_MS = 10_000L;
    static final long RUNTIME_LOG_INTERVAL_MS = 15_000L;

    private static final PlaybackTelemetryCoordinator PROCESS = new PlaybackTelemetryCoordinator(
            PlaybackAutoContextStore.process(),
            entry -> PlaybackTrace.log(entry.tag(), entry.traceId(), "%s", entry.message()));

    private final PlaybackAutoContextStore store;
    private final LogSink sink;
    private final Map<PlaybackTelemetry.DecisionDomain, DecisionState> decisions =
            new EnumMap<>(PlaybackTelemetry.DecisionDomain.class);
    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private long startedAtElapsedMs;
    private long decisionEvaluations;
    private long decisionEmitted;
    private long decisionSuppressed;
    private long runtimeSamples;
    private long runtimeLogs;
    private long runtimeSuppressed;
    private long pendingRuntimeSuppressed;
    private long lastRuntimeLogAtMs = -1;
    private RuntimeSignificantKey lastRuntimeKey;
    private PlaybackAutoContext.RuntimeFacts lastRuntime = PlaybackAutoContext.RuntimeFacts.unknown();
    private long peakBufferedMs = -1;
    private long maxDroppedFrames = -1;
    private int rebufferCount = -1;
    private long rebufferTotalMs = -1;

    public static PlaybackTelemetryCoordinator process() {
        return PROCESS;
    }

    public PlaybackTelemetryCoordinator(PlaybackAutoContextStore store, LogSink sink) {
        this.store = store == null ? new PlaybackAutoContextStore() : store;
        this.sink = sink == null ? entry -> { } : sink;
    }

    public synchronized boolean beginSession(PlaybackAutoContext.SessionToken session, long startedAtElapsedMs) {
        if (session == null || !session.active() || !session.equals(store.snapshot().session())) return false;
        this.session = session;
        this.startedAtElapsedMs = Math.max(0, startedAtElapsedMs);
        decisions.clear();
        decisionEvaluations = 0;
        decisionEmitted = 0;
        decisionSuppressed = 0;
        runtimeSamples = 0;
        runtimeLogs = 0;
        runtimeSuppressed = 0;
        pendingRuntimeSuppressed = 0;
        lastRuntimeLogAtMs = -1;
        lastRuntimeKey = null;
        lastRuntime = PlaybackAutoContext.RuntimeFacts.unknown();
        peakBufferedMs = -1;
        maxDroppedFrames = -1;
        rebufferCount = -1;
        rebufferTotalMs = -1;
        return true;
    }

    public synchronized boolean isActiveTrace(String traceId) {
        return session.active() && session.traceId().equals(PlaybackTrace.normalize(traceId))
                && session.equals(store.snapshot().session());
    }

    public synchronized boolean publishRuntime(
            PlaybackAutoContext.SessionToken session,
            PlaybackTelemetry.RuntimeObservation observation,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        long now = Math.max(0, sampledAtElapsedMs);
        PlaybackAutoContext.RuntimeFacts runtime = PlaybackTelemetryMapper.map(observation, now);
        if (!store.publishRuntimeFacts(session, runtime, now)) return false;
        runtimeSamples++;
        lastRuntime = runtime;
        updateAggregates(runtime);
        RuntimeSignificantKey key = RuntimeSignificantKey.from(runtime);
        boolean significant = lastRuntimeKey == null || !lastRuntimeKey.equals(key);
        boolean intervalElapsed = lastRuntimeLogAtMs < 0 || now - lastRuntimeLogAtMs >= RUNTIME_LOG_INTERVAL_MS;
        if (!significant && !intervalElapsed) {
            runtimeSuppressed++;
            pendingRuntimeSuppressed++;
            return true;
        }
        long suppressed = pendingRuntimeSuppressed;
        pendingRuntimeSuppressed = 0;
        lastRuntimeKey = key;
        lastRuntimeLogAtMs = now;
        runtimeLogs++;
        PlaybackAutoContext context = store.snapshot();
        emit("playback-telemetry", session.traceId(),
                "event=sample revision=" + context.revision()
                        + " duplicateSuppressed=" + suppressed + " "
                        + PlaybackTelemetryMapper.runtimeLogSummary(runtime)
                        + " " + conditionSummary(context));
        return true;
    }

    public synchronized boolean publishDecision(
            PlaybackAutoContext.SessionToken session,
            PlaybackTelemetry.DecisionEvent event,
            long sampledAtElapsedMs) {
        if (!isCurrent(session) || event == null) return false;
        return publishDecisionLocked(event, Math.max(0, sampledAtElapsedMs));
    }

    public synchronized boolean publishDecision(
            String traceId,
            PlaybackTelemetry.DecisionEvent event,
            long sampledAtElapsedMs) {
        if (!isActiveTrace(traceId) || event == null) return false;
        return publishDecisionLocked(event, Math.max(0, sampledAtElapsedMs));
    }

    private boolean publishDecisionLocked(PlaybackTelemetry.DecisionEvent event, long now) {
        decisionEvaluations++;
        DecisionState state = decisions.computeIfAbsent(event.domain(), ignored -> new DecisionState());
        String semanticKey = event.semanticKey();
        boolean repeated = semanticKey.equals(state.semanticKey);
        boolean force = event.outcome() == PlaybackTelemetry.DecisionOutcome.APPLIED
                || event.outcome() == PlaybackTelemetry.DecisionOutcome.REQUESTED
                || event.outcome() == PlaybackTelemetry.DecisionOutcome.FAILED;
        boolean intervalElapsed = state.lastEmittedAtMs < 0
                || now - state.lastEmittedAtMs >= DECISION_REPEAT_LOG_INTERVAL_MS;
        if (repeated && !force && !intervalElapsed) {
            state.suppressed++;
            decisionSuppressed++;
            return false;
        }
        long suppressed = state.suppressed;
        state.semanticKey = semanticKey;
        state.lastEmittedAtMs = now;
        state.suppressed = 0;
        decisionEmitted++;
        PlaybackAutoContext context = store.snapshot();
        emit("playback-decision", session.traceId(),
                "event=decision domain=" + event.domain().label()
                        + " revision=" + context.revision()
                        + " outcome=" + event.outcome().label()
                        + " old=" + event.oldValue()
                        + " target=" + event.targetValue()
                        + " result=" + event.resultValue()
                        + " reason=" + event.reason()
                        + " suppression=" + event.suppressionReason()
                        + " duplicateSuppressed=" + suppressed
                        + " inputs=" + inputSummary(event));
        return true;
    }

    public synchronized PlaybackTelemetry.SessionSummary endSession(
            PlaybackAutoContext.SessionToken session,
            String reason,
            PlaybackTelemetry.RuntimeObservation finalObservation,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return null;
        long now = Math.max(0, sampledAtElapsedMs);
        if (finalObservation != null) publishRuntime(session, finalObservation, now);
        PlaybackAutoContext context = store.snapshot();
        PlaybackTelemetry.SessionSummary summary = new PlaybackTelemetry.SessionSummary(
                session.traceId(), PlaybackTelemetry.safeLabel(reason),
                Math.max(0, now - startedAtElapsedMs),
                decisionEvaluations, decisionEmitted, decisionSuppressed,
                runtimeSamples, runtimeLogs, runtimeSuppressed,
                peakBufferedMs, maxDroppedFrames, rebufferCount, rebufferTotalMs);
        emit("playback-session-summary", session.traceId(),
                "event=session-end reason=" + summary.reason()
                        + " durationMs=" + summary.durationMs()
                        + " decisionEvaluations=" + summary.decisionEvaluations()
                        + " decisionEmitted=" + summary.decisionEmitted()
                        + " decisionSuppressed=" + summary.decisionSuppressed()
                        + " runtimeSamples=" + summary.runtimeSamples()
                        + " runtimeLogs=" + summary.runtimeLogs()
                        + " runtimeSuppressed=" + summary.runtimeSuppressed()
                        + " peakBufferedMs=" + summary.peakBufferedMs()
                        + " maxDropped=" + summary.maxDroppedFrames()
                        + " rebufferCount=" + summary.rebufferCount()
                        + " rebufferTotalMs=" + summary.rebufferTotalMs()
                        + " " + PlaybackTelemetryMapper.runtimeLogSummary(lastRuntime)
                        + " " + conditionSummary(context));
        clearSession();
        return summary;
    }

    public synchronized PlaybackTelemetry.SessionSummary snapshotStats() {
        return new PlaybackTelemetry.SessionSummary(
                session.traceId(), "active", 0,
                decisionEvaluations, decisionEmitted, decisionSuppressed,
                runtimeSamples, runtimeLogs, runtimeSuppressed,
                peakBufferedMs, maxDroppedFrames, rebufferCount, rebufferTotalMs);
    }

    private void updateAggregates(PlaybackAutoContext.RuntimeFacts runtime) {
        if (runtime.bufferedDurationMs().hasValue()) {
            peakBufferedMs = Math.max(peakBufferedMs, runtime.bufferedDurationMs().value());
        }
        if (runtime.droppedFrames().hasValue()) {
            maxDroppedFrames = Math.max(maxDroppedFrames, runtime.droppedFrames().value());
        }
        if (runtime.rebufferCount().hasValue()) rebufferCount = runtime.rebufferCount().value();
        if (runtime.rebufferTotalMs().hasValue()) rebufferTotalMs = runtime.rebufferTotalMs().value();
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken session) {
        return session != null && session.active() && session.equals(this.session)
                && session.equals(store.snapshot().session());
    }

    private void emit(String tag, String traceId, String message) {
        sink.log(new PlaybackTelemetry.LogEntry(tag, traceId, message));
    }

    private static String inputSummary(PlaybackTelemetry.DecisionEvent event) {
        if (event.inputs().isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (PlaybackTelemetry.DecisionInput input : event.inputs()) {
            if (builder.length() > 0) builder.append(',');
            builder.append(input.name()).append(':').append(input.value())
                    .append(':').append(input.source().label())
                    .append(':').append(input.confidence().label());
        }
        return builder.toString();
    }

    private static String conditionSummary(PlaybackAutoContext context) {
        PlaybackAutoContext.DeviceFacts device = context == null
                ? PlaybackAutoContext.DeviceFacts.unknown() : context.device();
        String pressure = device.memoryPressure().hasValue()
                ? device.memoryPressure().value().label() : "unknown";
        long javaHeadroom = device.memorySnapshot().hasValue()
                && device.memorySnapshot().value().javaHeapHeadroomBytes() != null
                ? device.memorySnapshot().value().javaHeapHeadroomBytes() : -1;
        String thermal = device.thermalState().hasValue()
                ? device.thermalState().value().label() : "unknown";
        String power = device.powerState().hasValue()
                ? device.powerState().value().label() : "unknown";
        String networkCost = device.networkCost().hasValue()
                ? device.networkCost().value().label() : "unknown";
        return "memory=" + pressure + " javaHeadroom=" + javaHeadroom
                + " thermal=" + thermal + " power=" + power + " networkCost=" + networkCost;
    }

    private void clearSession() {
        session = PlaybackAutoContext.SessionToken.none();
        startedAtElapsedMs = 0;
        decisions.clear();
        lastRuntimeKey = null;
        lastRuntime = PlaybackAutoContext.RuntimeFacts.unknown();
        lastRuntimeLogAtMs = -1;
        pendingRuntimeSuppressed = 0;
    }

    @FunctionalInterface
    public interface LogSink {
        void log(PlaybackTelemetry.LogEntry entry);
    }

    private static final class DecisionState {
        private String semanticKey = "";
        private long lastEmittedAtMs = -1;
        private long suppressed;
    }

    private record RuntimeSignificantKey(
            String phase,
            String loading,
            String dropped,
            String rebufferCount,
            String firstFrame) {

        private static RuntimeSignificantKey from(PlaybackAutoContext.RuntimeFacts runtime) {
            return new RuntimeSignificantKey(
                    PlaybackTelemetryMapper.factValue(runtime.phase()),
                    PlaybackTelemetryMapper.factValue(runtime.loading()),
                    PlaybackTelemetryMapper.factValue(runtime.droppedFrames()),
                    PlaybackTelemetryMapper.factValue(runtime.rebufferCount()),
                    PlaybackTelemetryMapper.factValue(runtime.firstFrameElapsedMs()));
        }
    }
}
