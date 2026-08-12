package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkBufferControllerTest {

    private static final IjkBufferPolicy.Config FOUR =
            new IjkBufferPolicy.Config(4, 100, 300, 1_000);
    private static final IjkBufferPolicy.Config EIGHT =
            IjkBufferPolicy.safeInitialConfig();
    private static final IjkBufferPolicy.Config FIFTEEN =
            new IjkBufferPolicy.Config(15, 100, 1_000, 5_000);

    @Test
    public void initialDecisionStagesWithoutReload() {
        IjkBufferController controller = controller("initial", 1, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        IjkBufferController.Decision decision = controller.stageInitial(
                token, token, policy(EIGHT, IjkBufferPolicy.Reason.MEMORY_UNKNOWN));

        assertEquals(IjkBufferController.Action.STAGE, decision.action());
        assertEquals(IjkBufferController.Reason.INITIAL_STAGE, decision.reason());
        assertEquals(EIGHT, controller.snapshot().stagedConfig());
    }

    @Test
    public void staleSessionCannotMutateController() {
        IjkBufferController controller = controller("current", 2, 0);
        PlaybackAutoContext.SessionToken current = controller.snapshot().session();
        PlaybackAutoContext.SessionToken stale = token("stale", 1);

        IjkBufferController.Decision decision = controller.evaluate(
                current, stale, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), EIGHT,
                IjkBufferController.Trigger.MEMORY, false, true, 0, 10);

        assertEquals(IjkBufferController.Reason.STALE_SESSION, decision.reason());
        assertEquals(0, controller.snapshot().evaluations());
    }

    @Test
    public void earlyManifestShrinkRequestsOneReload() {
        IjkBufferController controller = controller("manifest", 3, 1_000);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        IjkBufferController.Decision decision = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.LOW_LATENCY_CADENCE), EIGHT,
                IjkBufferController.Trigger.MANIFEST, false, false, 0, 5_000);

        assertTrue(decision.requestsReload());
        assertEquals(IjkBufferController.Reason.EARLY_SCENE_RELOAD,
                decision.reason());
    }

    @Test
    public void healthyExpansionIsOnlyStaged() {
        IjkBufferController controller = controller("healthy", 4, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        IjkBufferController.Decision decision = controller.evaluate(
                token, token, policy(FIFTEEN,
                        IjkBufferPolicy.Reason.MEDIA_DEMAND), EIGHT,
                IjkBufferController.Trigger.RUNTIME, false, true, 0, 30_000);

        assertEquals(IjkBufferController.Action.STAGE, decision.action());
        assertEquals(IjkBufferController.Reason.HEALTHY_EXPANSION_DEFERRED,
                decision.reason());
    }

    @Test
    public void newRebufferCanExpandDuringExistingStall() {
        IjkBufferController controller = controller("rebuffer", 5, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        IjkBufferController.Decision decision = controller.evaluate(
                token, token, policy(FIFTEEN,
                        IjkBufferPolicy.Reason.REBUFFER_HEADROOM), EIGHT,
                IjkBufferController.Trigger.RUNTIME, true, true, 1, 30_000);

        assertTrue(decision.requestsReload());
        assertTrue(decision.newRebuffer());
        assertEquals(IjkBufferController.Reason.REBUFFER_RELOAD,
                decision.reason());
    }

    @Test
    public void cooldownBlocksSecondNoncriticalReload() {
        IjkBufferController controller = controller("cooldown", 6, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision first = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.LOW_LATENCY_CADENCE), EIGHT,
                IjkBufferController.Trigger.MANIFEST, false, false, 0, 1_000);
        apply(controller, token, first, 1_000);

        IjkBufferController.Decision second = controller.evaluate(
                token, token, policy(FIFTEEN,
                        IjkBufferPolicy.Reason.REBUFFER_HEADROOM), FOUR,
                IjkBufferController.Trigger.RUNTIME, true, true, 1, 5_000);

        assertEquals(IjkBufferController.Action.HOLD, second.action());
        assertEquals(IjkBufferController.Reason.RELOAD_COOLDOWN,
                second.reason());
        assertEquals(26_000, second.cooldownRemainingMs());
    }

    @Test
    public void criticalShrinkBypassesCooldown() {
        IjkBufferController controller = controller("critical", 7, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision first = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.LOW_LATENCY_CADENCE), EIGHT,
                IjkBufferController.Trigger.MANIFEST, false, false, 0, 1_000);
        apply(controller, token, first, 1_000);

        IjkBufferController.Decision critical = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                IjkBufferController.Trigger.MEMORY, false, true, 0, 2_000);

        assertTrue(critical.requestsReload());
        assertEquals(IjkBufferController.Reason.SAFETY_RELOAD,
                critical.reason());
    }

    @Test
    public void failedSafetyReloadRestoresOldStageAndCoolsSameTarget() {
        IjkBufferController controller = controller("failed-safety", 12, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision first = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                IjkBufferController.Trigger.MEMORY, false, true, 0, 1_000);

        assertTrue(controller.beginApply(token, first));
        controller.completeApply(token, first, false, 1_000);
        assertEquals(FIFTEEN, controller.snapshot().stagedConfig());

        IjkBufferController.Decision repeated = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                IjkBufferController.Trigger.MEMORY, false, true, 0, 2_000);

        assertEquals(IjkBufferController.Action.HOLD, repeated.action());
        assertEquals(IjkBufferController.Reason.RELOAD_COOLDOWN,
                repeated.reason());
        assertEquals(29_000, repeated.cooldownRemainingMs());
        assertEquals(FIFTEEN, controller.snapshot().stagedConfig());
    }

    @Test
    public void moderateMemoryDoesNotReloadWhenCapacityIsAlreadySafe() {
        IjkBufferController controller = controller("moderate", 13, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferPolicy.Config largerWater =
                new IjkBufferPolicy.Config(8, 100, 1_000, 5_000);

        IjkBufferController.Decision decision = controller.evaluate(
                token, token, policy(largerWater,
                        IjkBufferPolicy.Reason.MODERATE_MEMORY), EIGHT,
                IjkBufferController.Trigger.MEMORY, false, true, 0, 1_000);

        assertEquals(IjkBufferController.Action.STAGE, decision.action());
        assertEquals(IjkBufferController.Reason.HEALTHY_EXPANSION_DEFERRED,
                decision.reason());
    }

    @Test
    public void highLagIsSafetyEvenWhenPolicyReasonIsMemoryUnknown() {
        IjkBufferController controller = controller("lag", 8, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferPolicy.Decision lag = new IjkBufferPolicy.Decision(true, FOUR,
                IjkBufferPolicy.Reason.MEMORY_UNKNOWN, 8, true, 1_200, 0);

        IjkBufferController.Decision decision = controller.evaluate(
                token, token, lag, FIFTEEN,
                IjkBufferController.Trigger.RUNTIME, false, true, 0, 60_000);

        assertTrue(decision.requestsReload());
        assertEquals(IjkBufferController.Reason.SAFETY_RELOAD,
                decision.reason());
    }

    @Test
    public void applyInProgressAndReloadLimitPreventLoops() {
        IjkBufferController controller = controller("limit", 9, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        for (int attempt = 0; attempt < IjkBufferController.MAX_RELOAD_ATTEMPTS;
             attempt++) {
            long now = attempt * IjkBufferController.RELOAD_COOLDOWN_MS;
            IjkBufferController.Decision decision = controller.evaluate(
                    token, token, policy(FOUR,
                            IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                    IjkBufferController.Trigger.MEMORY, false, true, 0,
                    now);
            assertTrue(decision.requestsReload());
            assertTrue(controller.beginApply(token, decision));
            IjkBufferController.Decision inProgress = controller.evaluate(
                    token, token, policy(FOUR,
                            IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                    IjkBufferController.Trigger.MEMORY, false, true, 0,
                    now);
            assertEquals(IjkBufferController.Reason.APPLY_IN_PROGRESS,
                    inProgress.reason());
            controller.completeApply(token, decision, false,
                    now);
        }

        IjkBufferController.Decision limited = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                IjkBufferController.Trigger.MEMORY, false, true, 0,
                IjkBufferController.MAX_RELOAD_ATTEMPTS
                        * IjkBufferController.RELOAD_COOLDOWN_MS);
        assertEquals(IjkBufferController.Reason.RELOAD_LIMIT, limited.reason());
    }

    @Test
    public void newSessionClearsPriorReloadAndRebufferState() {
        IjkBufferController controller = controller("first", 10, 0);
        PlaybackAutoContext.SessionToken first = controller.snapshot().session();
        IjkBufferController.Decision decision = controller.evaluate(
                first, first, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                IjkBufferController.Trigger.MEMORY, false, true, 4, 1_000);
        apply(controller, first, decision, 1_000);

        PlaybackAutoContext.SessionToken second = token("second", 11);
        controller.beginSession(second, 2_000);

        assertEquals(0, controller.snapshot().reloadAttempts());
        assertEquals(0, controller.snapshot().lastRebufferCount());
        assertFalse(controller.endSession(first));
        assertTrue(controller.endSession(second));
    }

    @Test
    public void realtimeRecoveryUsesSameReloadLaneWithoutChangingConfig() {
        IjkBufferController controller = controller("realtime", 14, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        IjkBufferController.Decision decision =
                controller.requestRealtimeRecovery(
                        token, token, FOUR, 10_000);

        assertTrue(decision.requestsReload());
        assertEquals(FOUR, decision.appliedConfig());
        assertEquals(FOUR, decision.targetConfig());
        assertEquals(IjkBufferController.Reason.REALTIME_RECOVERY_RELOAD,
                decision.reason());
    }

    @Test
    public void realtimeRecoverySharesCooldownWithBufferReload() {
        IjkBufferController controller = controller("shared-cooldown", 15, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision buffer = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.LOW_LATENCY_CADENCE), EIGHT,
                IjkBufferController.Trigger.MANIFEST, false, false, 0,
                1_000);
        apply(controller, token, buffer, 1_000);

        IjkBufferController.Decision realtime =
                controller.requestRealtimeRecovery(
                        token, token, FOUR, 5_000);

        assertEquals(IjkBufferController.Action.HOLD, realtime.action());
        assertEquals(IjkBufferController.Reason.RELOAD_COOLDOWN,
                realtime.reason());
        assertEquals(26_000, realtime.cooldownRemainingMs());
    }

    @Test
    public void criticalMemoryCanBypassRealtimeRecoveryCooldown() {
        IjkBufferController controller = controller("realtime-safety", 18, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision realtime =
                controller.requestRealtimeRecovery(
                        token, token, FIFTEEN, 1_000);
        apply(controller, token, realtime, 1_000);

        IjkBufferController.Decision critical = controller.evaluate(
                token, token, policy(FOUR,
                        IjkBufferPolicy.Reason.CRITICAL_MEMORY), FIFTEEN,
                IjkBufferController.Trigger.MEMORY, false, true, 0,
                2_000);

        assertTrue(critical.requestsReload());
        assertEquals(IjkBufferController.Reason.SAFETY_RELOAD,
                critical.reason());
    }

    @Test
    public void realtimeRecoverySharesApplyInProgressAndAttemptLimit() {
        IjkBufferController controller = controller("shared-limit", 16, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        for (int attempt = 0;
             attempt < IjkBufferController.MAX_RELOAD_ATTEMPTS;
             attempt++) {
            long now = attempt * IjkBufferController.RELOAD_COOLDOWN_MS;
            IjkBufferController.Decision decision =
                    controller.requestRealtimeRecovery(
                            token, token, FOUR, now);
            assertTrue(decision.requestsReload());
            assertTrue(controller.beginApply(token, decision));
            assertEquals(IjkBufferController.Reason.APPLY_IN_PROGRESS,
                    controller.requestRealtimeRecovery(
                            token, token, FOUR, now).reason());
            controller.completeApply(token, decision, true, now);
        }

        IjkBufferController.Decision limited =
                controller.requestRealtimeRecovery(
                        token, token, FOUR,
                        IjkBufferController.MAX_RELOAD_ATTEMPTS
                                * IjkBufferController.RELOAD_COOLDOWN_MS);
        assertEquals(IjkBufferController.Reason.RELOAD_LIMIT,
                limited.reason());
    }

    @Test
    public void staleRealtimeRecoveryCannotSpendCurrentSessionBudget() {
        IjkBufferController controller = controller("realtime-stale", 17, 0);
        PlaybackAutoContext.SessionToken current = controller.snapshot().session();
        PlaybackAutoContext.SessionToken stale = token("old", 16);

        IjkBufferController.Decision decision =
                controller.requestRealtimeRecovery(
                        current, stale, FOUR, 10_000);

        assertEquals(IjkBufferController.Reason.STALE_SESSION,
                decision.reason());
        assertEquals(0, controller.snapshot().reloadAttempts());
    }

    @Test
    public void decodePressureUsesSameReloadLaneWithoutChangingBuffer() {
        IjkBufferController controller = controller("decode", 19, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        IjkBufferController.Decision decision =
                controller.requestDecodePressureReload(
                        token, token, EIGHT, 10_000);

        assertTrue(decision.requestsReload());
        assertEquals(EIGHT, decision.appliedConfig());
        assertEquals(EIGHT, decision.targetConfig());
        assertEquals(IjkBufferController.Reason.DECODE_PRESSURE_RELOAD,
                decision.reason());
    }

    @Test
    public void decodePressureSharesCooldownWithRealtimeRecovery() {
        IjkBufferController controller = controller("decode-cooldown", 20, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision realtime =
                controller.requestRealtimeRecovery(
                        token, token, FOUR, 1_000);
        apply(controller, token, realtime, 1_000);

        IjkBufferController.Decision decode =
                controller.requestDecodePressureReload(
                        token, token, FOUR, 5_000);

        assertEquals(IjkBufferController.Reason.RELOAD_COOLDOWN,
                decode.reason());
        assertEquals(26_000, decode.cooldownRemainingMs());
    }

    @Test
    public void decodePressureSharesAttemptLimitWithAllIjkReloads() {
        IjkBufferController controller = controller("decode-limit", 21, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();

        for (int attempt = 0;
             attempt < IjkBufferController.MAX_RELOAD_ATTEMPTS;
             attempt++) {
            long now = attempt * IjkBufferController.RELOAD_COOLDOWN_MS;
            IjkBufferController.Decision decision =
                    controller.requestDecodePressureReload(
                            token, token, FOUR, now);
            apply(controller, token, decision, now);
        }

        IjkBufferController.Decision limited =
                controller.requestRealtimeRecovery(
                        token, token, FOUR,
                        IjkBufferController.MAX_RELOAD_ATTEMPTS
                                * IjkBufferController.RELOAD_COOLDOWN_MS);
        assertEquals(IjkBufferController.Reason.RELOAD_LIMIT,
                limited.reason());
    }

    @Test
    public void disabledExperimentStagesReloadWithoutSpendingReloadBudget() {
        IjkBufferController controller = controller("experiment-stage", 22, 0);
        PlaybackAutoContext.SessionToken token = controller.snapshot().session();
        IjkBufferController.Decision reload = controller.evaluate(
                token, token, policy(FIFTEEN,
                        IjkBufferPolicy.Reason.REBUFFER_HEADROOM), EIGHT,
                IjkBufferController.Trigger.RUNTIME, true, true, 1,
                30_000);

        IjkBufferController.Decision deferred =
                controller.deferExperimentalReload(token, reload);

        assertEquals(IjkBufferController.Action.STAGE, deferred.action());
        assertEquals(IjkBufferController.Reason.EXPERIMENT_DISABLED,
                deferred.reason());
        assertEquals(EIGHT, deferred.appliedConfig());
        assertEquals(FIFTEEN, deferred.targetConfig());
        assertTrue(deferred.newRebuffer());
        assertEquals(FIFTEEN, controller.snapshot().stagedConfig());
        assertEquals(0, controller.snapshot().reloadAttempts());
        assertFalse(controller.snapshot().applyInProgress());
    }

    @Test
    public void staleSessionCannotStageDisabledExperimentalReload() {
        IjkBufferController controller = controller("experiment-current", 23, 0);
        PlaybackAutoContext.SessionToken current = controller.snapshot().session();
        PlaybackAutoContext.SessionToken stale = token("experiment-stale", 22);
        IjkBufferController.Decision reload = controller.evaluate(
                current, current, policy(FIFTEEN,
                        IjkBufferPolicy.Reason.REBUFFER_HEADROOM), EIGHT,
                IjkBufferController.Trigger.RUNTIME, true, true, 1,
                30_000);

        IjkBufferController.Decision deferred =
                controller.deferExperimentalReload(stale, reload);

        assertEquals(IjkBufferController.Action.HOLD, deferred.action());
        assertEquals(IjkBufferController.Reason.EXPERIMENT_DISABLED,
                deferred.reason());
        assertEquals(EIGHT, controller.snapshot().stagedConfig());
        assertEquals(0, controller.snapshot().reloadAttempts());
    }

    private static IjkBufferController controller(
            String trace,
            long generation,
            long startedAt) {
        IjkBufferController controller = new IjkBufferController();
        controller.beginSession(token(trace, generation), startedAt);
        return controller;
    }

    private static void apply(
            IjkBufferController controller,
            PlaybackAutoContext.SessionToken token,
            IjkBufferController.Decision decision,
            long now) {
        assertTrue(controller.beginApply(token, decision));
        controller.completeApply(token, decision, true, now);
    }

    private static IjkBufferPolicy.Decision policy(
            IjkBufferPolicy.Config config,
            IjkBufferPolicy.Reason reason) {
        return new IjkBufferPolicy.Decision(true, config, reason,
                15, false, -1, 0);
    }

    private static PlaybackAutoContext.SessionToken token(
            String trace,
            long generation) {
        long seed = Math.abs((trace == null ? 0 : trace.hashCode()) * 31L
                + generation);
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(seed + 1, 36)
                        + "-" + Long.toString(generation + 1, 36),
                generation);
    }
}
