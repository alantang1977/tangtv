package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Serializes combined forward/back MPV cache target updates for one playback session. */
public final class MpvCacheTargetCoordinator {

    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private State state = State.IDLE;
    private boolean baselineInitialized;
    private long nativeForwardBytes = -1;
    private long nativeBackBytes = -1;
    private int applyAttempts;
    private boolean lastApplySucceeded;
    private Decision lastDecision = Decision.hold(
            Reason.WAITING_FOR_BASELINE, -1, -1, -1, -1);

    public synchronized void beginSession(PlaybackAutoContext.SessionToken token) {
        reset(token);
    }

    public synchronized void endSession(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        reset(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized boolean recordBaseline(
            PlaybackAutoContext.SessionToken token,
            long forwardBytes,
            long backBytes) {
        if (!isCurrent(token) || !validTargets(forwardBytes, backBytes)) return false;
        baselineInitialized = true;
        nativeForwardBytes = forwardBytes;
        nativeBackBytes = backBytes;
        state = State.ACTIVE;
        lastApplySucceeded = true;
        lastDecision = Decision.hold(
                Reason.BASELINE_RECORDED,
                nativeForwardBytes,
                nativeBackBytes,
                nativeForwardBytes,
                nativeBackBytes);
        return true;
    }

    public synchronized void suppress(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        baselineInitialized = false;
        nativeForwardBytes = -1;
        nativeBackBytes = -1;
        state = State.SUPPRESSED;
        lastDecision = Decision.hold(
                Reason.SUPPRESSED, -1, -1, -1, -1);
    }

    public synchronized Decision evaluate(
            PlaybackAutoContext.SessionToken token,
            long forwardBytes,
            long backBytes) {
        if (!isCurrent(token)) {
            return remember(Decision.hold(
                    Reason.STALE_SESSION,
                    nativeForwardBytes,
                    nativeBackBytes,
                    forwardBytes,
                    backBytes));
        }
        if (!baselineInitialized) {
            state = State.WAITING_FOR_BASELINE;
            return remember(Decision.hold(
                    Reason.WAITING_FOR_BASELINE,
                    nativeForwardBytes,
                    nativeBackBytes,
                    forwardBytes,
                    backBytes));
        }
        if (state == State.APPLYING) {
            return remember(Decision.hold(
                    Reason.APPLY_PENDING,
                    nativeForwardBytes,
                    nativeBackBytes,
                    forwardBytes,
                    backBytes));
        }
        if (!validTargets(forwardBytes, backBytes)) {
            state = State.INVALID;
            return remember(Decision.hold(
                    Reason.INVALID_TARGET,
                    nativeForwardBytes,
                    nativeBackBytes,
                    forwardBytes,
                    backBytes));
        }
        if (forwardBytes == nativeForwardBytes && backBytes == nativeBackBytes) {
            state = State.ACTIVE;
            return remember(Decision.hold(
                    Reason.TARGET_STABLE,
                    nativeForwardBytes,
                    nativeBackBytes,
                    forwardBytes,
                    backBytes));
        }
        state = State.APPLY_READY;
        return remember(new Decision(
                Action.APPLY,
                Reason.TARGET_CHANGED,
                nativeForwardBytes,
                nativeBackBytes,
                forwardBytes,
                backBytes));
    }

    public synchronized boolean beginApply(
            PlaybackAutoContext.SessionToken token,
            Decision decision) {
        if (!isCurrent(token)
                || decision == null
                || !decision.requestsApply()
                || !decision.equals(lastDecision)
                || state != State.APPLY_READY) {
            return false;
        }
        state = State.APPLYING;
        if (applyAttempts < Integer.MAX_VALUE) applyAttempts++;
        lastApplySucceeded = false;
        return true;
    }

    public synchronized void completeApply(
            PlaybackAutoContext.SessionToken token,
            Decision decision,
            boolean accepted,
            boolean staged) {
        if (!isCurrent(token)
                || decision == null
                || !decision.equals(lastDecision)
                || state != State.APPLYING) {
            return;
        }
        lastApplySucceeded = accepted;
        if (!accepted) {
            state = State.FAILED;
            return;
        }
        nativeForwardBytes = decision.targetForwardBytes();
        nativeBackBytes = decision.targetBackBytes();
        state = staged ? State.STAGED : State.APPLIED;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                session,
                state,
                baselineInitialized,
                nativeForwardBytes,
                nativeBackBytes,
                applyAttempts,
                lastApplySucceeded,
                lastDecision);
    }

    private static boolean validTargets(long forwardBytes, long backBytes) {
        return forwardBytes == MpvForwardCachePolicy.normalizeTier(forwardBytes)
                && backBytes == MpvBackCachePolicy.normalizeTier(backBytes)
                && backBytes <= forwardBytes;
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private void reset(PlaybackAutoContext.SessionToken token) {
        session = token == null ? PlaybackAutoContext.SessionToken.none() : token;
        state = State.IDLE;
        baselineInitialized = false;
        nativeForwardBytes = -1;
        nativeBackBytes = -1;
        applyAttempts = 0;
        lastApplySucceeded = false;
        lastDecision = Decision.hold(
                Reason.WAITING_FOR_BASELINE, -1, -1, -1, -1);
    }

    public enum Action {
        HOLD("hold"),
        APPLY("apply");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        STALE_SESSION("stale-session"),
        WAITING_FOR_BASELINE("waiting-for-baseline"),
        BASELINE_RECORDED("baseline-recorded"),
        SUPPRESSED("suppressed"),
        APPLY_PENDING("apply-pending"),
        INVALID_TARGET("invalid-target"),
        TARGET_STABLE("target-stable"),
        TARGET_CHANGED("target-changed");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum State {
        IDLE("idle"),
        SUPPRESSED("suppressed"),
        WAITING_FOR_BASELINE("waiting-for-baseline"),
        ACTIVE("active"),
        INVALID("invalid"),
        APPLY_READY("apply-ready"),
        APPLYING("applying"),
        STAGED("staged"),
        APPLIED("applied"),
        FAILED("failed");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Decision(
            Action action,
            Reason reason,
            long oldForwardBytes,
            long oldBackBytes,
            long targetForwardBytes,
            long targetBackBytes) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            reason = reason == null ? Reason.TARGET_STABLE : reason;
        }

        static Decision hold(
                Reason reason,
                long oldForwardBytes,
                long oldBackBytes,
                long targetForwardBytes,
                long targetBackBytes) {
            return new Decision(
                    Action.HOLD,
                    reason,
                    oldForwardBytes,
                    oldBackBytes,
                    targetForwardBytes,
                    targetBackBytes);
        }

        public boolean requestsApply() {
            return action == Action.APPLY;
        }

        public String targetLabel() {
            return "forward-" + targetForwardBytes + "-back-" + targetBackBytes;
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            State state,
            boolean baselineInitialized,
            long nativeForwardBytes,
            long nativeBackBytes,
            int applyAttempts,
            boolean lastApplySucceeded,
            Decision lastDecision) {
    }
}
