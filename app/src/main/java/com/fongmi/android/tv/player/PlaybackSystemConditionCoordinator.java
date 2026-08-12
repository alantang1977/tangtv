package com.fongmi.android.tv.player;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Session-safe process coordinator for network, power and thermal facts. */
public final class PlaybackSystemConditionCoordinator {

    private static final PlaybackSystemConditionCoordinator PROCESS =
            new PlaybackSystemConditionCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<PlaybackAutoContext.SessionToken> activeSession;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners;

    public PlaybackSystemConditionCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
        this.activeSession = new AtomicReference<>(PlaybackAutoContext.SessionToken.none());
        this.listeners = new CopyOnWriteArrayList<>();
    }

    public static PlaybackSystemConditionCoordinator process() {
        return PROCESS;
    }

    public boolean beginSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active() || !session.equals(store.snapshot().session())) return false;
        activeSession.set(session);
        return true;
    }

    public boolean endSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active()) return false;
        return activeSession.compareAndSet(session, PlaybackAutoContext.SessionToken.none());
    }

    public PlaybackAutoContext.SessionToken activeSession() {
        return activeSession.get();
    }

    public boolean publish(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SystemConditionTrigger trigger,
            PlaybackAutoContext.NetworkSnapshot networkSnapshot,
            Boolean powerSave,
            Integer thermalStatus,
            int sdkInt,
            long publishedAtElapsedMs) {
        if (session == null || !session.equals(activeSession.get())) return false;
        PlaybackAutoContext.SystemConditionTrigger safeTrigger = trigger == null
                ? PlaybackAutoContext.SystemConditionTrigger.UNKNOWN : trigger;
        long publishedAt = Math.max(0, publishedAtElapsedMs);

        PlaybackSystemConditionPolicy.NetworkResult networkResult =
                PlaybackSystemConditionPolicy.evaluateNetwork(networkSnapshot);
        PlaybackAutoContext.Confidence networkConfidence =
                PlaybackSystemConditionPolicy.networkSnapshotConfidence(networkSnapshot);
        PlaybackAutoContext.ValueSource networkSource = networkSource(safeTrigger);
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> networkSnapshotFact =
                networkSnapshot != null && networkSnapshot.hasEvidence()
                        && networkConfidence != PlaybackAutoContext.Confidence.UNKNOWN
                        ? PlaybackAutoContext.Fact.withTtl(networkSnapshot, networkSource,
                        networkConfidence, publishedAt, PlaybackSystemConditionPolicy.FACT_TTL_MS)
                        : null;
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> networkCostFact = networkResult.known()
                ? PlaybackAutoContext.Fact.withTtl(networkResult.cost(), networkSource,
                networkResult.confidence(), publishedAt, PlaybackSystemConditionPolicy.FACT_TTL_MS)
                : null;

        PlaybackAutoContext.PowerState powerState = PlaybackSystemConditionPolicy.powerState(powerSave);
        PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> powerFact =
                powerState == PlaybackAutoContext.PowerState.UNKNOWN ? null
                        : PlaybackAutoContext.Fact.withTtl(powerState, powerSource(safeTrigger),
                        PlaybackAutoContext.Confidence.HIGH, publishedAt,
                        PlaybackSystemConditionPolicy.FACT_TTL_MS);

        PlaybackAutoContext.ThermalState thermalState =
                PlaybackSystemConditionPolicy.thermalState(thermalStatus, sdkInt);
        PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermalFact =
                thermalState == PlaybackAutoContext.ThermalState.UNKNOWN ? null
                        : PlaybackAutoContext.Fact.withTtl(thermalState, thermalSource(safeTrigger),
                        PlaybackAutoContext.Confidence.HIGH, publishedAt,
                        PlaybackSystemConditionPolicy.FACT_TTL_MS);

        if (thermalFact == null && powerFact == null && networkCostFact == null && networkSnapshotFact == null) {
            return false;
        }
        if (!store.publishSystemConditionFacts(session, thermalFact, powerFact,
                networkCostFact, networkSnapshotFact, publishedAt)) return false;
        if (!session.equals(activeSession.get())) return false;
        PlaybackAutoContext current = store.snapshot();
        if (!session.equals(current.session())) return false;
        Update update = new Update(
                session,
                safeTrigger,
                current.device().thermalState(),
                current.device().powerState(),
                current.device().networkCost(),
                current.device().networkSnapshot(),
                publishedAt);
        notifyListeners(update);
        return true;
    }

    public Registration addListener(Listener listener) {
        ListenerRegistration registration = new ListenerRegistration(Objects.requireNonNull(listener));
        listeners.add(registration);
        return registration;
    }

    int listenerCount() {
        return listeners.size();
    }

    private void notifyListeners(Update update) {
        for (ListenerRegistration registration : listeners) {
            if (registration.closed.get()) continue;
            try {
                registration.listener.onSystemConditionUpdate(update);
            } catch (Throwable ignored) {
            }
        }
    }

    private static PlaybackAutoContext.ValueSource networkSource(
            PlaybackAutoContext.SystemConditionTrigger trigger) {
        return switch (trigger) {
            case NETWORK_CALLBACK, DATA_SAVER_CALLBACK -> PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK;
            default -> PlaybackAutoContext.ValueSource.SYSTEM_API;
        };
    }

    private static PlaybackAutoContext.ValueSource powerSource(
            PlaybackAutoContext.SystemConditionTrigger trigger) {
        return trigger == PlaybackAutoContext.SystemConditionTrigger.POWER_CALLBACK
                ? PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK
                : PlaybackAutoContext.ValueSource.SYSTEM_API;
    }

    private static PlaybackAutoContext.ValueSource thermalSource(
            PlaybackAutoContext.SystemConditionTrigger trigger) {
        return trigger == PlaybackAutoContext.SystemConditionTrigger.THERMAL_CALLBACK
                ? PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK
                : PlaybackAutoContext.ValueSource.SYSTEM_API;
    }

    public interface Listener {
        void onSystemConditionUpdate(Update update);
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public record Update(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SystemConditionTrigger trigger,
            PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermal,
            PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> power,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> networkCost,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> networkSnapshot,
            long publishedAtElapsedMs) {

        public String logSummary() {
            PlaybackAutoContext.NetworkSnapshot network = networkSnapshot == null
                    ? PlaybackAutoContext.NetworkSnapshot.unknown() : networkSnapshot.value();
            return "trigger=" + (trigger == null
                    ? PlaybackAutoContext.SystemConditionTrigger.UNKNOWN.label() : trigger.label())
                    + " thermal=" + factValue(thermal, PlaybackAutoContext.ThermalState.UNKNOWN.label())
                    + " power=" + factValue(power, PlaybackAutoContext.PowerState.UNKNOWN.label())
                    + " networkCost=" + factValue(networkCost, PlaybackAutoContext.NetworkCost.UNKNOWN.label())
                    + " available=" + safeBoolean(network.available())
                    + " validated=" + safeBoolean(network.validated())
                    + " metered=" + safeBoolean(network.metered())
                    + " roaming=" + safeBoolean(network.roaming())
                    + " transport=" + network.transport().label()
                    + " dataSaver=" + network.dataSaverState().label();
        }

        private static String factValue(PlaybackAutoContext.Fact<?> fact, String unknown) {
            if (fact == null || !fact.hasValue()) return unknown;
            Object value = fact.value();
            if (value instanceof PlaybackAutoContext.ThermalState thermal) return thermal.label();
            if (value instanceof PlaybackAutoContext.PowerState power) return power.label();
            if (value instanceof PlaybackAutoContext.NetworkCost cost) return cost.label();
            return unknown;
        }

        private static String safeBoolean(Boolean value) {
            return value == null ? "unknown" : value.toString();
        }
    }

    private final class ListenerRegistration implements Registration {

        private final Listener listener;
        private final AtomicBoolean closed;

        private ListenerRegistration(Listener listener) {
            this.listener = listener;
            this.closed = new AtomicBoolean();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            listeners.remove(this);
        }
    }
}
