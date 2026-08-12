package com.fongmi.android.tv.player.exo;

final class CacheCapacityState {

    private boolean created;
    private boolean pending;
    private long actualCapacityBytes;
    private long pendingCapacityBytes;
    private int activeSessions;

    synchronized void recordCreated(long capacityBytes) {
        created = true;
        pending = false;
        actualCapacityBytes = Math.max(0, capacityBytes);
        pendingCapacityBytes = 0;
    }

    synchronized long report(long desiredCapacityBytes) {
        long desired = Math.max(0, desiredCapacityBytes);
        if (!created) return desired;
        pending = desired != actualCapacityBytes;
        pendingCapacityBytes = pending ? desired : 0;
        return actualCapacityBytes;
    }

    synchronized boolean hasPending() {
        return created && pending;
    }

    synchronized long pendingCapacityBytes() {
        return pendingCapacityBytes;
    }

    synchronized long actualCapacityBytes() {
        return actualCapacityBytes;
    }

    synchronized void acquireSession() {
        activeSessions++;
    }

    synchronized void releaseSession() {
        activeSessions = Math.max(0, activeSessions - 1);
    }

    synchronized boolean canReleasePending() {
        return activeSessions == 0 && hasPending();
    }

    synchronized int activeSessions() {
        return activeSessions;
    }

    synchronized void recordReleased() {
        created = false;
        pending = false;
        actualCapacityBytes = 0;
        pendingCapacityBytes = 0;
    }
}
