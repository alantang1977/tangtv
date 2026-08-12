package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTelemetryCoordinator;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Media3 BandwidthMeter wrapper that supplies a path-aware estimate to native ABR. */
final class ExoPathAwareBandwidthMeter implements BandwidthMeter, TransferListener {

    private final DefaultBandwidthMeter delegate;
    private final EventListener.EventDispatcher eventDispatcher;
    private final ExoThroughputCoordinator throughputCoordinator;
    private final ExoPreloadTrafficCoordinator preloadCoordinator;
    private final LongSupplier elapsedRealtime;
    private final Map<DataSource, TransferState> activeTransfers = new IdentityHashMap<>();

    private int streamCount;
    private long sampleStartMs;
    private long sampleBytes;
    private PlaybackAutoContext.SessionToken sampleSession = PlaybackAutoContext.SessionToken.none();
    private boolean sampleSessionSet;
    private boolean sampleMixedSession;
    private boolean samplePreloadContended;
    private long sampleNetworkGeneration;
    private long lastDelegateRawEstimate;

    ExoPathAwareBandwidthMeter(@Nullable Context context) {
        this(
                new DefaultBandwidthMeter.Builder(context)
                        .setSlidingWindowMaxWeight(4_000)
                        .build(),
                ExoThroughputCoordinator.process(),
                ExoPreloadTrafficCoordinator.process(),
                SystemClock::elapsedRealtime);
    }

    ExoPathAwareBandwidthMeter(
            DefaultBandwidthMeter delegate,
            ExoThroughputCoordinator throughputCoordinator,
            ExoPreloadTrafficCoordinator preloadCoordinator,
            LongSupplier elapsedRealtime) {
        this.delegate = delegate;
        this.throughputCoordinator = throughputCoordinator;
        this.preloadCoordinator = preloadCoordinator;
        this.elapsedRealtime = elapsedRealtime;
        this.eventDispatcher = new EventListener.EventDispatcher();
        this.lastDelegateRawEstimate = delegate.getBitrateEstimate();
    }

    @Override
    public synchronized long getBitrateEstimate() {
        return synchronizedEstimate(true);
    }

    @Override
    public long getTimeToFirstByteEstimateUs() {
        return delegate.getTimeToFirstByteEstimateUs();
    }

    @Override
    public TransferListener getTransferListener() {
        return this;
    }

    @Override
    public void addEventListener(Handler eventHandler, EventListener eventListener) {
        eventDispatcher.addListener(eventHandler, eventListener);
    }

    @Override
    public void removeEventListener(EventListener eventListener) {
        eventDispatcher.removeListener(eventListener);
    }

    @Override
    public synchronized void onTransferInitializing(
            DataSource source,
            DataSpec dataSpec,
            boolean isNetwork) {
        delegate.onTransferInitializing(source, dataSpec, isNetwork);
    }

    @Override
    public synchronized void onTransferStart(
            DataSource source,
            DataSpec dataSpec,
            boolean isNetwork) {
        delegate.onTransferStart(source, dataSpec, isNetwork);
        if (!isFullNetworkSpeed(dataSpec, isNetwork)) return;
        if (streamCount == 0) resetSampleBoundary(now());
        streamCount++;
        PlaybackAutoContext.SessionToken session = throughputCoordinator.currentSession();
        activeTransfers.put(source, new TransferState(session));
        if (preloadCoordinator.isActive(session)) samplePreloadContended = true;
    }

    @Override
    public synchronized void onBytesTransferred(
            DataSource source,
            DataSpec dataSpec,
            boolean isNetwork,
            int bytesTransferred) {
        delegate.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred);
        if (!isFullNetworkSpeed(dataSpec, isNetwork) || bytesTransferred <= 0) return;
        TransferState transfer = activeTransfers.get(source);
        if (transfer == null) return;
        recordSession(transfer.session());
        sampleBytes = safeAdd(sampleBytes, bytesTransferred);
        if (preloadCoordinator.isActive(transfer.session())) samplePreloadContended = true;
    }

    @Override
    public synchronized void onTransferEnd(
            DataSource source,
            DataSpec dataSpec,
            boolean isNetwork) {
        delegate.onTransferEnd(source, dataSpec, isNetwork);
        if (!isFullNetworkSpeed(dataSpec, isNetwork)) return;

        ExoThroughputEstimator.Update update = null;
        TransferState transfer = activeTransfers.remove(source);
        long now = now();
        long raw = delegate.getBitrateEstimate();
        lastDelegateRawEstimate = raw;
        if (transfer != null) {
            recordSession(transfer.session());
            if (preloadCoordinator.isActive(transfer.session())) {
                samplePreloadContended = true;
            }
        }

        int elapsedMs = clampElapsed(now - sampleStartMs);
        long bytes = sampleBytes;
        if (elapsedMs > 0) {
            throughputCoordinator.synchronize(raw, now);
            if (!sampleMixedSession && sampleSessionSet && sampleSession.active()) {
                update = throughputCoordinator.observe(
                        sampleSession,
                        now,
                        bytes,
                        elapsedMs,
                        raw,
                        samplePreloadContended,
                        true,
                        sampleNetworkGeneration);
            }
            long effective = effectiveOrRaw(raw);
            eventDispatcher.bandwidthSample(elapsedMs, bytes, effective);
            if (update != null
                    && update.reason()
                    != ExoThroughputEstimator.Reason.NETWORK_CHANGED_DURING_SAMPLE) {
                publishTelemetry(update, elapsedMs, bytes, now);
            }
            resetSampleBoundary(now);
        }
        streamCount = Math.max(0, streamCount - 1);
        if (streamCount == 0) resetSampleBoundary(now);
    }

    DefaultBandwidthMeter rawMeterForTest() {
        return delegate;
    }

    private long synchronizedEstimate(boolean detectNetworkChange) {
        long raw = delegate.getBitrateEstimate();
        PlaybackAutoContext.SessionToken session = throughputCoordinator.currentSession();
        if (!session.active()) {
            lastDelegateRawEstimate = raw;
            return raw;
        }
        long now = now();
        ExoThroughputEstimator.Snapshot before = throughputCoordinator.snapshot();
        ExoThroughputEstimator.Snapshot synchronizedSnapshot =
                throughputCoordinator.synchronize(raw, now);
        boolean sameSessionBefore = session.equals(before.session());
        if (detectNetworkChange && sameSessionBefore
                && raw != lastDelegateRawEstimate) {
            synchronizedSnapshot = throughputCoordinator.resetForNetworkChange(raw, now);
        }
        lastDelegateRawEstimate = raw;
        return synchronizedSnapshot.effectiveEstimateBitsPerSecond() > 0
                ? synchronizedSnapshot.effectiveEstimateBitsPerSecond() : raw;
    }

    private long effectiveOrRaw(long raw) {
        ExoThroughputEstimator.Snapshot snapshot = throughputCoordinator.snapshot();
        return snapshot.effectiveEstimateBitsPerSecond() > 0
                ? snapshot.effectiveEstimateBitsPerSecond() : raw;
    }

    private void recordSession(PlaybackAutoContext.SessionToken session) {
        PlaybackAutoContext.SessionToken safe = session == null
                ? PlaybackAutoContext.SessionToken.none() : session;
        if (!sampleSessionSet) {
            sampleSession = safe;
            sampleSessionSet = true;
        } else if (!sampleSession.equals(safe)) {
            sampleMixedSession = true;
        }
    }

    private void resetSampleBoundary(long now) {
        sampleStartMs = now;
        sampleBytes = 0;
        sampleSession = PlaybackAutoContext.SessionToken.none();
        sampleSessionSet = false;
        sampleMixedSession = false;
        samplePreloadContended = false;
        sampleNetworkGeneration = throughputCoordinator.networkGeneration();
    }

    private void publishTelemetry(
            ExoThroughputEstimator.Update update,
            int elapsedMs,
            long bytes,
            long now) {
        ExoThroughputEstimator.Snapshot previous = update.previous();
        ExoThroughputEstimator.Snapshot current = update.snapshot();
        if (!current.session().active()) return;
        PlaybackTelemetry.DecisionOutcome outcome =
                current.action() == ExoThroughputEstimator.Action.DECREASE
                        || current.action() == ExoThroughputEstimator.Action.INCREASE
                        ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : PlaybackTelemetry.DecisionOutcome.OBSERVED;
        PlaybackTelemetryCoordinator.process().publishDecision(
                current.session(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.THROUGHPUT,
                        outcome,
                        Long.toString(previous.effectiveEstimateBitsPerSecond()),
                        Long.toString(current.lastSampleBitsPerSecond()),
                        Long.toString(current.effectiveEstimateBitsPerSecond()),
                        current.reason().label(),
                        current.action() == ExoThroughputEstimator.Action.HOLD
                                ? current.reason().label() : "none",
                        List.of(
                                estimateInput("raw_bps", current.rawEstimateBitsPerSecond(), current.confidence()),
                                estimateInput("short_bps", current.shortEstimateBitsPerSecond(), current.confidence()),
                                estimateInput("long_bps", current.longEstimateBitsPerSecond(), current.confidence()),
                                estimateInput("effective_bps", current.effectiveEstimateBitsPerSecond(), current.confidence()),
                                PlaybackTelemetry.DecisionInput.number("short_samples", current.shortSampleCount(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("long_samples", current.longSampleCount(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.HIGH),
                                current.predictionErrorPermille() < 0
                                        ? PlaybackTelemetry.DecisionInput.unknown("prediction_error_permille")
                                        : PlaybackTelemetry.DecisionInput.number("prediction_error_permille", current.predictionErrorPermille(), PlaybackAutoContext.ValueSource.ESTIMATOR, current.confidence()),
                                current.pathConfidence() == PlaybackAutoContext.Confidence.UNKNOWN
                                        ? PlaybackTelemetry.DecisionInput.unknown("path_trust")
                                        : PlaybackTelemetry.DecisionInput.text("path_trust", current.pathTrust().label(), PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER, current.pathConfidence()),
                                PlaybackTelemetry.DecisionInput.bool("preload_contended", current.preloadContended(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("sample_elapsed_ms", elapsedMs, PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("sample_bytes", bytes, PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.HIGH))),
                now);
    }

    private static PlaybackTelemetry.DecisionInput estimateInput(
            String name,
            long value,
            PlaybackAutoContext.Confidence confidence) {
        if (value <= 0 || confidence == PlaybackAutoContext.Confidence.UNKNOWN) {
            return PlaybackTelemetry.DecisionInput.unknown(name);
        }
        return PlaybackTelemetry.DecisionInput.number(
                name, value, PlaybackAutoContext.ValueSource.ESTIMATOR, confidence);
    }

    private long now() {
        return Math.max(0, elapsedRealtime.getAsLong());
    }

    private static int clampElapsed(long elapsedMs) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs));
    }

    private static boolean isFullNetworkSpeed(DataSpec dataSpec, boolean isNetwork) {
        return isNetwork && dataSpec != null
                && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED);
    }

    private static long safeAdd(long first, long second) {
        if (first <= 0) return Math.max(0, second);
        if (second <= 0) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private record TransferState(PlaybackAutoContext.SessionToken session) {

        private TransferState {
            session = session == null ? PlaybackAutoContext.SessionToken.none() : session;
        }
    }
}
