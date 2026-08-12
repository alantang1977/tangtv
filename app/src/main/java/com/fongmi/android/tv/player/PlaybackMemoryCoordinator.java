package com.fongmi.android.tv.player;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Session-safe process coordinator for memory facts and pressure event listeners. */
public final class PlaybackMemoryCoordinator {

    private static final PlaybackMemoryCoordinator PROCESS = new PlaybackMemoryCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<PlaybackAutoContext.SessionToken> activeSession;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners;

    public PlaybackMemoryCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
        this.activeSession = new AtomicReference<>(PlaybackAutoContext.SessionToken.none());
        this.listeners = new CopyOnWriteArrayList<>();
    }

    public static PlaybackMemoryCoordinator process() {
        return PROCESS;
    }

    public boolean beginSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active() || !session.equals(store.snapshot().session())) return false;
        activeSession.set(session);
        return true;
    }

    public boolean endSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active()) return false;
        return activeSession.compareAndSet(session, PlaybackAutoContext.SessionToken.none());
    }

    public PlaybackAutoContext.SessionToken activeSession() {
        return activeSession.get();
    }

    public boolean publish(PlaybackAutoContext.SessionToken session,
                           PlaybackAutoContext.MemorySnapshot snapshot,
                           Long diagnosticPssBytes,
                           int sdkInt,
                           long publishedAtElapsedMs) {
        if (session == null || !session.equals(activeSession.get())) return false;
        long publishedAt = Math.max(0, publishedAtElapsedMs);
        PlaybackMemoryPolicy.Result result = PlaybackMemoryPolicy.evaluate(snapshot, sdkInt);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact = result.known()
                ? PlaybackAutoContext.Fact.withTtl(result.pressure(), result.source(), result.confidence(),
                publishedAt, PlaybackMemoryPolicy.PRESSURE_TTL_MS)
                : null;
        PlaybackAutoContext.Confidence snapshotConfidence = PlaybackMemoryPolicy.snapshotConfidence(snapshot);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact = snapshot != null
                && snapshot.hasEvidence() && snapshotConfidence != PlaybackAutoContext.Confidence.UNKNOWN
                ? PlaybackAutoContext.Fact.withTtl(snapshot, snapshotSource(snapshot),
                snapshotConfidence, publishedAt, PlaybackMemoryPolicy.SNAPSHOT_TTL_MS)
                : null;
        PlaybackAutoContext.Fact<Long> pssFact = validBytes(diagnosticPssBytes)
                ? PlaybackAutoContext.Fact.withTtl(diagnosticPssBytes, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.LOW, publishedAt, PlaybackMemoryPolicy.PSS_TTL_MS)
                : null;
        if (pressureFact == null && snapshotFact == null && pssFact == null) return false;
        if (!store.publishMemoryFacts(session, pressureFact, snapshotFact, pssFact, publishedAt)) return false;
        if (!session.equals(activeSession.get())) return false;
        PlaybackAutoContext current = store.snapshot();
        if (!session.equals(current.session())) return false;
        Update update = new Update(session,
                current.device().memoryPressure(),
                current.device().memorySnapshot(),
                current.device().diagnosticPssBytes(),
                result.reason(),
                publishedAt);
        notifyListeners(update);
        return true;
    }

    public Registration addListener(Listener listener) {
        ListenerRegistration registration = new ListenerRegistration(Objects.requireNonNull(listener));
        listeners.add(registration);
        return registration;
    }

    int listenerCount() {
        return listeners.size();
    }

    private void notifyListeners(Update update) {
        for (ListenerRegistration registration : listeners) {
            if (registration.closed.get()) continue;
            try {
                registration.listener.onMemoryUpdate(update);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean validBytes(Long value) {
        return value != null && value >= 0;
    }

    private static PlaybackAutoContext.ValueSource snapshotSource(PlaybackAutoContext.MemorySnapshot snapshot) {
        return switch (snapshot.trigger()) {
            case TRIM_MEMORY, LOW_MEMORY -> PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK;
            default -> PlaybackAutoContext.ValueSource.SYSTEM_API;
        };
    }

    public interface Listener {
        void onMemoryUpdate(Update update);
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public record Update(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshot,
            PlaybackAutoContext.Fact<Long> diagnosticPssBytes,
            PlaybackMemoryPolicy.Reason reason,
            long publishedAtElapsedMs) {

        public String logSummary() {
            PlaybackAutoContext.MemorySnapshot value = snapshot == null
                    ? PlaybackAutoContext.MemorySnapshot.unknown() : snapshot.value();
            long pss = diagnosticPssBytes != null && diagnosticPssBytes.hasValue()
                    ? diagnosticPssBytes.value() : -1;
            return "pressure=" + (pressure == null || !pressure.hasValue() ? "unknown" : pressure.value().label())
                    + " reason=" + (reason == null ? PlaybackMemoryPolicy.Reason.UNKNOWN.label() : reason.label())
                    + " trigger=" + value.trigger().label()
                    + " javaUsed=" + safeLong(value.javaHeapUsedBytes())
                    + " javaLimit=" + safeLong(value.javaHeapLimitBytes())
                    + " javaHeadroom=" + safeLong(value.javaHeapHeadroomBytes())
                    + " systemAvail=" + safeLong(value.systemAvailableBytes())
                    + " systemThreshold=" + safeLong(value.systemThresholdBytes())
                    + " systemLow=" + (value.systemLowMemory() == null ? "unknown" : value.systemLowMemory())
                    + " trim=" + (value.lastTrimLevel() == null ? -1 : value.lastTrimLevel())
                    + " importance=" + (value.processImportance() == null ? -1 : value.processImportance())
                    + " nativeHeap=" + safeLong(value.nativeHeapAllocatedBytes())
                    + " pssBytes=" + pss;
        }

        private static long safeLong(Long value) {
            return value == null ? -1 : value;
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
