package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkGuardControllerTest {

    @Test
    public void sustainedSmallDeficitUsesContinuousLightTarget() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        assertFalse(evaluate(controller, 0, 30_000, true, -18, -18, -18, 10_000, 0, 1.00f).changed());

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 29_000, true, -18, -18, -18, 20_000, 0, 1.00f);

        assertTrue(decision.changed());
        assertEquals(0.997f, decision.targetSpeed(), 0.0001f);
        assertEquals(0.979f, decision.calculatedTargetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.ProtectionTier.LIGHT, decision.tier());
    }

    @Test
    public void oneSecondCooldownPreventsRateWriteChurn() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 30_000, true, -18, -18, -18, 10_000, 0, 1.00f);
        assertTrue(evaluate(controller, 10_000, 29_000, true, -18, -18, -18, 20_000, 0, 1.00f).changed());

        ExoNetworkGuardController.Decision held = evaluate(controller, 10_500, 28_900, true, -15, -15, -15, 20_500, 0, 0.997f);
        ExoNetworkGuardController.Decision next = evaluate(controller, 11_000, 28_800, true, -15, -15, -15, 21_000, 0, 0.997f);

        assertFalse(held.changed());
        assertTrue(next.changed());
        assertEquals(0.994f, next.targetSpeed(), 0.0001f);
    }

    @Test
    public void deepProtectionCalculatesBestTargetWithoutJumpingToFloor() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 26_000, true, -66, -66, -66, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 26_000, true, -66, -66, -66, 20_000, 0, 1.00f);

        assertTrue(decision.changed());
        assertEquals(0.934f, decision.supportedSpeed(), 0.0001f);
        assertEquals(0.931f, decision.calculatedTargetSpeed(), 0.0001f);
        assertEquals(0.994f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.ProtectionTier.DEEP, decision.tier());
        assertTrue(decision.targetSpeed() > ExoNetworkProtectionPolicy.AUTO_MIN_SPEED);
    }

    @Test
    public void deepProtectionConvergesNearSustainableRateInsteadOfWalkingToFloor() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        float capacity = 0.934f;
        float current = 1.00f;
        evaluateForCapacity(controller, 0, 24_000, 10_000, current, capacity);

        for (int i = 0; i < 40; i++) {
            ExoNetworkGuardController.Decision decision = evaluateForCapacity(controller, 10_000 + i * 1_000L, 24_000, 20_000 + i * 1_000L, current, capacity);
            if (decision.changed()) current = decision.targetSpeed();
        }

        assertTrue(current >= 0.93f);
        assertTrue(current <= 0.95f);
        assertTrue(current > ExoNetworkProtectionPolicy.AUTO_MIN_SPEED);
    }

    @Test
    public void floorIsApproachedOnlyWhenCalculatedCapacityRequiresIt() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        float capacity = 0.853f;
        float current = 1.00f;
        evaluateForCapacity(controller, 0, 24_000, 10_000, current, capacity);

        ExoNetworkGuardController.Decision first = evaluateForCapacity(controller, 3_000, 24_000, 13_000, current, capacity);
        assertTrue(first.changed());
        assertEquals(0.990f, first.targetSpeed(), 0.0001f);
        assertEquals(0.850f, first.calculatedTargetSpeed(), 0.0001f);
        current = first.targetSpeed();

        for (int i = 1; i < 40; i++) {
            ExoNetworkGuardController.Decision decision = evaluateForCapacity(controller, 3_000 + i * 1_000L, 24_000, 13_000 + i * 1_000L, current, capacity);
            if (decision.changed()) current = decision.targetSpeed();
        }

        assertTrue(current >= 0.85f);
        assertTrue(current <= 0.86f);
    }

    @Test
    public void deficitBelowConfiguredFloorStillAppliesMaximumRescue() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 30_000, true, -190, -190, -190, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 28_000, true, -190, -190, -190, 20_000, 0, 1.00f);

        assertTrue(decision.changed());
        assertTrue(decision.targetSpeed() < 1.00f);
        assertTrue(decision.targetSpeed() >= 0.85f);
        assertEquals(0.85f, decision.calculatedTargetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.PROTECT, decision.state());
        assertEquals(ExoNetworkGuardController.ProtectionTier.DEEP, decision.tier());
        assertTrue(decision.rampFeasible());
    }

    @Test
    public void deadlineFeasibilityUsesEmergencyRescueInsteadOfHoldingFullSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 21_000, true, -140, -140, -140, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 3_000, 21_000, true, -140, -140, -140, 13_000, 0, 1.00f);

        assertTrue(decision.changed());
        assertEquals(0.990f, decision.targetSpeed(), 0.0001f);
        assertEquals("deadline-emergency-rescue", decision.reason());
        assertTrue(decision.requiredSlewPerSecond() >= 0f);
    }

    @Test
    public void lightProtectionIsUsedFirstWhileReserveDeadlineIsFarAway() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 39_000, true, -66, -66, -66, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 39_000, true, -66, -66, -66, 20_000, 0, 1.00f);

        assertTrue(decision.changed());
        assertEquals(ExoNetworkGuardController.ProtectionTier.LIGHT, decision.tier());
        assertEquals(ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED, decision.calculatedTargetSpeed(), 0.0001f);
    }

    @Test
    public void recoveredCapacityReturnsGraduallyToUnitSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        controller.disrupt(0.93f);
        assertFalse(evaluate(controller, 0, 17_000, true, 70, 70, 70, 15_000, 0, 0.93f).changed());

        ExoNetworkGuardController.Decision decision = evaluate(controller, 8_000, 18_000, true, 70, 70, 70, 23_000, 0, 0.93f);

        assertTrue(decision.changed());
        assertEquals(0.932f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.RECOVERY, decision.state());
    }

    @Test
    public void fullBufferRecoversWithoutTreatingLoaderIdleAsNetworkFailure() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        controller.disrupt(0.93f);
        assertFalse(evaluate(controller, 0, 21_000, false, 0, 0, 0, 0, 0, 0.93f).changed());

        ExoNetworkGuardController.Decision decision = evaluate(controller, 12_000, 21_000, false, 0, 0, 0, 0, 0, 0.93f);

        assertTrue(decision.changed());
        assertEquals(0.932f, decision.targetSpeed(), 0.0001f);
    }

    @Test
    public void directNetworkEstimateCanOnlyMakeDecisionMoreConservative() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 30_000, true, -15, -15, -15, 10_000, 0, 1.00f, true, 0.975f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 29_000, true, -15, -15, -15, 20_000, 0, 1.00f, true, 0.975f);

        assertTrue(decision.changed());
        assertEquals(0.997f, decision.targetSpeed(), 0.0001f);
        assertEquals(0.975f, decision.supportedSpeed(), 0.0001f);
    }

    @Test
    public void timeToReserveUsesSafetyBufferInsteadOfWaitingForZero() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 60_000, true, -300, -300, -300, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 57_000, true, -300, -300, -300, 20_000, 0, 1.00f);

        assertEquals(190_000, decision.timeToEmptyMs());
        assertEquals(123_333, decision.timeToReserveMs());
        assertEquals(20_000, decision.safeBufferMs());
    }

    @Test
    public void rebufferWithoutFreshTrendDoesNotBlindlyChangeSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 5_000, true, 0, 0, 0, 0, 1, 1.00f);

        assertFalse(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.WARNING, decision.state());
    }

    @Test
    public void actualRebufferCanUseFiveSecondDecliningTrendImmediately() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(
                controller,
                10_000,
                23_000,
                true,
                -100,
                -100,
                -100,
                ExoNetworkGuardController.REBUFFER_MIN_TREND_WINDOW_MS,
                1,
                1.00f);

        assertTrue(decision.changed());
        assertEquals(0.990f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.PROTECT, decision.state());
    }

    @Test
    public void highBufferLoaderIdleNeverStartsProtection() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(
                controller,
                10_000,
                45_000,
                false,
                -500,
                -500,
                -500,
                20_000,
                0,
                1.00f);

        assertFalse(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.NORMAL, decision.state());
    }

    @Test
    public void ineligibleSessionRestoresNormalSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        ExoNetworkGuardController.Input input = new ExoNetworkGuardController.Input(0, false, true, true, true, 10_000, true, -20, 15_000, 0, 0.93f, 0.85f);

        ExoNetworkGuardController.Decision decision = controller.evaluate(input);

        assertTrue(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.NORMAL, decision.state());
    }

    private static ExoNetworkGuardController.Decision evaluateForCapacity(ExoNetworkGuardController controller, long nowMs, long bufferedMs, long windowMs, float currentSpeed, float capacity) {
        long slope = Math.round((capacity - currentSpeed) * 1_000f);
        return evaluate(controller, nowMs, bufferedMs, true, slope, slope, slope, windowMs, 0, currentSpeed);
    }

    private static ExoNetworkGuardController.Decision evaluate(ExoNetworkGuardController controller, long nowMs, long bufferedMs, boolean loading,
                                                                long slope, long fastSlope, long slowSlope, long windowMs, int rebufferCount, float currentSpeed) {
        return evaluate(controller, nowMs, bufferedMs, loading, slope, fastSlope, slowSlope, windowMs, rebufferCount, currentSpeed, false, 1f);
    }

    private static ExoNetworkGuardController.Decision evaluate(ExoNetworkGuardController controller, long nowMs, long bufferedMs, boolean loading,
                                                                long slope, long fastSlope, long slowSlope, long windowMs, int rebufferCount, float currentSpeed,
                                                                boolean networkKnown, float networkSupported) {
        return controller.evaluate(new ExoNetworkGuardController.Input(nowMs, true, true, true, loading, bufferedMs, windowMs > 0,
                slope, fastSlope, slowSlope, windowMs, rebufferCount, currentSpeed, 0.85f, 20_000, networkKnown, networkSupported));
    }
}
