package com.fongmi.android.tv.player;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local generation and listener hub for invalidating internal experiment work. */
public final class PlaybackExperimentCoordinator {

    private static final PlaybackExperimentCoordinator INSTANCE =
            new PlaybackExperimentCoordinator();

    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong(1);

    public static PlaybackExperimentCoordinator process() {
        return INSTANCE;
    }

    public long generation() {
        return generation.get();
    }

    public Token capture(PlaybackExperimentPolicy.Action action) {
        return new Token(generation(), action);
    }

    public boolean isCurrent(Token token) {
        return token != null
                && token.action() != null
                && (token.action().risk()
                        != PlaybackExperimentPolicy.Risk.INTERNAL_EXPERIMENT
                        || token.generation() == generation());
    }

    public Update invalidate(Change change) {
        Update update = new Update(
                generation.updateAndGet(value -> value == Long.MAX_VALUE ? 1 : value + 1),
                change == null ? Change.POLICY_CHANGED : change);
        for (Listener listener : listeners) {
            try {
                listener.onExperimentPolicyChanged(update);
            } catch (Throwable ignored) {
            }
        }
        return update;
    }

    public Registration addListener(Listener listener) {
        if (listener == null) return Registration.NONE;
        listeners.add(listener);
        return new Registration(this, listener);
    }

    private void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public enum Change {
        POLICY_CHANGED,
        ROLLBACK
    }

    public interface Listener {
        void onExperimentPolicyChanged(Update update);
    }

    public record Token(
            long generation,
            PlaybackExperimentPolicy.Action action) {
    }

    public record Update(long generation, Change change) {

        public Update {
            generation = Math.max(1, generation);
            change = change == null ? Change.POLICY_CHANGED : change;
        }
    }

    public static final class Registration implements AutoCloseable {

        private static final Registration NONE = new Registration(null, null);

        private final PlaybackExperimentCoordinator owner;
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(
                PlaybackExperimentCoordinator owner,
                Listener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)
                    || owner == null || listener == null) return;
            owner.removeListener(listener);
        }
    }
}
