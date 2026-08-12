package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackMemoryCoordinator;
import com.fongmi.android.tv.player.PlaybackTrace;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Session-safe shared EXO memory decision state consumed by LoadControl and preloading. */
final class ExoMemoryPressureCoordinator implements AutoCloseable {

    private static final ExoMemoryPressureCoordinator PROCESS = new ExoMemoryPressureCoordinator(
            PlaybackAutoContextStore.process(),
            PlaybackMemoryCoordinator.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<State> state;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners;
    private final PlaybackMemoryCoordinator.Registration memoryRegistration;

    ExoMemoryPressureCoordinator(
            PlaybackAutoContextStore store,
            PlaybackMemoryCoordinator memoryCoordinator) {
        this.store = Objects.requireNonNull(store);
        this.state = new AtomicReference<>(State.empty());
        this.listeners = new CopyOnWriteArrayList<>();
        this.memoryRegistration = Objects.requireNonNull(memoryCoordinator)
                .addListener(this::onMemoryUpdate);
    }

    static ExoMemoryPressureCoordinator process() {
        return PROCESS;
    }

    boolean publishBaseline(
            PlaybackAutoContext.SessionToken session,
            ExoTargetBufferPolicy.Decision baselineDecision,
            int configuredTargetBytes,
            ExoBufferBudget.Budget fallbackBudget,
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long publishedAtElapsedMs) {
        if (session == null || !session.active()
                || baselineDecision == null || fallbackBudget == null
                || !isCurrentExoSession(session)) {
            return false;
        }
        long publishedAt = Math.max(0, publishedAtElapsedMs);
        Update update;
        synchronized (this) {
            State previous = state.get();
            ExoMemoryPressurePolicy.Decision previousPressure = session.equals(previous.session())
                    ? previous.pressureDecision() : null;
            Baseline baseline = new Baseline(
                    baselineDecision,
                    Math.max(0, configuredTargetBytes),
                    fallbackBudget);
            PlaybackAutoContext.DeviceFacts device = deviceFacts == null
                    ? PlaybackAutoContext.DeviceFacts.unknown() : deviceFacts;
            ExoTargetBufferPolicy.Decision safety = safetyDecision(
                    baseline, device, publishedAt);
            ExoMemoryPressurePolicy.Decision pressure = ExoMemoryPressurePolicy.resolve(
                    previousPressure,
                    baselineDecision.targetBytes(),
                    safety.targetBytes(),
                    observation(device, publishedAt));
            State next = new State(session, baseline, pressure, publishedAt);
            state.set(next);
            update = new Update(
                    session,
                    previousPressure,
                    pressure,
                    publishedAt);
        }
        if (!isCurrentExoSession(session)) {
            clearIfSession(session);
            return false;
        }
        notifyListeners(update);
        return true;
    }

    ExoMemoryPressurePolicy.Decision currentDecision() {
        return currentDecision(store.snapshot().session());
    }

    ExoMemoryPressurePolicy.Decision currentDecision(
            PlaybackAutoContext.SessionToken session) {
        State current = state.get();
        if (session == null || !session.active()
                || !session.equals(current.session())
                || !isCurrentExoSession(session)) {
            return null;
        }
        return current.pressureDecision();
    }

    ExoMemoryPressurePolicy.Decision currentDecision(String playbackTraceId) {
        String traceId = PlaybackTrace.normalize(playbackTraceId);
        State current = state.get();
        if (!current.session().active()
                || !current.session().traceId().equals(traceId)
                || !isCurrentExoSession(current.session())) {
            return null;
        }
        return current.pressureDecision();
    }

    Registration addListener(Listener listener) {
        ListenerRegistration registration = new ListenerRegistration(
                Objects.requireNonNull(listener));
        listeners.add(registration);
        return registration;
    }

    int listenerCount() {
        return listeners.size();
    }

    @Override
    public void close() {
        memoryRegistration.close();
        for (ListenerRegistration registration : listeners) registration.close();
        state.set(State.empty());
    }

    private void onMemoryUpdate(PlaybackMemoryCoordinator.Update memoryUpdate) {
        if (memoryUpdate == null) return;
        PlaybackAutoContext.SessionToken session = memoryUpdate.session();
        Update update;
        synchronized (this) {
            State previous = state.get();
            if (!session.equals(previous.session()) || previous.baseline() == null
                    || !isCurrentExoSession(session)) {
                return;
            }
            long publishedAt = Math.max(0, memoryUpdate.publishedAtElapsedMs());
            PlaybackAutoContext context = store.snapshot();
            if (!session.equals(context.session())) return;
            ExoTargetBufferPolicy.Decision safety = safetyDecision(
                    previous.baseline(), context.device(), publishedAt);
            ExoMemoryPressurePolicy.Decision pressure = ExoMemoryPressurePolicy.resolve(
                    previous.pressureDecision(),
                    previous.baseline().decision().targetBytes(),
                    safety.targetBytes(),
                    observation(context.device(), publishedAt));
            State next = new State(
                    session,
                    previous.baseline(),
                    pressure,
                    publishedAt);
            state.set(next);
            update = new Update(
                    session,
                    previous.pressureDecision(),
                    pressure,
                    publishedAt);
        }
        if (!isCurrentExoSession(session)) {
            clearIfSession(session);
            return;
        }
        notifyListeners(update);
    }

    private ExoTargetBufferPolicy.Decision safetyDecision(
            Baseline baseline,
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long nowElapsedMs) {
        return ExoTargetBufferPolicy.resolve(
                baseline.decision().mediaDemand(),
                baseline.configuredTargetBytes(),
                baseline.fallbackBudget(),
                baseline.decision().unknownMediaFallback(),
                deviceFacts,
                nowElapsedMs);
    }

    private static ExoMemoryPressurePolicy.Observation observation(
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long nowElapsedMs) {
        PlaybackAutoContext.DeviceFacts device = deviceFacts == null
                ? PlaybackAutoContext.DeviceFacts.unknown() : deviceFacts;
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure =
                device.memoryPressure();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshot =
                device.memorySnapshot();
        boolean pressureUsable = pressure.isUsable(nowElapsedMs);
        boolean snapshotUsable = snapshot.isUsable(nowElapsedMs);
        long pressureSample = pressureUsable ? pressure.sampledAtElapsedMs() : -1;
        long snapshotSample = snapshotUsable ? snapshot.sampledAtElapsedMs() : -1;
        long sampledAt = Math.max(pressureSample, snapshotSample);
        return new ExoMemoryPressurePolicy.Observation(
                pressureUsable
                        ? pressure.value() : PlaybackAutoContext.MemoryPressure.UNKNOWN,
                pressureUsable,
                snapshotUsable,
                sampledAt);
    }

    private boolean isCurrentExoSession(PlaybackAutoContext.SessionToken session) {
        PlaybackAutoContext context = store.snapshot();
        return session != null
                && session.active()
                && session.equals(context.session())
                && (!context.kernel().hasValue()
                || context.kernel().value() == PlaybackAutoContext.Kernel.EXO);
    }

    private void clearIfSession(PlaybackAutoContext.SessionToken session) {
        State current = state.get();
        if (session.equals(current.session())) state.compareAndSet(current, State.empty());
    }

    private void notifyListeners(Update update) {
        for (ListenerRegistration registration : listeners) {
            if (registration.closed.get()) continue;
            try {
                registration.listener.onDecision(update);
            } catch (Throwable ignored) {
            }
        }
    }

    interface Listener {
        void onDecision(Update update);
    }

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    record Update(
            PlaybackAutoContext.SessionToken session,
            ExoMemoryPressurePolicy.Decision previous,
            ExoMemoryPressurePolicy.Decision decision,
            long publishedAtElapsedMs) {
    }

    private record Baseline(
            ExoTargetBufferPolicy.Decision decision,
            int configuredTargetBytes,
            ExoBufferBudget.Budget fallbackBudget) {
    }

    private record State(
            PlaybackAutoContext.SessionToken session,
            Baseline baseline,
            ExoMemoryPressurePolicy.Decision pressureDecision,
            long publishedAtElapsedMs) {

        private static State empty() {
            return new State(
                    PlaybackAutoContext.SessionToken.none(),
                    null,
                    null,
                    -1);
        }
    }

    private final class ListenerRegistration implements Registration {

        private final Listener listener;
        private final AtomicBoolean closed;

        private ListenerRegistration(Listener listener) {
            this.listener = listener;
            this.closed = new AtomicBoolean();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            listeners.remove(this);
        }
    }
}
