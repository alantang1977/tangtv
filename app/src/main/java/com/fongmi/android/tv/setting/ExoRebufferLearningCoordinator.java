package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.PlaybackNetworkIdentityPolicy;
import com.fongmi.android.tv.player.PlaybackTrace;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps persistent learning bound to the playback trace that produced it. */
final class ExoRebufferLearningCoordinator {

    private static final int MAX_PENDING_SESSIONS = 4;

    private final ExoRebufferLearningStore store;
    private final Map<String, Session> sessions;
    private String activeTraceId;
    private int activeRebufferMs;

    ExoRebufferLearningCoordinator(ExoRebufferLearningStore store) {
        this.store = store;
        this.sessions = new LinkedHashMap<>();
        this.activeTraceId = PlaybackTrace.NONE;
        this.activeRebufferMs = AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
    }

    synchronized BeginResult begin(
            String rawTraceId,
            ExoRebufferLearningKey.Key key,
            long nowEpochMs) {
        String traceId = PlaybackTrace.normalize(rawTraceId);
        if (PlaybackTrace.NONE.equals(traceId)) {
            activeTraceId = PlaybackTrace.NONE;
            activeRebufferMs = AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
            return new BeginResult(
                    Action.DEFAULT,
                    activeRebufferMs,
                    0,
                    null);
        }
        ExoRebufferLearningStore.Lookup lookup = store.lookup(key, nowEpochMs);
        int rebufferMs = lookup.hit()
                ? lookup.rebufferMs() : AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
        Session session = new Session(key, false, rebufferMs);
        sessions.remove(traceId);
        sessions.put(traceId, session);
        trimPendingSessions();
        activeTraceId = traceId;
        activeRebufferMs = rebufferMs;
        return new BeginResult(
                lookup.hit() ? Action.HIT : Action.DEFAULT,
                rebufferMs,
                lookup.sampleCount(),
                key);
    }

    synchronized BindResult bind(
            String rawTraceId,
            ExoRebufferLearningKey.Key candidate,
            long nowEpochMs) {
        String traceId = PlaybackTrace.normalize(rawTraceId);
        Session session = sessions.get(traceId);
        if (session == null || candidate == null || !candidate.valid()) {
            return new BindResult(Action.SKIP_UNKNOWN, null, activeRebufferMs, 0);
        }
        if (session.unstable) {
            return new BindResult(
                    Action.SKIP_UNSTABLE,
                    session.key,
                    session.rebufferMs,
                    0);
        }
        if (session.key == null) {
            session.key = candidate;
            ExoRebufferLearningStore.Lookup lookup = store.lookup(
                    candidate, nowEpochMs);
            if (lookup.hit() && !session.runtimeUpdated) {
                session.rebufferMs = lookup.rebufferMs();
                if (traceId.equals(activeTraceId)) {
                    activeRebufferMs = lookup.rebufferMs();
                }
                return new BindResult(
                        Action.LATE_HIT,
                        candidate,
                        lookup.rebufferMs(),
                        lookup.sampleCount());
            }
            return new BindResult(
                    Action.LATE_BOUND,
                    candidate,
                    session.rebufferMs,
                    0);
        }
        if (!session.key.equals(candidate)) {
            session.unstable = true;
            return new BindResult(
                    Action.SKIP_UNSTABLE,
                    session.key,
                    session.rebufferMs,
                    0);
        }
        return new BindResult(
                Action.HOLD,
                session.key,
                session.rebufferMs,
                0);
    }

    synchronized UpdateResult update(
            String rawTraceId,
            int rebufferCount,
            long rebufferTotalMs,
            long positionMs,
            long mediaBitrate,
            long bandwidthEstimate) {
        String traceId = PlaybackTrace.normalize(rawTraceId);
        Session session = sessions.get(traceId);
        if (session == null || !traceId.equals(activeTraceId)) {
            return new UpdateResult(Action.SKIP_SESSION, activeRebufferMs, false);
        }
        AutoRebufferPolicy.Result result = AutoRebufferPolicy.resolve(
                session.rebufferMs,
                0,
                rebufferCount,
                rebufferTotalMs,
                positionMs,
                mediaBitrate,
                bandwidthEstimate);
        int updated = Math.max(session.rebufferMs, result.rebufferMs());
        boolean changed = updated != session.rebufferMs;
        session.runtimeUpdated = true;
        session.rebufferMs = updated;
        activeRebufferMs = updated;
        return new UpdateResult(
                changed ? Action.RAISED : Action.HOLD,
                updated,
                changed);
    }

    synchronized FinishResult finish(
            String rawTraceId,
            String currentNetworkDigest,
            int rebufferCount,
            long rebufferTotalMs,
            long positionMs,
            long mediaBitrate,
            long bandwidthEstimate,
            long nowEpochMs) {
        String traceId = PlaybackTrace.normalize(rawTraceId);
        Session session = sessions.remove(traceId);
        if (session == null) {
            return new FinishResult(Action.SKIP_SESSION, null, 0, 0);
        }
        if (traceId.equals(activeTraceId)) {
            activeTraceId = PlaybackTrace.NONE;
            activeRebufferMs = AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
        }
        if (session.key == null) {
            return new FinishResult(Action.SKIP_UNKNOWN, null, 0, 0);
        }
        if (session.unstable) {
            return new FinishResult(Action.SKIP_UNSTABLE, session.key, 0, 0);
        }
        if (!PlaybackNetworkIdentityPolicy.isValidDigest(currentNetworkDigest)
                || !session.key.networkDigest().equals(currentNetworkDigest)) {
            return new FinishResult(Action.SKIP_NETWORK_CHANGED, session.key, 0, 0);
        }
        ExoRebufferLearningStore.RecordResult recorded = store.record(
                session.key,
                rebufferCount,
                rebufferTotalMs,
                positionMs,
                mediaBitrate,
                bandwidthEstimate,
                nowEpochMs);
        if (!recorded.recorded() || recorded.entry() == null) {
            return new FinishResult(Action.SKIP_STORE, session.key, 0, 0);
        }
        return new FinishResult(
                Action.RECORDED,
                session.key,
                recorded.entry().rebufferMs(),
                recorded.entry().sampleCount());
    }

    synchronized void discard(String rawTraceId) {
        String traceId = PlaybackTrace.normalize(rawTraceId);
        sessions.remove(traceId);
        if (traceId.equals(activeTraceId)) {
            activeTraceId = PlaybackTrace.NONE;
            activeRebufferMs = AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
        }
    }

    synchronized boolean needsBinding(String rawTraceId) {
        Session session = sessions.get(PlaybackTrace.normalize(rawTraceId));
        return session != null && session.key == null && !session.unstable;
    }

    synchronized boolean hasSession(String rawTraceId) {
        return sessions.containsKey(PlaybackTrace.normalize(rawTraceId));
    }

    synchronized int currentRebufferMs() {
        return AutoRebufferPolicy.normalize(activeRebufferMs);
    }

    synchronized void clear() {
        sessions.clear();
        activeTraceId = PlaybackTrace.NONE;
        activeRebufferMs = AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
        store.clear();
    }

    private void trimPendingSessions() {
        while (sessions.size() > MAX_PENDING_SESSIONS) {
            Iterator<String> iterator = sessions.keySet().iterator();
            if (!iterator.hasNext()) return;
            String oldest = iterator.next();
            iterator.remove();
            if (oldest.equals(activeTraceId)) {
                activeTraceId = PlaybackTrace.NONE;
                activeRebufferMs = AutoRebufferPolicy.DEFAULT_REBUFFER_MS;
            }
        }
    }

    enum Action {
        DEFAULT("default"),
        HIT("hit"),
        LATE_BOUND("late-bound"),
        LATE_HIT("late-hit"),
        HOLD("hold"),
        RAISED("raised"),
        RECORDED("recorded"),
        SKIP_UNKNOWN("skip-unknown"),
        SKIP_UNSTABLE("skip-unstable"),
        SKIP_NETWORK_CHANGED("skip-network-changed"),
        SKIP_SESSION("skip-session"),
        SKIP_STORE("skip-store");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record BeginResult(
            Action action,
            int rebufferMs,
            int sampleCount,
            ExoRebufferLearningKey.Key key) {
    }

    record BindResult(
            Action action,
            ExoRebufferLearningKey.Key key,
            int rebufferMs,
            int sampleCount) {
    }

    record UpdateResult(
            Action action,
            int rebufferMs,
            boolean changed) {
    }

    record FinishResult(
            Action action,
            ExoRebufferLearningKey.Key key,
            int rebufferMs,
            int sampleCount) {
    }

    private static final class Session {

        private ExoRebufferLearningKey.Key key;
        private boolean unstable;
        private boolean runtimeUpdated;
        private int rebufferMs;

        private Session(
                ExoRebufferLearningKey.Key key,
                boolean unstable,
                int rebufferMs) {
            this.key = key;
            this.unstable = unstable;
            this.runtimeUpdated = false;
            this.rebufferMs = AutoRebufferPolicy.normalize(rebufferMs);
        }
    }
}
