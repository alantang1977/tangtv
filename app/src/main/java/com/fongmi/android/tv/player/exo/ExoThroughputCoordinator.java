package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackSystemConditionCoordinator;
import com.fongmi.android.tv.player.PlaybackThroughputHistory;

import java.util.Objects;

/** Session-safe owner of the automatic EXO throughput estimator. */
final class ExoThroughputCoordinator implements AutoCloseable {

    private static final ExoThroughputCoordinator PROCESS =
            new ExoThroughputCoordinator(
                    PlaybackAutoContextStore.process(),
                    new ExoThroughputEstimator(),
                    PlaybackSystemConditionCoordinator.process(),
                    PlaybackThroughputHistory.process());

    private final PlaybackAutoContextStore store;
    private final ExoThroughputEstimator estimator;
    private final PlaybackThroughputHistory throughputHistory;
    private final PlaybackSystemConditionCoordinator.Registration systemConditionRegistration;
    private long networkGeneration;

    ExoThroughputCoordinator(PlaybackAutoContextStore store) {
        this(store, new ExoThroughputEstimator(), null,
                new PlaybackThroughputHistory());
    }

    ExoThroughputCoordinator(
            PlaybackAutoContextStore store,
            ExoThroughputEstimator estimator) {
        this(store, estimator, null, new PlaybackThroughputHistory());
    }

    ExoThroughputCoordinator(
            PlaybackAutoContextStore store,
            ExoThroughputEstimator estimator,
            PlaybackSystemConditionCoordinator systemConditionCoordinator) {
        this(store, estimator, systemConditionCoordinator,
                new PlaybackThroughputHistory());
    }

    ExoThroughputCoordinator(
            PlaybackAutoContextStore store,
            ExoThroughputEstimator estimator,
            PlaybackSystemConditionCoordinator systemConditionCoordinator,
            PlaybackThroughputHistory throughputHistory) {
        this.store = Objects.requireNonNull(store);
        this.estimator = Objects.requireNonNull(estimator);
        this.throughputHistory = Objects.requireNonNull(throughputHistory);
        this.systemConditionRegistration = systemConditionCoordinator == null
                ? null : systemConditionCoordinator.addListener(this::onSystemConditionUpdate);
    }

    static ExoThroughputCoordinator process() {
        return PROCESS;
    }

    synchronized PlaybackAutoContext.SessionToken currentSession() {
        return currentExoSession(store.snapshot());
    }

    synchronized ExoThroughputEstimator.Snapshot synchronize(
            long rawEstimateBitsPerSecond,
            long nowElapsedMs) {
        PlaybackAutoContext.SessionToken current = currentSession();
        ExoThroughputEstimator.Snapshot snapshot = estimator.snapshot();
        if (!current.active()) return snapshot;
        if (!current.equals(snapshot.session())
                || snapshot.effectiveEstimateBitsPerSecond() <= 0
                && rawEstimateBitsPerSecond > 0) {
            return estimator.reset(current, nowElapsedMs, rawEstimateBitsPerSecond,
                    ExoThroughputEstimator.Reason.SESSION_RESET);
        }
        return snapshot;
    }

    synchronized ExoThroughputEstimator.Snapshot resetForNetworkChange(
            long rawEstimateBitsPerSecond,
            long nowElapsedMs) {
        PlaybackAutoContext.SessionToken current = currentSession();
        if (!current.active()) return estimator.snapshot();
        ExoThroughputEstimator.Snapshot snapshot = estimator.snapshot();
        long safePrior = current.equals(snapshot.session())
                ? safeNetworkPrior(snapshot, rawEstimateBitsPerSecond)
                : Math.max(0, rawEstimateBitsPerSecond);
        networkGeneration = nextGeneration(networkGeneration);
        return estimator.reset(
                current,
                nowElapsedMs,
                rawEstimateBitsPerSecond,
                safePrior,
                ExoThroughputEstimator.Reason.NETWORK_RESET);
    }

    synchronized long networkGeneration() {
        return networkGeneration;
    }

    synchronized ExoThroughputEstimator.Update observe(
            PlaybackAutoContext.SessionToken session,
            long nowElapsedMs,
            long bytesTransferred,
            int elapsedMs,
            long rawEstimateBitsPerSecond,
            boolean preloadContended,
            boolean fullNetworkSpeed) {
        return observe(
                session,
                nowElapsedMs,
                bytesTransferred,
                elapsedMs,
                rawEstimateBitsPerSecond,
                preloadContended,
                fullNetworkSpeed,
                networkGeneration);
    }

    synchronized ExoThroughputEstimator.Update observe(
            PlaybackAutoContext.SessionToken session,
            long nowElapsedMs,
            long bytesTransferred,
            int elapsedMs,
            long rawEstimateBitsPerSecond,
            boolean preloadContended,
            boolean fullNetworkSpeed,
            long sampleNetworkGeneration) {
        PlaybackAutoContext context = store.snapshot();
        PlaybackAutoContext.SessionToken current = currentExoSession(context);
        ExoThroughputEstimator.Snapshot before = estimator.snapshot();
        if (!current.active() || session == null || !session.equals(current)) {
            return new ExoThroughputEstimator.Update(
                    before, before, false, ExoThroughputEstimator.Reason.STALE_SESSION);
        }
        if (sampleNetworkGeneration != networkGeneration) {
            return new ExoThroughputEstimator.Update(
                    before,
                    before,
                    false,
                    ExoThroughputEstimator.Reason.NETWORK_CHANGED_DURING_SAMPLE);
        }
        if (!current.equals(before.session())) {
            estimator.reset(current, nowElapsedMs, rawEstimateBitsPerSecond,
                    ExoThroughputEstimator.Reason.SESSION_RESET);
        }
        ExoThroughputPathPolicy.Decision path = ExoThroughputPathPolicy.resolve(
                context, nowElapsedMs, preloadContended);
        ExoThroughputEstimator.Update update = estimator.observe(
                current,
                nowElapsedMs,
                bytesTransferred,
                elapsedMs,
                rawEstimateBitsPerSecond,
                path,
                preloadContended,
                fullNetworkSpeed);
        ExoThroughputEstimator.Snapshot snapshot = update.snapshot();
        if (update.accepted()) {
            throughputHistory.record(
                    context,
                    new PlaybackThroughputHistory.Evidence(
                            snapshot.effectiveEstimateBitsPerSecond(),
                            snapshot.shortEstimateBitsPerSecond(),
                            snapshot.longEstimateBitsPerSecond(),
                            snapshot.longSampleCount(),
                            snapshot.longWindowMs(),
                            snapshot.predictionErrorPermille(),
                            snapshot.confidence(),
                            snapshot.pathTrust() == ExoThroughputPathPolicy.Trust.TRUSTED,
                            snapshot.preloadContended()),
                    nowElapsedMs);
        }
        return update;
    }

    synchronized ExoThroughputEstimator.Snapshot snapshot() {
        PlaybackAutoContext.SessionToken current = currentSession();
        ExoThroughputEstimator.Snapshot snapshot = estimator.snapshot();
        if (!current.active() || !current.equals(snapshot.session())) {
            return ExoThroughputEstimator.Snapshot.empty();
        }
        return snapshot;
    }

    @Override
    public void close() {
        if (systemConditionRegistration != null) systemConditionRegistration.close();
    }

    private synchronized void onSystemConditionUpdate(
            PlaybackSystemConditionCoordinator.Update update) {
        if (update == null
                || update.trigger() != PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK) {
            return;
        }
        PlaybackAutoContext.SessionToken current = currentSession();
        ExoThroughputEstimator.Snapshot snapshot = estimator.snapshot();
        if (!current.active() || !current.equals(update.session())) return;
        throughputHistory.invalidate();
        networkGeneration = nextGeneration(networkGeneration);
        if (!current.equals(snapshot.session())) return;
        long safePrior = safeNetworkPrior(
                snapshot, snapshot.rawEstimateBitsPerSecond());
        estimator.reset(
                current,
                update.publishedAtElapsedMs(),
                snapshot.rawEstimateBitsPerSecond(),
                safePrior,
                ExoThroughputEstimator.Reason.NETWORK_RESET);
    }

    private static PlaybackAutoContext.SessionToken currentExoSession(
            PlaybackAutoContext context) {
        if (context == null || !context.active()) {
            return PlaybackAutoContext.SessionToken.none();
        }
        if (!context.kernel().hasValue()
                || context.kernel().value() != PlaybackAutoContext.Kernel.EXO) {
            return PlaybackAutoContext.SessionToken.none();
        }
        return context.session();
    }

    private static long safeNetworkPrior(
            ExoThroughputEstimator.Snapshot snapshot,
            long candidateRawBitsPerSecond) {
        long effective = snapshot.effectiveEstimateBitsPerSecond();
        long raw = Math.max(0, candidateRawBitsPerSecond);
        if (effective <= 0) return raw;
        return raw > 0 ? Math.min(effective, raw) : effective;
    }

    private static long nextGeneration(long generation) {
        return generation == Long.MAX_VALUE ? 1 : generation + 1;
    }
}
