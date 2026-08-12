package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.util.ArrayDeque;
import java.util.List;

/** Session-isolated controller for MPV HLS staged options and reload fallback. */
public final class MpvHlsVariantController {

    public static final int CONFIRMATION_SAMPLES = 3;
    public static final int CONFIRMATION_HARD_RISK_SAMPLES = 2;
    public static final long RUNTIME_SAMPLE_INTERVAL_MS = 5_000L;
    public static final long CONFIRMATION_WINDOW_MS = 10_000L;
    public static final long MAX_SAMPLE_GAP_MS = 10_000L;
    public static final long BUFFER_DECLINE_MIN_MS = 1_000L;
    public static final long RELOAD_READY_TIMEOUT_MS = 20_000L;
    public static final long SUCCESS_COOLDOWN_MS = 30_000L;
    public static final long FAILURE_COOLDOWN_MS = 60_000L;
    public static final int MAX_RELOAD_ATTEMPTS = 3;

    private PlaybackAutoContext.SessionToken session =
            PlaybackAutoContext.SessionToken.none();
    private State state = State.IDLE;
    private boolean initialAccepted;
    private boolean readySeen;
    private String targetOption = "";
    private long targetCeilingBitsPerSecond;
    private long previousBufferMs = -1;
    private int previousRebufferCount = -1;
    private long previousUnderrunCount = -1;
    private long lastRuntimeSampleAtMs = -1;
    private long riskSinceMs = -1;
    private int riskSamples;
    private int hardRiskSamples;
    private int throughputRiskSamples;
    private int bufferRiskSamples;
    private int reloadAttempts;
    private int successfulDowngrades;
    private int rollbackCount;
    private int failedActions;
    private long cooldownUntilMs;
    private final ArrayDeque<RiskSample> riskWindow = new ArrayDeque<>();
    private Pending pending;
    private Decision lastDecision = Decision.hold(
            Reason.WAITING_INITIAL, MpvHlsVariantPolicy.Reason.NOT_AUTOMATIC);

    public synchronized void beginSession(PlaybackAutoContext.SessionToken token) {
        reset(token);
    }

    public synchronized void endSession(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        reset(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized void suppress(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        initialAccepted = false;
        readySeen = false;
        targetOption = "";
        targetCeilingBitsPerSecond = 0;
        pending = null;
        clearRiskEvidence();
        state = State.SUPPRESSED;
    }

    public synchronized Decision evaluateInitial(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            MpvHlsVariantPolicy.InitialAssessment assessment) {
        if (!isCurrent(token) || factsSession == null || !token.equals(factsSession)) {
            return remember(Decision.hold(
                    Reason.STALE_SESSION, MpvHlsVariantPolicy.Reason.NOT_MPV));
        }
        MpvHlsVariantPolicy.InitialAssessment current = assessment == null
                ? MpvHlsVariantPolicy.resolveInitial(false, false, false, null)
                : assessment;
        if (!current.active()) {
            state = current.reason() == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                    ? State.SUPPRESSED : State.INACTIVE;
            return remember(Decision.hold(
                    current.reason() == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                            ? Reason.CONFIG_PRIORITY : Reason.NOT_AUTOMATIC,
                    current.reason()));
        }
        if (state == State.APPLYING || pending != null) {
            return remember(Decision.hold(
                    Reason.ACTION_PENDING, current.reason()));
        }
        String option = initialAccepted ? targetOption : current.option();
        long ceiling = initialAccepted
                ? targetCeilingBitsPerSecond : current.ceilingBitsPerSecond();
        Reason reason = initialAccepted
                ? Reason.CONTEXT_RESTORE
                : current.reason() == MpvHlsVariantPolicy.Reason.TRUSTED_HISTORY
                ? Reason.INITIAL_TRUSTED_HISTORY
                : Reason.INITIAL_CONSERVATIVE;
        state = State.INITIAL_READY;
        return remember(new Decision(
                Action.APPLY_INITIAL,
                reason,
                current.reason(),
                targetOption,
                option,
                ceiling,
                PlaybackAutoContext.StreamKind.UNKNOWN,
                0,
                riskSamples,
                hardRiskSamples,
                throughputRiskSamples,
                bufferRiskSamples,
                0));
    }

    public synchronized Decision evaluateRuntime(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            RuntimeObservation observation,
            long nowElapsedMs) {
        long now = Math.max(0, nowElapsedMs);
        if (!isCurrent(token) || factsSession == null || !token.equals(factsSession)) {
            return remember(Decision.hold(
                    Reason.STALE_SESSION, MpvHlsVariantPolicy.Reason.NOT_MPV));
        }
        if (pending != null) {
            if (pending.mode() == PendingMode.DOWNGRADE
                    && now >= pending.deadlineAtMs()
                    && state != State.ROLLBACK_READY
                    && state != State.APPLYING) {
                state = State.ROLLBACK_READY;
                return remember(rollbackDecision(Reason.RELOAD_TIMEOUT));
            }
            if (pending.mode() == PendingMode.ROLLBACK
                    && now >= pending.deadlineAtMs()
                    && state != State.APPLYING) {
                pending = null;
                state = State.FAILED;
                failedActions = increment(failedActions);
                cooldownUntilMs = now + FAILURE_COOLDOWN_MS;
                clearRiskEvidence();
                return remember(Decision.hold(
                        Reason.ROLLBACK_TIMEOUT,
                        MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
            }
            return remember(Decision.hold(
                    pending.mode() == PendingMode.DOWNGRADE
                            ? Reason.RELOAD_PENDING : Reason.ROLLBACK_PENDING,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
        }
        if (!initialAccepted) {
            return remember(Decision.hold(
                    Reason.WAITING_INITIAL,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
        }
        if (now < cooldownUntilMs) {
            state = State.COOLDOWN;
            return remember(Decision.hold(
                    Reason.COOLDOWN,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE,
                    cooldownUntilMs - now));
        }
        if (!readySeen) {
            state = State.WAITING_READY;
            return remember(Decision.hold(
                    Reason.WAITING_READY,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
        }
        RuntimeObservation current = observation == null
                ? RuntimeObservation.inactive() : observation;
        if (lastRuntimeSampleAtMs >= 0 && now <= lastRuntimeSampleAtMs) {
            return remember(Decision.hold(
                    Reason.DUPLICATE_SAMPLE,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
        }
        if (lastRuntimeSampleAtMs >= 0
                && now - lastRuntimeSampleAtMs < RUNTIME_SAMPLE_INTERVAL_MS) {
            return remember(Decision.hold(
                    Reason.SAMPLE_INTERVAL,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
        }

        boolean gapTooLarge = lastRuntimeSampleAtMs >= 0
                && now - lastRuntimeSampleAtMs > MAX_SAMPLE_GAP_MS;
        boolean bufferDeclining = current.bufferUsable()
                && !gapTooLarge
                && previousBufferMs >= 0
                && previousBufferMs - current.bufferedDurationMs()
                >= BUFFER_DECLINE_MIN_MS;
        boolean rebufferRisk = previousRebufferCount >= 0
                && current.rebufferCount() > previousRebufferCount;
        boolean underrunRisk = current.underrun()
                || previousUnderrunCount >= 0
                && current.underrunCount() > previousUnderrunCount;
        previousBufferMs = current.bufferUsable()
                ? current.bufferedDurationMs() : -1;
        previousRebufferCount = current.rebufferCount();
        previousUnderrunCount = current.underrunCount();
        lastRuntimeSampleAtMs = now;
        if (gapTooLarge) clearRiskEvidence();

        MpvHlsVariantPolicy.Assessment assessment = MpvHlsVariantPolicy.assess(
                new MpvHlsVariantPolicy.RuntimeRequest(
                        current.automatic(),
                        current.mpvKernel(),
                        current.performanceOptionsPriority(),
                        current.protocol(),
                        current.protocolUsable(),
                        current.streamKind(),
                        current.streamKindUsable(),
                        current.variants(),
                        current.selectedVariant(),
                        underrunRisk,
                        rebufferRisk,
                        current.buffering(),
                        bufferDeclining,
                        current.bufferUsable(),
                        current.bufferedDurationMs(),
                        current.rawThroughputBitsPerSecond(),
                        current.rawThroughputUsable()));
        if (!assessment.active()) {
            clearRiskEvidence();
            state = assessment.reason() == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                    ? State.SUPPRESSED : State.INACTIVE;
            return remember(Decision.hold(
                    Reason.POLICY_INACTIVE, assessment.reason()));
        }
        if (assessment.nextLowerVariant() == null) {
            clearRiskEvidence();
            state = State.ACTIVE;
            return remember(Decision.hold(
                    Reason.LOWEST_VARIANT, assessment.reason()));
        }

        observeRisk(assessment, now);
        if (!confirmed(now)) {
            state = riskSamples > 0 ? State.EVIDENCE_WAIT : State.ACTIVE;
            return remember(Decision.hold(
                    riskSamples > 0 ? Reason.RISK_CONFIRMING : Reason.EVIDENCE_INCOMPLETE,
                    assessment.reason()));
        }
        if (reloadAttempts >= MAX_RELOAD_ATTEMPTS) {
            clearRiskEvidence();
            state = State.ACTIVE;
            return remember(Decision.hold(
                    Reason.MAX_RELOAD_ATTEMPTS, assessment.reason()));
        }

        long target = assessment.nextLowerBitsPerSecond();
        long resumePosition = current.streamKind() == PlaybackAutoContext.StreamKind.VOD
                ? current.positionMs() : 0;
        state = State.RELOAD_READY;
        return remember(new Decision(
                Action.RELOAD_LOWER,
                Reason.RELOAD_ONE_STEP,
                assessment.reason(),
                targetOption,
                MpvHlsVariantPolicy.optionFor(target),
                target,
                current.streamKind(),
                resumePosition,
                riskSamples,
                hardRiskSamples,
                throughputRiskSamples,
                bufferRiskSamples,
                0));
    }

    public synchronized Decision requestRollbackOnError(
            PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token) || pending == null
                || pending.mode() != PendingMode.DOWNGRADE) {
            return remember(Decision.hold(
                    Reason.NO_ROLLBACK_PENDING,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE));
        }
        state = State.ROLLBACK_READY;
        return remember(rollbackDecision(Reason.RELOAD_ERROR));
    }

    /** Reserves the action before native/player calls are made. */
    public synchronized boolean beginApply(
            PlaybackAutoContext.SessionToken token,
            Decision decision,
            long nowElapsedMs) {
        if (!isCurrent(token)
                || decision == null
                || !decision.requestsApply()
                || !decision.equals(lastDecision)
                || state == State.APPLYING) {
            return false;
        }
        long now = Math.max(0, nowElapsedMs);
        if (decision.action() == Action.APPLY_INITIAL) {
            if (state != State.INITIAL_READY) return false;
        } else if (decision.action() == Action.RELOAD_LOWER) {
            if (state != State.RELOAD_READY || pending != null) return false;
            pending = new Pending(
                    PendingMode.DOWNGRADE,
                    decision.oldOption(),
                    decision.targetOption(),
                    decision.streamKind(),
                    decision.resumePositionMs(),
                    now + RELOAD_READY_TIMEOUT_MS);
            if (reloadAttempts < Integer.MAX_VALUE) reloadAttempts++;
        } else if (decision.action() == Action.ROLLBACK) {
            if (state != State.ROLLBACK_READY || pending == null
                    || pending.mode() != PendingMode.DOWNGRADE) return false;
            pending = pending.asRollback(now + RELOAD_READY_TIMEOUT_MS);
            if (rollbackCount < Integer.MAX_VALUE) rollbackCount++;
        } else {
            return false;
        }
        state = State.APPLYING;
        return true;
    }

    public synchronized void completeApply(
            PlaybackAutoContext.SessionToken token,
            Decision decision,
            boolean accepted,
            boolean staged,
            long completedAtElapsedMs) {
        if (!isCurrent(token)
                || decision == null
                || !decision.equals(lastDecision)
                || state != State.APPLYING) {
            return;
        }
        long completedAt = Math.max(0, completedAtElapsedMs);
        if (decision.action() == Action.APPLY_INITIAL) {
            if (accepted) {
                initialAccepted = true;
                targetOption = decision.targetOption();
                targetCeilingBitsPerSecond = decision.targetBitsPerSecond();
                readySeen = false;
                state = staged ? State.STAGED : State.ACTIVE;
            } else {
                initialAccepted = false;
                state = State.FAILED;
                failedActions = increment(failedActions);
            }
            clearRuntimeObservations();
            return;
        }
        if (!accepted) {
            Pending failed = pending;
            if (failed != null && failed.mode() == PendingMode.DOWNGRADE) {
                targetOption = failed.oldOption();
            }
            pending = null;
            state = State.FAILED;
            failedActions = increment(failedActions);
            cooldownUntilMs = completedAt + FAILURE_COOLDOWN_MS;
            clearRiskEvidence();
            return;
        }
        targetOption = decision.targetOption();
        targetCeilingBitsPerSecond = decision.targetBitsPerSecond();
        readySeen = false;
        state = decision.action() == Action.RELOAD_LOWER
                ? State.RELOAD_PENDING : State.ROLLBACK_PENDING;
        clearRiskEvidence();
    }

    public synchronized Completion onPlaybackReady(
            PlaybackAutoContext.SessionToken token,
            long nowElapsedMs) {
        if (!isCurrent(token)) return Completion.none();
        long now = Math.max(0, nowElapsedMs);
        readySeen = true;
        if (pending == null) {
            if (initialAccepted) state = State.ACTIVE;
            return Completion.none();
        }
        Pending finished = pending;
        pending = null;
        clearRuntimeObservations();
        if (finished.mode() == PendingMode.DOWNGRADE) {
            successfulDowngrades = increment(successfulDowngrades);
            cooldownUntilMs = now + SUCCESS_COOLDOWN_MS;
            state = State.COOLDOWN;
            return new Completion(true, false, finished.targetOption(),
                    Reason.RELOAD_CONFIRMED);
        }
        failedActions = increment(failedActions);
        cooldownUntilMs = now + FAILURE_COOLDOWN_MS;
        state = State.COOLDOWN;
        return new Completion(false, true, finished.oldOption(),
                Reason.ROLLBACK_CONFIRMED);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                session,
                state,
                initialAccepted,
                readySeen,
                targetOption,
                targetCeilingBitsPerSecond,
                riskSinceMs,
                riskSamples,
                hardRiskSamples,
                throughputRiskSamples,
                bufferRiskSamples,
                reloadAttempts,
                successfulDowngrades,
                rollbackCount,
                failedActions,
                cooldownUntilMs,
                pending == null ? PendingMode.NONE : pending.mode(),
                pending == null ? 0 : pending.deadlineAtMs(),
                lastDecision);
    }

    private Decision rollbackDecision(Reason reason) {
        Pending current = pending;
        if (current == null) {
            return Decision.hold(Reason.NO_ROLLBACK_PENDING,
                    MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE);
        }
        return new Decision(
                Action.ROLLBACK,
                reason,
                MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE,
                current.targetOption(),
                current.oldOption(),
                optionBits(current.oldOption()),
                current.streamKind(),
                current.resumePositionMs(),
                riskSamples,
                hardRiskSamples,
                throughputRiskSamples,
                bufferRiskSamples,
                0);
    }

    private void observeRisk(MpvHlsVariantPolicy.Assessment assessment, long now) {
        boolean anyRisk = assessment.hardRisk()
                || assessment.throughputShortfall()
                || assessment.bufferRisk();
        if (!anyRisk) {
            clearRiskEvidence();
            return;
        }
        riskWindow.addLast(new RiskSample(
                now,
                assessment.hardRisk(),
                assessment.throughputShortfall(),
                assessment.bufferRisk()));
        pruneRiskWindow(now);
        recomputeRiskCounts();
    }

    private boolean confirmed(long now) {
        return riskSamples >= CONFIRMATION_SAMPLES
                && hardRiskSamples >= CONFIRMATION_HARD_RISK_SAMPLES
                && throughputRiskSamples >= CONFIRMATION_SAMPLES
                && bufferRiskSamples >= CONFIRMATION_SAMPLES
                && riskSinceMs >= 0
                && now - riskSinceMs >= CONFIRMATION_WINDOW_MS;
    }

    private void clearRiskEvidence() {
        riskWindow.clear();
        riskSinceMs = -1;
        riskSamples = 0;
        hardRiskSamples = 0;
        throughputRiskSamples = 0;
        bufferRiskSamples = 0;
    }

    private void pruneRiskWindow(long now) {
        while (!riskWindow.isEmpty()
                && now - riskWindow.peekFirst().sampledAtMs()
                > CONFIRMATION_WINDOW_MS) {
            riskWindow.removeFirst();
        }
    }

    private void recomputeRiskCounts() {
        riskSinceMs = riskWindow.isEmpty()
                ? -1 : riskWindow.peekFirst().sampledAtMs();
        riskSamples = riskWindow.size();
        hardRiskSamples = 0;
        throughputRiskSamples = 0;
        bufferRiskSamples = 0;
        for (RiskSample sample : riskWindow) {
            if (sample.hardRisk()) hardRiskSamples = increment(hardRiskSamples);
            if (sample.throughputRisk()) {
                throughputRiskSamples = increment(throughputRiskSamples);
            }
            if (sample.bufferRisk()) {
                bufferRiskSamples = increment(bufferRiskSamples);
            }
        }
    }

    private void clearRuntimeObservations() {
        previousBufferMs = -1;
        previousRebufferCount = -1;
        previousUnderrunCount = -1;
        lastRuntimeSampleAtMs = -1;
        clearRiskEvidence();
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private void reset(PlaybackAutoContext.SessionToken token) {
        session = token == null
                ? PlaybackAutoContext.SessionToken.none() : token;
        state = session.active() ? State.WAITING_INITIAL : State.IDLE;
        initialAccepted = false;
        readySeen = false;
        targetOption = "";
        targetCeilingBitsPerSecond = 0;
        reloadAttempts = 0;
        successfulDowngrades = 0;
        rollbackCount = 0;
        failedActions = 0;
        cooldownUntilMs = 0;
        pending = null;
        clearRuntimeObservations();
        lastDecision = Decision.hold(
                Reason.WAITING_INITIAL,
                MpvHlsVariantPolicy.Reason.NOT_AUTOMATIC);
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static long optionBits(String option) {
        if (option == null || option.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(option));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record RuntimeObservation(
            boolean automatic,
            boolean mpvKernel,
            boolean performanceOptionsPriority,
            PlaybackAutoContext.Protocol protocol,
            boolean protocolUsable,
            PlaybackAutoContext.StreamKind streamKind,
            boolean streamKindUsable,
            List<MpvHlsVariantPolicy.Variant> variants,
            MpvHlsVariantPolicy.Variant selectedVariant,
            boolean underrun,
            long underrunCount,
            int rebufferCount,
            boolean buffering,
            boolean bufferUsable,
            long bufferedDurationMs,
            long rawThroughputBitsPerSecond,
            boolean rawThroughputUsable,
            long positionMs) {

        public RuntimeObservation {
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            variants = variants == null ? List.of() : List.copyOf(variants);
            underrunCount = Math.max(0, underrunCount);
            rebufferCount = Math.max(0, rebufferCount);
            bufferedDurationMs = Math.max(0, bufferedDurationMs);
            rawThroughputBitsPerSecond = Math.max(0,
                    rawThroughputBitsPerSecond);
            positionMs = Math.max(0, positionMs);
        }

        static RuntimeObservation inactive() {
            return new RuntimeObservation(false, false, false,
                    PlaybackAutoContext.Protocol.UNKNOWN, false,
                    PlaybackAutoContext.StreamKind.UNKNOWN, false,
                    List.of(), null, false, 0, 0,
                    false, false, 0, 0, false, 0);
        }
    }

    public record Decision(
            Action action,
            Reason reason,
            MpvHlsVariantPolicy.Reason policyReason,
            String oldOption,
            String targetOption,
            long targetBitsPerSecond,
            PlaybackAutoContext.StreamKind streamKind,
            long resumePositionMs,
            int riskSamples,
            int hardRiskSamples,
            int throughputRiskSamples,
            int bufferRiskSamples,
            long cooldownRemainingMs) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            reason = reason == null ? Reason.EVIDENCE_INCOMPLETE : reason;
            policyReason = policyReason == null
                    ? MpvHlsVariantPolicy.Reason.EVIDENCE_INCOMPLETE : policyReason;
            oldOption = oldOption == null ? "" : oldOption;
            targetOption = targetOption == null ? "" : targetOption;
            targetBitsPerSecond = Math.max(0, targetBitsPerSecond);
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            resumePositionMs = Math.max(0, resumePositionMs);
            riskSamples = Math.max(0, riskSamples);
            hardRiskSamples = Math.max(0, hardRiskSamples);
            throughputRiskSamples = Math.max(0, throughputRiskSamples);
            bufferRiskSamples = Math.max(0, bufferRiskSamples);
            cooldownRemainingMs = Math.max(0, cooldownRemainingMs);
        }

        public static Decision hold(
                Reason reason,
                MpvHlsVariantPolicy.Reason policyReason) {
            return hold(reason, policyReason, 0);
        }

        public static Decision hold(
                Reason reason,
                MpvHlsVariantPolicy.Reason policyReason,
                long cooldownRemainingMs) {
            return new Decision(Action.HOLD, reason, policyReason,
                    "", "", 0, PlaybackAutoContext.StreamKind.UNKNOWN,
                    0, 0, 0, 0, 0, cooldownRemainingMs);
        }

        public boolean requestsApply() {
            return action != Action.HOLD;
        }

        public boolean reloadsMedia() {
            return action == Action.RELOAD_LOWER || action == Action.ROLLBACK;
        }

        public boolean preservesVodPosition() {
            return streamKind == PlaybackAutoContext.StreamKind.VOD;
        }
    }

    public record Completion(
            boolean downgradeConfirmed,
            boolean rollbackConfirmed,
            String option,
            Reason reason) {

        public Completion {
            option = option == null ? "" : option;
            reason = reason == null ? Reason.EVIDENCE_INCOMPLETE : reason;
        }

        static Completion none() {
            return new Completion(false, false, "", Reason.EVIDENCE_INCOMPLETE);
        }

        public boolean changed() {
            return downgradeConfirmed || rollbackConfirmed;
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            State state,
            boolean initialAccepted,
            boolean readySeen,
            String targetOption,
            long targetCeilingBitsPerSecond,
            long riskSinceMs,
            int riskSamples,
            int hardRiskSamples,
            int throughputRiskSamples,
            int bufferRiskSamples,
            int reloadAttempts,
            int successfulDowngrades,
            int rollbackCount,
            int failedActions,
            long cooldownUntilMs,
            PendingMode pendingMode,
            long pendingDeadlineAtMs,
            Decision lastDecision) {

        public Snapshot {
            session = session == null
                    ? PlaybackAutoContext.SessionToken.none() : session;
            state = state == null ? State.IDLE : state;
            targetOption = targetOption == null ? "" : targetOption;
            targetCeilingBitsPerSecond = Math.max(0,
                    targetCeilingBitsPerSecond);
            pendingMode = pendingMode == null ? PendingMode.NONE : pendingMode;
        }
    }

    public enum Action {
        HOLD("hold"),
        APPLY_INITIAL("apply-initial"),
        RELOAD_LOWER("reload-lower"),
        ROLLBACK("rollback");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum State {
        IDLE,
        WAITING_INITIAL,
        INITIAL_READY,
        STAGED,
        WAITING_READY,
        ACTIVE,
        EVIDENCE_WAIT,
        RELOAD_READY,
        RELOAD_PENDING,
        ROLLBACK_READY,
        ROLLBACK_PENDING,
        APPLYING,
        COOLDOWN,
        SUPPRESSED,
        INACTIVE,
        FAILED
    }

    public enum PendingMode {
        NONE("none"),
        DOWNGRADE("downgrade"),
        ROLLBACK("rollback");

        private final String label;

        PendingMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        WAITING_INITIAL("waiting-initial"),
        INITIAL_TRUSTED_HISTORY("initial-trusted-history"),
        INITIAL_CONSERVATIVE("initial-conservative"),
        CONTEXT_RESTORE("context-restore"),
        NOT_AUTOMATIC("not-automatic"),
        CONFIG_PRIORITY("config-priority"),
        STALE_SESSION("stale-session"),
        ACTION_PENDING("action-pending"),
        WAITING_READY("waiting-ready"),
        DUPLICATE_SAMPLE("duplicate-sample"),
        SAMPLE_INTERVAL("sample-interval"),
        POLICY_INACTIVE("policy-inactive"),
        LOWEST_VARIANT("lowest-variant"),
        EVIDENCE_INCOMPLETE("evidence-incomplete"),
        RISK_CONFIRMING("risk-confirming"),
        MAX_RELOAD_ATTEMPTS("max-reload-attempts"),
        RELOAD_ONE_STEP("reload-one-step"),
        RELOAD_PENDING("reload-pending"),
        RELOAD_CONFIRMED("reload-confirmed"),
        RELOAD_TIMEOUT("reload-timeout"),
        RELOAD_ERROR("reload-error"),
        ROLLBACK_PENDING("rollback-pending"),
        ROLLBACK_CONFIRMED("rollback-confirmed"),
        ROLLBACK_TIMEOUT("rollback-timeout"),
        NO_ROLLBACK_PENDING("no-rollback-pending"),
        COOLDOWN("cooldown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private record Pending(
            PendingMode mode,
            String oldOption,
            String targetOption,
            PlaybackAutoContext.StreamKind streamKind,
            long resumePositionMs,
            long deadlineAtMs) {

        private Pending {
            mode = mode == null ? PendingMode.NONE : mode;
            oldOption = oldOption == null ? "" : oldOption;
            targetOption = targetOption == null ? "" : targetOption;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            resumePositionMs = Math.max(0, resumePositionMs);
            deadlineAtMs = Math.max(0, deadlineAtMs);
        }

        private Pending asRollback(long deadlineAtMs) {
            return new Pending(PendingMode.ROLLBACK, oldOption, targetOption,
                    streamKind, resumePositionMs, deadlineAtMs);
        }
    }

    private record RiskSample(
            long sampledAtMs,
            boolean hardRisk,
            boolean throughputRisk,
            boolean bufferRisk) {

        private RiskSample {
            sampledAtMs = Math.max(0, sampledAtMs);
        }
    }
}
