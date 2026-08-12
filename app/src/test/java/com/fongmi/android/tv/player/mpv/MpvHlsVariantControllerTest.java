package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackThroughputHistory;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvHlsVariantControllerTest {

    private static final MpvHlsVariantPolicy.Variant V2 = variant(2_000_000L);
    private static final MpvHlsVariantPolicy.Variant V4 = variant(4_000_000L);
    private static final MpvHlsVariantPolicy.Variant V6 = variant(6_000_000L);
    private static final MpvHlsVariantPolicy.Variant V8 = variant(8_000_000L);
    private static final MpvHlsVariantPolicy.Variant V12 = variant(12_000_000L);
    private static final List<MpvHlsVariantPolicy.Variant> LADDER =
            List.of(V2, V4, V6, V8, V12);

    @Test
    public void initialCeilingCanBeStagedAndRestoredAfterContextRebuild() {
        Fixture fixture = readyController("initial", 1);

        MpvHlsVariantController.Decision restore =
                fixture.controller.evaluateInitial(
                        fixture.token,
                        fixture.token,
                        initialAssessment());

        assertEquals(MpvHlsVariantController.Action.APPLY_INITIAL,
                restore.action());
        assertEquals(MpvHlsVariantController.Reason.CONTEXT_RESTORE,
                restore.reason());
        assertEquals("15000000", restore.targetOption());
    }

    @Test
    public void vodRiskNeedsThreeSamplesOverTenSecondsAndKeepsPosition() {
        Fixture fixture = readyController("vod", 2);

        assertFalse(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 123_456, 1),
                0).requestsApply());
        assertFalse(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 123_456, 2),
                5_000).requestsApply());
        MpvHlsVariantController.Decision decision =
                fixture.controller.evaluateRuntime(
                        fixture.token, fixture.token,
                        risk(V12, PlaybackAutoContext.StreamKind.VOD, 123_456, 3),
                        10_000);

        assertEquals(MpvHlsVariantController.Action.RELOAD_LOWER,
                decision.action());
        assertEquals("8000000", decision.targetOption());
        assertEquals(123_456L, decision.resumePositionMs());
        assertTrue(decision.preservesVodPosition());
    }

    @Test
    public void liveRiskReloadsAtDefaultLiveEdge() {
        Fixture fixture = readyController("live", 3);

        MpvHlsVariantController.Decision decision = confirm(
                fixture,
                V12,
                PlaybackAutoContext.StreamKind.LIVE,
                88_000,
                0);

        assertEquals(MpvHlsVariantController.Action.RELOAD_LOWER,
                decision.action());
        assertEquals(0, decision.resumePositionMs());
        assertFalse(decision.preservesVodPosition());
    }

    @Test
    public void successfulReloadWaitsForReadyThenStartsThirtySecondCooldown() {
        Fixture fixture = readyController("success", 4);
        MpvHlsVariantController.Decision decision = confirm(
                fixture, V12, PlaybackAutoContext.StreamKind.VOD, 50_000, 0);
        assertTrue(fixture.controller.beginApply(
                fixture.token, decision, 10_000));
        fixture.controller.completeApply(
                fixture.token, decision, true, false, 10_000);

        assertEquals(MpvHlsVariantController.PendingMode.DOWNGRADE,
                fixture.controller.snapshot().pendingMode());
        MpvHlsVariantController.Completion completion =
                fixture.controller.onPlaybackReady(fixture.token, 11_000);
        assertTrue(completion.downgradeConfirmed());
        assertEquals(41_000L, fixture.controller.snapshot().cooldownUntilMs());

        MpvHlsVariantController.Decision cooldown =
                fixture.controller.evaluateRuntime(
                        fixture.token, fixture.token,
                        risk(V8, PlaybackAutoContext.StreamKind.VOD, 51_000, 4),
                        20_000);
        assertEquals(MpvHlsVariantController.Reason.COOLDOWN,
                cooldown.reason());
    }

    @Test
    public void contextRebuildDuringPendingReloadKeepsTargetForRestaging() {
        Fixture fixture = readyController("pending", 5);
        MpvHlsVariantController.Decision downgrade = confirm(
                fixture, V12, PlaybackAutoContext.StreamKind.VOD, 55_000, 0);
        assertTrue(fixture.controller.beginApply(
                fixture.token, downgrade, 10_000));
        fixture.controller.completeApply(
                fixture.token, downgrade, true, false, 10_000);

        MpvHlsVariantController.Decision restore =
                fixture.controller.evaluateInitial(
                        fixture.token, fixture.token, initialAssessment());

        assertEquals(MpvHlsVariantController.Reason.ACTION_PENDING,
                restore.reason());
        assertEquals("8000000",
                fixture.controller.snapshot().targetOption());
        assertEquals(MpvHlsVariantController.PendingMode.DOWNGRADE,
                fixture.controller.snapshot().pendingMode());
    }

    @Test
    public void reloadErrorRestoresOldCeilingAndUsesFailureCooldown() {
        Fixture fixture = readyController("rollback", 6);
        MpvHlsVariantController.Decision downgrade = confirm(
                fixture, V12, PlaybackAutoContext.StreamKind.VOD, 60_000, 0);
        assertTrue(fixture.controller.beginApply(
                fixture.token, downgrade, 10_000));
        fixture.controller.completeApply(
                fixture.token, downgrade, true, false, 10_000);

        MpvHlsVariantController.Decision rollback =
                fixture.controller.requestRollbackOnError(fixture.token);
        assertEquals(MpvHlsVariantController.Action.ROLLBACK,
                rollback.action());
        assertEquals("15000000", rollback.targetOption());
        assertEquals(60_000L, rollback.resumePositionMs());
        assertTrue(fixture.controller.beginApply(
                fixture.token, rollback, 11_000));
        fixture.controller.completeApply(
                fixture.token, rollback, true, false, 11_000);

        MpvHlsVariantController.Completion completion =
                fixture.controller.onPlaybackReady(fixture.token, 12_000);
        assertTrue(completion.rollbackConfirmed());
        assertEquals("15000000", completion.option());
        assertEquals(72_000L, fixture.controller.snapshot().cooldownUntilMs());
    }

    @Test
    public void downgradeReadyTimeoutRequestsRollbackAndRollbackCanAlsoExpire() {
        Fixture fixture = readyController("timeout", 7);
        MpvHlsVariantController.Decision downgrade = confirm(
                fixture, V12, PlaybackAutoContext.StreamKind.VOD, 70_000, 0);
        assertTrue(fixture.controller.beginApply(
                fixture.token, downgrade, 10_000));
        fixture.controller.completeApply(
                fixture.token, downgrade, true, false, 10_000);

        MpvHlsVariantController.Decision rollback =
                fixture.controller.evaluateRuntime(
                        fixture.token, fixture.token,
                        risk(V8, PlaybackAutoContext.StreamKind.VOD, 70_000, 4),
                        30_000);
        assertEquals(MpvHlsVariantController.Action.ROLLBACK,
                rollback.action());
        assertEquals(MpvHlsVariantController.Reason.RELOAD_TIMEOUT,
                rollback.reason());
        assertTrue(fixture.controller.beginApply(
                fixture.token, rollback, 30_000));
        fixture.controller.completeApply(
                fixture.token, rollback, true, false, 30_000);

        MpvHlsVariantController.Decision expired =
                fixture.controller.evaluateRuntime(
                        fixture.token, fixture.token,
                        risk(V12, PlaybackAutoContext.StreamKind.VOD, 70_000, 5),
                        50_000);
        assertEquals(MpvHlsVariantController.Reason.ROLLBACK_TIMEOUT,
                expired.reason());
        assertEquals(MpvHlsVariantController.PendingMode.NONE,
                fixture.controller.snapshot().pendingMode());
    }

    @Test
    public void sessionAllowsAtMostThreeDowngradeAttempts() {
        Fixture fixture = readyController("attempts", 8);
        long base = 0;
        MpvHlsVariantPolicy.Variant selected = V12;
        for (int attempt = 0; attempt < 3; attempt++) {
            MpvHlsVariantController.Decision decision = confirm(
                    fixture,
                    selected,
                    PlaybackAutoContext.StreamKind.VOD,
                    80_000,
                    base);
            assertEquals(MpvHlsVariantController.Action.RELOAD_LOWER,
                    decision.action());
            assertTrue(fixture.controller.beginApply(
                    fixture.token, decision, base + 10_000));
            fixture.controller.completeApply(
                    fixture.token, decision, true, false, base + 10_000);
            fixture.controller.onPlaybackReady(
                    fixture.token, base + 10_001);
            selected = decision.targetBitsPerSecond() == 8_000_000L
                    ? V8 : decision.targetBitsPerSecond() == 6_000_000L
                    ? V6 : V4;
            base = fixture.controller.snapshot().cooldownUntilMs();
        }

        MpvHlsVariantController.Decision blocked = confirm(
                fixture,
                V4,
                PlaybackAutoContext.StreamKind.VOD,
                80_000,
                base);
        assertEquals(MpvHlsVariantController.Action.HOLD, blocked.action());
        assertEquals(MpvHlsVariantController.Reason.MAX_RELOAD_ATTEMPTS,
                blocked.reason());
        assertEquals(3, fixture.controller.snapshot().reloadAttempts());
    }

    @Test
    public void staleSessionCannotApplyOrCompleteActions() {
        Fixture fixture = readyController("current", 9);
        PlaybackAutoContext.SessionToken stale = token("stale", 8);

        MpvHlsVariantController.Decision decision =
                fixture.controller.evaluateRuntime(
                        fixture.token,
                        stale,
                        risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 1),
                        0);

        assertEquals(MpvHlsVariantController.Reason.STALE_SESSION,
                decision.reason());
        assertFalse(fixture.controller.beginApply(stale, decision, 0));
    }

    @Test
    public void callbacksFasterThanFiveSecondsDoNotCountAsIndependentSamples() {
        Fixture fixture = readyController("sample-interval", 10);

        assertFalse(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 1),
                0).requestsApply());
        MpvHlsVariantController.Decision skipped =
                fixture.controller.evaluateRuntime(
                        fixture.token, fixture.token,
                        risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 2),
                        1_000);
        assertEquals(MpvHlsVariantController.Reason.SAMPLE_INTERVAL,
                skipped.reason());
        assertEquals(1, fixture.controller.snapshot().riskSamples());

        assertFalse(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 3),
                5_000).requestsApply());
        assertTrue(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 4),
                10_000).requestsApply());
    }

    @Test
    public void riskSamplesOlderThanTenSecondsDoNotAccumulate() {
        Fixture fixture = readyController("sliding-window", 11);

        assertFalse(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 1),
                0).requestsApply());
        assertFalse(fixture.controller.evaluateRuntime(
                fixture.token, fixture.token,
                risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 2),
                10_000).requestsApply());
        MpvHlsVariantController.Decision stillWaiting =
                fixture.controller.evaluateRuntime(
                        fixture.token, fixture.token,
                        risk(V12, PlaybackAutoContext.StreamKind.VOD, 0, 3),
                        20_000);

        assertFalse(stillWaiting.requestsApply());
        assertEquals(MpvHlsVariantController.Reason.RISK_CONFIRMING,
                stillWaiting.reason());
        assertEquals(2, fixture.controller.snapshot().riskSamples());
    }

    private static Fixture readyController(String trace, long generation) {
        MpvHlsVariantController controller = new MpvHlsVariantController();
        PlaybackAutoContext.SessionToken token = token(trace, generation);
        controller.beginSession(token);
        MpvHlsVariantController.Decision initial = controller.evaluateInitial(
                token, token, initialAssessment());
        assertTrue(controller.beginApply(token, initial, 0));
        controller.completeApply(token, initial, true, false, 0);
        controller.onPlaybackReady(token, 0);
        return new Fixture(controller, token);
    }

    private static MpvHlsVariantController.Decision confirm(
            Fixture fixture,
            MpvHlsVariantPolicy.Variant selected,
            PlaybackAutoContext.StreamKind streamKind,
            long positionMs,
            long baseMs) {
        MpvHlsVariantController.Decision decision = null;
        for (int i = 0; i < 3; i++) {
            decision = fixture.controller.evaluateRuntime(
                    fixture.token,
                    fixture.token,
                    risk(selected, streamKind, positionMs, i + 1),
                    baseMs + i * 5_000L);
        }
        return decision;
    }

    private static MpvHlsVariantController.RuntimeObservation risk(
            MpvHlsVariantPolicy.Variant selected,
            PlaybackAutoContext.StreamKind streamKind,
            long positionMs,
            long underrunCount) {
        return new MpvHlsVariantController.RuntimeObservation(
                true,
                true,
                true,
                PlaybackAutoContext.Protocol.HLS,
                true,
                streamKind,
                true,
                LADDER,
                selected,
                true,
                underrunCount,
                0,
                false,
                true,
                5_000,
                1_000_000L,
                true,
                positionMs);
    }

    private static MpvHlsVariantPolicy.InitialAssessment initialAssessment() {
        return MpvHlsVariantPolicy.resolveInitial(
                true,
                true,
                true,
                PlaybackThroughputHistory.Match.unavailable(
                        PlaybackThroughputHistory.Reason.NO_MATCH));
    }

    private static MpvHlsVariantPolicy.Variant variant(long bitrate) {
        return new MpvHlsVariantPolicy.Variant(
                bitrate, Math.max(1, bitrate - 500_000L), 1920, 1080);
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

    private record Fixture(
            MpvHlsVariantController controller,
            PlaybackAutoContext.SessionToken token) {
    }
}
