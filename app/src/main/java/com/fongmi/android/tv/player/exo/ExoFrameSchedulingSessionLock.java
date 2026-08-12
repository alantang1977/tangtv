package com.fongmi.android.tv.player.exo;

/** Keeps renderer parameters and per-playback experiment identity consistent. */
public final class ExoFrameSchedulingSessionLock {

    private ExoFrameSchedulingExperimentPolicy.Decision rendererDecision;
    private ExoFrameSchedulingExperimentPolicy.Decision sessionDecision;

    public ExoFrameSchedulingSessionLock(
            ExoFrameSchedulingExperimentPolicy.Decision initialDecision) {
        rendererDecision = safe(initialDecision);
        sessionDecision = rendererDecision;
    }

    public synchronized boolean requiresRendererRebuild(
            ExoFrameSchedulingExperimentPolicy.Decision desired) {
        return !rendererDecision.sameRendererSettings(safe(desired));
    }

    public synchronized boolean lockForNextPlayback(
            ExoFrameSchedulingExperimentPolicy.Decision desired) {
        ExoFrameSchedulingExperimentPolicy.Decision safeDesired = safe(desired);
        if (!rendererDecision.sameRendererSettings(safeDesired)) return false;
        sessionDecision = safeDesired;
        return true;
    }

    public synchronized void onRendererRebuilt(
            ExoFrameSchedulingExperimentPolicy.Decision decision) {
        rendererDecision = safe(decision);
        sessionDecision = rendererDecision;
    }

    public synchronized ExoFrameSchedulingExperimentPolicy.Decision
    sessionDecision() {
        return sessionDecision;
    }

    public synchronized ExoFrameSchedulingExperimentPolicy.Decision
    rendererDecision() {
        return rendererDecision;
    }

    private static ExoFrameSchedulingExperimentPolicy.Decision safe(
            ExoFrameSchedulingExperimentPolicy.Decision decision) {
        return decision == null
                ? ExoFrameSchedulingExperimentPolicy.stableDecision(
                false, false, false)
                : decision;
    }
}
