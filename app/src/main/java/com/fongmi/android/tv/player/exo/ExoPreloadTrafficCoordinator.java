package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackTrace;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-level marker for preload traffic that competes with foreground EXO transfers. */
final class ExoPreloadTrafficCoordinator {

    private static final ExoPreloadTrafficCoordinator PROCESS =
            new ExoPreloadTrafficCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final Map<Long, Entry> active = new HashMap<>();
    private long nextId;

    ExoPreloadTrafficCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
    }

    static ExoPreloadTrafficCoordinator process() {
        return PROCESS;
    }

    Registration acquire(String traceId, Source source) {
        String normalized = PlaybackTrace.normalize(traceId);
        PlaybackAutoContext context = store.snapshot();
        if (!context.active() || !context.session().traceId().equals(normalized)) {
            return Registration.none();
        }
        return acquire(context.session(), source);
    }

    synchronized Registration acquire(
            PlaybackAutoContext.SessionToken session,
            Source source) {
        pruneLocked();
        if (!isCurrentExoSession(session)) return Registration.none();
        long id = nextId == Long.MAX_VALUE ? 1 : ++nextId;
        while (active.containsKey(id)) id = id == Long.MAX_VALUE ? 1 : id + 1;
        nextId = id;
        active.put(id, new Entry(session, source == null ? Source.CUSTOM : source));
        return new Registration(this, id, session);
    }

    synchronized boolean isActive(PlaybackAutoContext.SessionToken session) {
        pruneLocked();
        if (!isCurrentExoSession(session)) return false;
        for (Entry entry : active.values()) {
            if (session.equals(entry.session())) return true;
        }
        return false;
    }

    synchronized Snapshot snapshot(PlaybackAutoContext.SessionToken session) {
        pruneLocked();
        int custom = 0;
        int media3 = 0;
        if (isCurrentExoSession(session)) {
            for (Entry entry : active.values()) {
                if (!session.equals(entry.session())) continue;
                if (entry.source() == Source.MEDIA3) media3++;
                else custom++;
            }
        }
        return new Snapshot(custom, media3);
    }

    private synchronized void release(
            long id,
            PlaybackAutoContext.SessionToken session) {
        Entry entry = active.get(id);
        if (entry != null && entry.session().equals(session)) active.remove(id);
        pruneLocked();
    }

    private void pruneLocked() {
        PlaybackAutoContext.SessionToken current = currentExoSession();
        active.entrySet().removeIf(entry -> !entry.getValue().session().equals(current));
    }

    private boolean isCurrentExoSession(PlaybackAutoContext.SessionToken session) {
        return session != null && session.active() && session.equals(currentExoSession());
    }

    private PlaybackAutoContext.SessionToken currentExoSession() {
        PlaybackAutoContext context = store.snapshot();
        if (!context.active()) return PlaybackAutoContext.SessionToken.none();
        if (!context.kernel().hasValue()
                || context.kernel().value() != PlaybackAutoContext.Kernel.EXO) {
            return PlaybackAutoContext.SessionToken.none();
        }
        return context.session();
    }

    enum Source {
        CUSTOM,
        MEDIA3
    }

    record Snapshot(int customCount, int media3Count) {

        int totalCount() {
            return customCount + media3Count;
        }
    }

    private record Entry(PlaybackAutoContext.SessionToken session, Source source) {
    }

    static final class Registration implements AutoCloseable {

        private static final Registration NONE = new Registration(
                null, 0, PlaybackAutoContext.SessionToken.none());

        private final ExoPreloadTrafficCoordinator owner;
        private final long id;
        private final PlaybackAutoContext.SessionToken session;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(
                ExoPreloadTrafficCoordinator owner,
                long id,
                PlaybackAutoContext.SessionToken session) {
            this.owner = owner;
            this.id = id;
            this.session = session;
        }

        static Registration none() {
            return NONE;
        }

        boolean active() {
            return owner != null && id > 0 && !closed.get();
        }

        PlaybackAutoContext.SessionToken session() {
            return session;
        }

        @Override
        public void close() {
            if (owner == null || !closed.compareAndSet(false, true)) return;
            owner.release(id, session);
        }
    }
}
