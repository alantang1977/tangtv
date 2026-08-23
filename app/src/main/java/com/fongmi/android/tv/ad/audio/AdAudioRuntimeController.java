package com.fongmi.android.tv.ad.audio;

import com.fongmi.android.tv.player.audio.PlaybackMediaClock;
import com.fongmi.android.tv.player.audio.PlaybackMediaSignalHub;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdAudioRuntimeController implements AutoCloseable {

    public interface RuleSource {
        AdAudioRuleStore.Snapshot load();
    }

    public interface PlaybackPort extends AdSkipCoordinator.PlaybackPort {
        boolean isEligible(long sessionId, long generation);
    }

    private final PlaybackMediaSignalHub hub;
    private final PlaybackMediaClock clock;
    private final RuleSource ruleSource;
    private final PlaybackPort playback;
    private final Executor worker;
    private final Runnable workerShutdown;
    private final AdAudioDiagnostics diagnostics = new AdAudioDiagnostics();

    private AdAudioRuleStore.Snapshot snapshot = new AdAudioRuleStore.Snapshot(
            "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "");
    private AdSkipCoordinator.UiPort ui;
    private AdSkipCoordinator coordinator;
    private AdAudioConsumer consumer;
    private PlaybackMediaSignalHub.Registration registration;
    private PlaybackMediaSignalHub.CaptureLease captureLease;
    private boolean enabled;
    private boolean closed;

    public AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                                    RuleSource ruleSource, PlaybackPort playback) {
        this(hub, clock, ruleSource, playback, createWorker());
    }

    private AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                                     RuleSource ruleSource, PlaybackPort playback,
                                     Worker worker) {
        this(hub, clock, ruleSource, playback, worker.executor, worker.executor::shutdownNow);
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             RuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ruleSource = Objects.requireNonNull(ruleSource, "ruleSource");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerShutdown = workerShutdown;
    }

    public synchronized void start(boolean enabled) {
        if (closed) return;
        this.enabled = enabled;
        reconfigureLocked();
    }

    public synchronized void reloadRules() {
        if (closed) return;
        reconfigureLocked();
    }

    private void reconfigureLocked() {
        loadRulesLocked();
        deactivateLocked();
        PlaybackMediaSignalHub.Session session = hub.session();
        if (coordinator != null) coordinator.reset(session.id());
        refreshLocked();
    }

    public synchronized void bindUi(AdSkipCoordinator.UiPort ui) {
        if (closed) return;
        if (coordinator != null) coordinator.close();
        this.ui = Objects.requireNonNull(ui, "ui");
        this.coordinator = new AdSkipCoordinator(playback, ui, 5_000L, diagnostics);
        refreshLocked();
    }

    public synchronized void unbindUi() {
        if (coordinator != null) coordinator.close();
        coordinator = null;
        ui = null;
        deactivateLocked();
    }

    public synchronized void refresh() {
        if (closed) return;
        refreshLocked();
    }

    public synchronized void suspend() {
        if (closed) return;
        deactivateLocked();
        PlaybackMediaSignalHub.Session session = hub.session();
        if (coordinator != null) coordinator.reset(session.id());
    }

    public synchronized boolean needsPipelineRebuild() {
        return consumer != null && captureLease != null && !hub.isPipelineAttached();
    }

    public synchronized boolean isActive() {
        return consumer != null && captureLease != null;
    }

    public synchronized AdAudioRuleStore.Snapshot snapshot() {
        return snapshot;
    }

    public AdAudioDiagnostics.Snapshot diagnostics() {
        return diagnostics.snapshot();
    }

    public synchronized void stop() {
        if (closed) return;
        enabled = false;
        deactivateLocked();
        if (coordinator != null) coordinator.close();
        coordinator = null;
        ui = null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        deactivateLocked();
        if (coordinator != null) coordinator.close();
        coordinator = null;
        ui = null;
        if (workerShutdown != null) workerShutdown.run();
    }

    private void loadRulesLocked() {
        try {
            AdAudioRuleStore.Snapshot loaded = ruleSource.load();
            if (loaded == null) {
                diagnostics.record(AdAudioDiagnostics.Code.RULE_LOAD_FAILED);
                snapshot = new AdAudioRuleStore.Snapshot(
                        "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "RULE_LOAD_FAILED");
            } else {
                snapshot = loaded;
                if (loaded.hasError()) diagnostics.record(AdAudioDiagnostics.Code.RULE_LOAD_FAILED);
            }
        } catch (RuntimeException e) {
            diagnostics.record(AdAudioDiagnostics.Code.RULE_LOAD_FAILED);
            snapshot = new AdAudioRuleStore.Snapshot(
                    "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "RULE_LOAD_FAILED");
        }
    }

    private void refreshLocked() {
        if (!enabled || ui == null || snapshot.hasError() || !snapshot.hasRules()) {
            deactivateLocked();
            return;
        }
        PlaybackMediaSignalHub.Session session = hub.session();
        if (!playback.isEligible(session.id(), session.generation())) {
            deactivateLocked();
            return;
        }
        if (consumer != null && captureLease != null) return;
        activateLocked(session);
    }

    private void activateLocked(PlaybackMediaSignalHub.Session session) {
        AdSkipCoordinator currentCoordinator = coordinator;
        if (currentCoordinator == null) return;
        AdAudioConsumer[] holder = new AdAudioConsumer[1];
        AdAudioConsumer nextConsumer = new AdAudioConsumer(
                candidate -> {
                    synchronized (AdAudioRuntimeController.this) {
                        if (consumer != holder[0] || coordinator != currentCoordinator) return;
                    }
                    currentCoordinator.onCandidate(candidate);
                },
                8,
                Runnable::run,
                diagnostics);
        holder[0] = nextConsumer;
        nextConsumer.start(session.id(), session.generation(), snapshot.ruleSet());
        PlaybackMediaSignalHub.Consumer bridge = new PlaybackMediaSignalHub.Consumer() {
            @Override
            public void onPcm(PlaybackMediaSignalHub.PcmFrame frame) {
                nextConsumer.onPcm(frame);
            }

            @Override
            public void onLifecycle(PlaybackMediaSignalHub.Lifecycle event) {
                nextConsumer.onLifecycle(event);
                currentCoordinator.onTimelineReset(event);
            }

            @Override
            public void onFailure(RuntimeException error) {
                nextConsumer.onFailure(error);
            }
        };
        PlaybackMediaSignalHub.Registration nextRegistration = hub.register(
                "ad-audio", worker, 8, bridge);
        PlaybackMediaSignalHub.CaptureLease nextLease = hub.requestCapture(
                PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO);
        consumer = nextConsumer;
        registration = nextRegistration;
        captureLease = nextLease;
    }

    private void deactivateLocked() {
        if (captureLease != null) {
            captureLease.close();
            captureLease = null;
        }
        if (registration != null) {
            registration.close();
            registration = null;
        }
        if (consumer != null) {
            consumer.close();
            consumer = null;
        }
    }

    private static Worker createWorker() {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ad-audio-matcher");
            thread.setDaemon(true);
            return thread;
        });
        return new Worker(executor);
    }

    private record Worker(ExecutorService executor) {
    }
}
