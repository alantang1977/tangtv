package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Session-safe automatic LoadControl baseline plus its runtime-effective target bytes. */
final class ExoTargetBufferCoordinator {

    private static final ExoTargetBufferCoordinator PROCESS =
            new ExoTargetBufferCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<State> state;

    ExoTargetBufferCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
        this.state = new AtomicReference<>(State.empty());
    }

    static ExoTargetBufferCoordinator process() {
        return PROCESS;
    }

    boolean publish(
            PlaybackAutoContext.SessionToken session,
            ExoTargetBufferPolicy.Decision decision,
            long publishedAtElapsedMs) {
        if (session == null || !session.active() || decision == null) return false;
        if (!isCurrentExoSession(session)) return false;
        while (true) {
            State current = state.get();
            boolean runtimeLimited = session.equals(current.session())
                    && current.decision() != null
                    && current.runtimeLimited();
            int effective = runtimeLimited
                    ? Math.min(decision.targetBytes(), current.effectiveTargetBytes())
                    : decision.targetBytes();
            long publishedAt = session.equals(current.session())
                    ? Math.max(current.publishedAtElapsedMs(), publishedAtElapsedMs)
                    : Math.max(0, publishedAtElapsedMs);
            State next = new State(
                    session,
                    decision,
                    effective,
                    runtimeLimited,
                    publishedAt);
            if (!state.compareAndSet(current, next)) continue;
            if (isCurrentExoSession(session)) return true;
            state.compareAndSet(next, State.empty());
            return false;
        }
    }

    boolean publishEffectiveTarget(
            PlaybackAutoContext.SessionToken session,
            int effectiveTargetBytes,
            boolean runtimeLimited,
            long publishedAtElapsedMs) {
        if (session == null || !session.active() || !isCurrentExoSession(session)) return false;
        while (true) {
            State current = state.get();
            if (!session.equals(current.session()) || current.decision() == null) return false;
            int effective = Math.min(
                    current.decision().targetBytes(),
                    ExoTargetBufferPolicy.tierForCapacity(
                            Math.max(ExoTargetBufferPolicy.MIN_TARGET_BYTES, effectiveTargetBytes)));
            State next = new State(
                    session,
                    current.decision(),
                    effective,
                    runtimeLimited,
                    Math.max(current.publishedAtElapsedMs(), publishedAtElapsedMs));
            if (!state.compareAndSet(current, next)) continue;
            if (isCurrentExoSession(session)) return true;
            state.compareAndSet(next, State.empty());
            return false;
        }
    }

    ExoTargetBufferPolicy.Decision currentDecision() {
        PlaybackAutoContext.SessionToken session = store.snapshot().session();
        return currentDecision(session);
    }

    ExoTargetBufferPolicy.Decision currentDecision(PlaybackAutoContext.SessionToken session) {
        State current = state.get();
        if (session == null || !session.active() || !session.equals(current.session())) return null;
        if (!isCurrentExoSession(session)) return null;
        return current.decision();
    }

    int currentTargetBytesOr(int fallbackBytes) {
        PlaybackAutoContext.SessionToken session = store.snapshot().session();
        State current = state.get();
        if (session == null || !session.active()
                || !session.equals(current.session())
                || !isCurrentExoSession(session)) {
            return fallbackBytes;
        }
        return current.effectiveTargetBytes();
    }

    private boolean isCurrentExoSession(PlaybackAutoContext.SessionToken session) {
        PlaybackAutoContext context = store.snapshot();
        return session.equals(context.session())
                && (!context.kernel().hasValue()
                || context.kernel().value() == PlaybackAutoContext.Kernel.EXO);
    }

    private record State(
            PlaybackAutoContext.SessionToken session,
            ExoTargetBufferPolicy.Decision decision,
            int effectiveTargetBytes,
            boolean runtimeLimited,
            long publishedAtElapsedMs) {

        private static State empty() {
            return new State(
                    PlaybackAutoContext.SessionToken.none(),
                    null,
                    0,
                    false,
                    -1);
        }
    }
}
