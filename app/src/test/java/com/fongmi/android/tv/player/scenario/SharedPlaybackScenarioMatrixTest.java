package com.fongmi.android.tv.player.scenario;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackExperimentPolicy;
import com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy;

import org.junit.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SharedPlaybackScenarioMatrixTest {

    @Test
    public void catalogCoversEveryRequiredRiskAxis() {
        EnumSet<PlaybackScenarioMatrix.Axis> covered =
                EnumSet.noneOf(PlaybackScenarioMatrix.Axis.class);
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            covered.addAll(scenario.axes());
        }

        assertEquals(EnumSet.allOf(PlaybackScenarioMatrix.Axis.class), covered);
    }

    @Test
    public void scenariosProduceActiveIsolatedContextsForEveryKernel() {
        Set<String> traceIds = new HashSet<>();
        Set<Long> generations = new HashSet<>();

        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            PlaybackAutoContext exo = scenario.context(PlaybackAutoContext.Kernel.EXO);
            assertTrue(scenario.id().name(), exo.active());
            assertTrue(scenario.id().name(), traceIds.add(exo.session().traceId()));
            assertTrue(scenario.id().name(), generations.add(exo.session().generation()));

            for (PlaybackAutoContext.Kernel kernel : new PlaybackAutoContext.Kernel[]{
                    PlaybackAutoContext.Kernel.EXO,
                    PlaybackAutoContext.Kernel.MPV,
                    PlaybackAutoContext.Kernel.IJK}) {
                PlaybackAutoContext context = scenario.context(kernel);
                assertEquals(scenario.id().name(), exo.session(), context.session());
                assertEquals(scenario.id().name(), kernel, context.kernel().value());
                assertEquals(scenario.id().name(), scenario.decodeMode(),
                        context.decodeMode().value());
            }
        }
    }

    @Test
    public void scenarioCoreFactsAreUsableAtTheMatrixSnapshot() {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            PlaybackAutoContext context = scenario.context(PlaybackAutoContext.Kernel.EXO);
            String message = scenario.id().name();

            assertUsable(message, context.kernel());
            assertUsable(message, context.decodeMode());
            assertUsable(message, context.device().memoryPressure());
            assertUsable(message, context.device().memorySnapshot());
            assertUsable(message, context.device().thermalState());
            assertUsable(message, context.device().networkSnapshot());
            assertUsable(message, context.resource().protocol());
            assertUsable(message, context.resource().streamKind());
            assertUsable(message, context.resource().transferUnit());
            assertUsable(message, context.resource().manifest());
            assertUsable(message, context.path().playerPath());
            assertUsable(message, context.path().upstreamState());
            assertUsable(message, context.runtime().phase());
            assertUsable(message, context.runtime().bufferedDurationMs());
            assertUsable(message, context.runtime().mediaBitrateBitsPerSecond());
            assertUsable(message, context.media().videoTrack().width());
            assertUsable(message, context.media().videoTrack().height());
            assertUsable(message, context.media().videoTrack().averageBitrateBitsPerSecond());
            assertUsable(message, context.media().videoTrack().peakBitrateBitsPerSecond());
            assertUsable(message, context.media().decoder().videoDecodeMode());

            if (scenario.upstreamPath() != PlaybackAutoContext.PathKind.UNKNOWN) {
                assertUsable(message, context.path().upstreamPath());
            }
        }
    }

    @Test
    public void automaticOptimizationsStayOnWhileInternalExperimentsStayOff() {
        PlaybackExperimentPolicy.State stable = PlaybackExperimentPolicy.State.stable();

        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            assertTrue(scenario.id().name(),
                    scenario.context(PlaybackAutoContext.Kernel.EXO).active());
            for (PlaybackExperimentPolicy.Action action
                    : PlaybackExperimentPolicy.Action.values()) {
                if (action.risk()
                        == PlaybackExperimentPolicy.Risk.INTERNAL_EXPERIMENT) {
                    assertFalse(scenario.id() + ":" + action.id(), stable.allows(action));
                } else {
                    assertTrue(scenario.id() + ":" + action.id(), stable.allows(action));
                }
            }
        }
    }

    @Test
    public void experimentalDomainsRemainIsolatedAcrossTheMatrix() {
        assertOnlyDomainEnabled(PlaybackExperimentPolicy.Domain.EXO,
                new PlaybackExperimentPolicy.State(
                        PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                        true, true, false, false));
        assertOnlyDomainEnabled(PlaybackExperimentPolicy.Domain.MPV,
                new PlaybackExperimentPolicy.State(
                        PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                        true, false, true, false));
        assertOnlyDomainEnabled(PlaybackExperimentPolicy.Domain.IJK,
                new PlaybackExperimentPolicy.State(
                        PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                        true, false, false, true));
    }

    @Test
    public void nearFullDiskScenarioForbidsGrowthAndRequiresReclaim() {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(
                PlaybackScenarioMatrix.Id.NEAR_FULL_DISK_HLS);
        DiskCacheCapacityPolicy.Decision decision = DiskCacheCapacityPolicy.resolve(
                true,
                scenario.diskConfiguredBytes(),
                scenario.diskExistingBytes(),
                scenario.diskAvailableBytes(),
                scenario.diskTotalBytes());

        assertEquals(DiskCacheCapacityPolicy.State.RECLAIM_REQUIRED, decision.state());
        assertEquals(0, decision.newWriteBudgetBytes());
        assertTrue(decision.reclaimBytes() > 0);
        assertTrue(decision.reserveDeficitBytes() > 0);
    }

    @Test
    public void codecFailureCanUseBoundedRuntimeRecoveryWithoutUserOptIn() {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(
                PlaybackScenarioMatrix.Id.CODEC_FALSE_POSITIVE_DASH);
        PlaybackAutoContext context = scenario.context(PlaybackAutoContext.Kernel.EXO);
        PlaybackExperimentPolicy.Action action =
                PlaybackExperimentPolicy.Action.EXO_DECODER_RUNTIME_REBUILD;

        assertEquals(PlaybackAutoContext.HdrType.DOLBY_VISION,
                context.media().videoTrack().hdrType().value());
        assertTrue(scenario.codecFailure());
        assertTrue(PlaybackExperimentPolicy.State.stable().allows(action));
    }

    private static void assertOnlyDomainEnabled(
            PlaybackExperimentPolicy.Domain enabled,
            PlaybackExperimentPolicy.State state) {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            assertTrue(scenario.id().name(), scenario.context(kernel(enabled)).active());
            for (PlaybackExperimentPolicy.Action action
                    : PlaybackExperimentPolicy.Action.values()) {
                if (action.risk()
                        != PlaybackExperimentPolicy.Risk.INTERNAL_EXPERIMENT) {
                    assertTrue(action.id(), state.allows(action));
                } else if (action.domain()
                        == PlaybackExperimentPolicy.Domain.SHARED) {
                    assertTrue(action.id(), state.allows(action));
                } else {
                    assertEquals(action.id(), action.domain() == enabled,
                            state.allows(action));
                }
            }
        }
    }

    private static PlaybackAutoContext.Kernel kernel(
            PlaybackExperimentPolicy.Domain domain) {
        return switch (domain) {
            case EXO -> PlaybackAutoContext.Kernel.EXO;
            case MPV -> PlaybackAutoContext.Kernel.MPV;
            case IJK -> PlaybackAutoContext.Kernel.IJK;
            case SHARED -> PlaybackAutoContext.Kernel.UNKNOWN;
        };
    }

    private static void assertUsable(
            String message,
            PlaybackAutoContext.Fact<?> fact) {
        assertTrue(message, fact.isUsable(PlaybackScenarioMatrix.NOW_MS));
    }
}
