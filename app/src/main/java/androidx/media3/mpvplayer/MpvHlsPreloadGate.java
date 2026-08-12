package androidx.media3.mpvplayer;

import java.io.IOException;

/** Generation gate that prevents cancelled MPV HLS preload work from resuming after recovery. */
final class MpvHlsPreloadGate {

    private volatile boolean allowed = true;
    private volatile long generation;
    private volatile int foregroundRequests;
    private volatile boolean foregroundBlocking = true;

    synchronized Transition update(boolean allow) {
        if (allowed == allow) return Transition.UNCHANGED;
        allowed = allow;
        if (!allow && generation < Long.MAX_VALUE) generation++;
        return allow ? Transition.ALLOWED : Transition.BLOCKED;
    }

    synchronized long invalidate() {
        if (generation < Long.MAX_VALUE) generation++;
        return generation;
    }

    synchronized boolean foregroundStarted() {
        if (foregroundRequests < Integer.MAX_VALUE) foregroundRequests++;
        if (!foregroundBlocking) return false;
        if (foregroundRequests != 1) return false;
        invalidate();
        return true;
    }

    synchronized void foregroundEnded() {
        if (foregroundRequests > 0) foregroundRequests--;
    }

    synchronized boolean setForegroundBlocking(boolean blocking) {
        if (foregroundBlocking == blocking) return false;
        foregroundBlocking = blocking;
        if (blocking) invalidate();
        return true;
    }

    long acquire() {
        long current = generation;
        return allowed && (!foregroundBlocking || foregroundRequests == 0)
                ? current : -1;
    }

    boolean allows(long expectedGeneration) {
        return expectedGeneration >= 0
                && allowed
                && (!foregroundBlocking || foregroundRequests == 0)
                && expectedGeneration == generation;
    }

    synchronized boolean commitIfAllowed(
            long expectedGeneration,
            CommitAction action) throws IOException {
        if (action == null || !allows(expectedGeneration)) return false;
        return action.commit();
    }

    boolean allowed() {
        return allowed;
    }

    int foregroundRequests() {
        return foregroundRequests;
    }

    @FunctionalInterface
    interface CommitAction {
        boolean commit() throws IOException;
    }

    enum Transition {
        UNCHANGED("unchanged"),
        BLOCKED("blocked"),
        ALLOWED("allowed");

        private final String label;

        Transition(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        boolean changed() {
            return this != UNCHANGED;
        }
    }
}
