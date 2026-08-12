package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Session-isolated action state for the MPV automatic controller. */
public final class MpvAutoController {

    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private State state = State.IDLE;
    private MpvAutoControlPolicy.Decision lastDecision =
            MpvAutoControlPolicy.Decision.hold(MpvAutoControlPolicy.Reason.NOT_AUTOMATIC_MPV);
    private int evaluationCount;
    private int applyAttempts;
    private long appliedForwardBytes = -1;
    private long appliedBackBytes = -1;
    private boolean lastApplySucceeded;

    public synchronized void beginSession(PlaybackAutoContext.SessionToken token) {
        resetAll(token);
    }

    public synchronized void endSession(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        resetAll(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized MpvAutoControlPolicy.Decision evaluate(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            MpvAutoControlPolicy.Request request) {
        if (!isCurrent(token) || factsSession == null || !token.equals(factsSession)) {
            return MpvAutoControlPolicy.Decision.hold(MpvAutoControlPolicy.Reason.STALE_SESSION);
        }
        MpvAutoControlPolicy.Decision decision = MpvAutoControlPolicy.resolve(request);
        lastDecision = decision;
        if (evaluationCount < Integer.MAX_VALUE) evaluationCount++;
        state = decision.requestsApply()
                ? State.BASELINE_READY
                : decision.reason() == MpvAutoControlPolicy.Reason.CONFIG_PRIORITY
                ? State.SUPPRESSED : State.INACTIVE;
        return decision;
    }

    /** Commits the action before calling MPV so reentrant callbacks cannot duplicate it. */
    public synchronized boolean beginApply(
            PlaybackAutoContext.SessionToken token,
            MpvAutoControlPolicy.Decision decision) {
        if (!isCurrent(token)
                || decision == null
                || !decision.requestsApply()
                || !decision.equals(lastDecision)
                || state != State.BASELINE_READY) {
            return false;
        }
        state = State.APPLYING;
        if (applyAttempts < Integer.MAX_VALUE) applyAttempts++;
        lastApplySucceeded = false;
        return true;
    }

    public synchronized void completeApply(
            PlaybackAutoContext.SessionToken token,
            MpvAutoControlPolicy.Decision decision,
            boolean succeeded) {
        completeApply(token, decision, succeeded, false);
    }

    public synchronized void completeApply(
            PlaybackAutoContext.SessionToken token,
            MpvAutoControlPolicy.Decision decision,
            boolean succeeded,
            boolean staged) {
        if (!isCurrent(token)
                || decision == null
                || !decision.equals(lastDecision)
                || state != State.APPLYING) {
            return;
        }
        lastApplySucceeded = succeeded;
        state = succeeded ? staged ? State.STAGED : State.APPLIED : State.FAILED;
        if (succeeded) {
            appliedForwardBytes = decision.forwardBytes();
            appliedBackBytes = decision.backBytes();
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(session, state, lastDecision, evaluationCount, applyAttempts,
                appliedForwardBytes, appliedBackBytes, lastApplySucceeded);
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private void resetAll(PlaybackAutoContext.SessionToken token) {
        session = token == null ? PlaybackAutoContext.SessionToken.none() : token;
        state = State.IDLE;
        lastDecision = MpvAutoControlPolicy.Decision.hold(
                MpvAutoControlPolicy.Reason.NOT_AUTOMATIC_MPV);
        evaluationCount = 0;
        applyAttempts = 0;
        appliedForwardBytes = -1;
        appliedBackBytes = -1;
        lastApplySucceeded = false;
    }

    public enum State {
        IDLE("idle"),
        INACTIVE("inactive"),
        SUPPRESSED("suppressed"),
        BASELINE_READY("baseline-ready"),
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

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            State state,
            MpvAutoControlPolicy.Decision lastDecision,
            int evaluationCount,
            int applyAttempts,
            long appliedForwardBytes,
            long appliedBackBytes,
            boolean lastApplySucceeded) {
    }
}
