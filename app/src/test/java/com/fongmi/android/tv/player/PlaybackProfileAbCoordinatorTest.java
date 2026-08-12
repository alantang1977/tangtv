package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackProfileAbCoordinatorTest {

    private static final String DEVICE =
            PlaybackProfileAbIdentity.deviceDigest(
                    "fingerprint", 10, "1", "media3");

    @Test
    public void completeThirtySecondSessionCommitsOneSample() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        List<PlaybackProfileAbCoordinator.LogEntry> logs =
                new ArrayList<>();
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(store, logs::add);
        PlaybackAutoContext.SessionToken session = token("ab-complete", 1);

        assertTrue(coordinator.beginSession(
                session, start(1), 0));
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 700), 1),
                1_000));
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 700), 1),
                31_500));
        assertTrue(coordinator.endSession(
                session, end(1, PlaybackProfileAbPolicy.Arm.AUTO),
                31_500, 10_000));

        PlaybackProfileAbStore.Snapshot snapshot = store.snapshot(10_000);
        assertEquals(1, snapshot.automaticSampleCount());
        PlaybackProfileAbStore.Sample sample =
                snapshot.groups().get(0).automatic().get(0);
        assertEquals(700, sample.firstFrameMs());
        assertTrue(sample.activePlaybackMs() >= 30_000);
        assertEquals(180L << 20, sample.peakPssBytes());
        assertFalse(sample.errorObserved());
        assertTrue(logs.stream().allMatch(log ->
                !log.message().contains("fingerprint")
                        && !log.message().contains("decoder-name")));
    }

    @Test
    public void incompleteFactsCanBecomeReadyBeforeMetricsAreCommitted() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(store, null);
        PlaybackAutoContext.SessionToken session = token("ab-late", 2);

        assertTrue(coordinator.beginSession(session, start(2), 0));
        assertTrue(coordinator.observe(
                session,
                runtime(incompleteContext(session), observation(
                        PlaybackAutoContext.PlaybackPhase.PREPARING, -1), 2),
                100));
        assertTrue(coordinator.snapshot().active());
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 600), 2),
                1_000));
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 600), 2),
                31_500));
        assertTrue(coordinator.endSession(
                session, end(2, PlaybackProfileAbPolicy.Arm.AUTO),
                31_500, 11_000));
        assertEquals(1, store.snapshot(11_000).automaticSampleCount());
    }

    @Test
    public void userInteractionGenerationAndConfoundsDiscardSession() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(store, null);
        PlaybackAutoContext.SessionToken seek = token("ab-seek", 3);
        coordinator.beginSession(seek, start(3), 0);
        coordinator.observe(seek, runtime(context(seek), observation(
                PlaybackAutoContext.PlaybackPhase.READY, 500), 3), 1_000);
        coordinator.invalidate(
                seek,
                PlaybackProfileAbCoordinator.InvalidationReason.USER_SEEK);
        assertFalse(coordinator.endSession(
                seek, end(3, PlaybackProfileAbPolicy.Arm.AUTO),
                40_000, 10_000));

        PlaybackAutoContext.SessionToken generation =
                token("ab-generation", 4);
        coordinator.beginSession(generation, start(4), 0);
        assertFalse(coordinator.observe(
                generation,
                runtime(context(generation), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 500), 5),
                1_000));

        PlaybackAutoContext.SessionToken confound =
                token("ab-confound", 5);
        coordinator.beginSession(confound, start(5), 0);
        PlaybackProfileAbCoordinator.RuntimeInput input = runtime(
                context(confound),
                observation(PlaybackAutoContext.PlaybackPhase.READY, 500),
                5);
        assertFalse(coordinator.observe(
                confound,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        input.allowed(), input.arm(),
                        input.experimentGeneration(), input.context(),
                        input.observation(), input.playbackIntended(),
                        true, false),
                1_000));

        assertEquals(0, store.snapshot(10_000).automaticSampleCount());
    }

    @Test
    public void profileChangeAndPauseFailClosed() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(store, null);
        PlaybackAutoContext.SessionToken profile = token("ab-profile", 6);
        coordinator.beginSession(profile, start(6), 0);
        PlaybackProfileAbCoordinator.RuntimeInput input = runtime(
                context(profile),
                observation(PlaybackAutoContext.PlaybackPhase.READY, 500),
                6);
        assertFalse(coordinator.observe(
                profile,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        true,
                        PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                        6,
                        input.context(),
                        input.observation(),
                        true,
                        false,
                        false),
                1_000));

        PlaybackAutoContext.SessionToken paused = token("ab-pause", 7);
        coordinator.beginSession(paused, start(7), 0);
        coordinator.observe(paused, runtime(
                context(paused), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 500), 7),
                1_000);
        assertFalse(coordinator.observe(
                paused,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        true,
                        PlaybackProfileAbPolicy.Arm.AUTO,
                        7,
                        context(paused),
                        observation(PlaybackAutoContext.PlaybackPhase.READY,
                                500),
                        false,
                        false,
                        false),
                2_000));
        assertEquals(0, store.snapshot(10_000).automaticSampleCount());
    }

    @Test
    public void startupErrorIsRetainedWithoutFirstFrame() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(store, null);
        PlaybackAutoContext.SessionToken session = token("ab-error", 8);

        coordinator.beginSession(session, start(8), 0);
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.ERROR, -1), 8),
                500));
        assertTrue(coordinator.endSession(
                session, end(8, PlaybackProfileAbPolicy.Arm.AUTO),
                500, 20_000));

        PlaybackProfileAbStore.Sample sample = store.snapshot(20_000)
                .groups().get(0).automatic().get(0);
        assertEquals(-1, sample.firstFrameMs());
        assertTrue(sample.errorObserved());
    }

    @Test
    public void groupChangesSpeedRescueAndOldSessionsCannotLeakSamples() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(store, null);
        PlaybackAutoContext.SessionToken changed = token("changed", 9);
        coordinator.beginSession(changed, start(9), 0);
        coordinator.observe(changed, runtime(
                context(changed), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 500), 9),
                1_000);
        assertFalse(coordinator.observe(
                changed,
                runtime(contextWithProtocol(
                                changed, PlaybackAutoContext.Protocol.DASH),
                        observation(
                                PlaybackAutoContext.PlaybackPhase.READY, 500),
                        9),
                2_000));

        PlaybackAutoContext.SessionToken speed = token("speed", 10);
        coordinator.beginSession(speed, start(10), 0);
        PlaybackProfileAbCoordinator.RuntimeInput input = runtime(
                context(speed), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 500), 10);
        assertFalse(coordinator.observe(
                speed,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        true,
                        PlaybackProfileAbPolicy.Arm.AUTO,
                        10,
                        input.context(),
                        input.observation(),
                        true,
                        false,
                        true),
                1_000));

        PlaybackAutoContext.SessionToken old = token("old", 11);
        PlaybackAutoContext.SessionToken current = token("current", 12);
        coordinator.beginSession(old, start(11), 0);
        coordinator.beginSession(current, start(12), 0);
        assertFalse(coordinator.observe(
                old,
                runtime(context(old), observation(
                        PlaybackAutoContext.PlaybackPhase.ERROR, -1), 11),
                100));
        assertEquals(current, coordinator.snapshot().session());
        assertEquals(0, store.snapshot(30_000).automaticSampleCount());
    }

    @Test
    public void customGroupResolverCanConservativelyRejectAComparison() {
        PlaybackProfileAbStore store =
                new PlaybackProfileAbStore(new MemoryBackend());
        PlaybackProfileAbCoordinator coordinator =
                new PlaybackProfileAbCoordinator(
                        store,
                        null,
                        (context, device, now) ->
                                PlaybackProfileAbPolicy.GroupResolution
                                        .invalid(
                                                PlaybackProfileAbPolicy
                                                        .GroupReason
                                                        .NOT_LIGHTWEIGHT_SCENARIO));
        PlaybackAutoContext.SessionToken session = token("restricted", 13);

        assertTrue(coordinator.beginSession(session, start(13), 0));
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 500), 13),
                1_000));
        assertTrue(coordinator.observe(
                session,
                runtime(context(session), observation(
                        PlaybackAutoContext.PlaybackPhase.READY, 500), 13),
                31_500));
        assertFalse(coordinator.endSession(
                session,
                end(13, PlaybackProfileAbPolicy.Arm.AUTO),
                31_500,
                40_000));
        assertEquals(0, store.snapshot(40_000).automaticSampleCount());
    }

    private static PlaybackProfileAbCoordinator.StartConfig start(
            long generation) {
        return new PlaybackProfileAbCoordinator.StartConfig(
                true,
                PlaybackProfileAbPolicy.Arm.AUTO,
                DEVICE,
                generation,
                true);
    }

    private static PlaybackProfileAbCoordinator.EndConfig end(
            long generation,
            PlaybackProfileAbPolicy.Arm arm) {
        return new PlaybackProfileAbCoordinator.EndConfig(
                true, arm, generation, "finished");
    }

    private static PlaybackProfileAbCoordinator.RuntimeInput runtime(
            PlaybackAutoContext context,
            PlaybackTelemetry.RuntimeObservation observation,
            long generation) {
        return new PlaybackProfileAbCoordinator.RuntimeInput(
                true,
                PlaybackProfileAbPolicy.Arm.AUTO,
                generation,
                context,
                observation,
                true,
                false,
                false);
    }

    private static PlaybackTelemetry.RuntimeObservation observation(
            PlaybackAutoContext.PlaybackPhase phase,
            long firstFrameMs) {
        return new PlaybackTelemetry.RuntimeObservation(
                metric(phase),
                metric(false),
                metric(1_000L),
                metric(100_000L),
                metric(10_000L),
                metric(8_000_000L),
                metric(4_000_000L),
                metric(24f),
                metric(2L),
                metric(1),
                metric(500L),
                firstFrameMs < 0
                        ? PlaybackTelemetry.Metric.unknown()
                        : metric(firstFrameMs),
                PlaybackTelemetry.Metric.unknown());
    }

    private static PlaybackAutoContext context(
            PlaybackAutoContext.SessionToken session) {
        long now = 100;
        PlaybackAutoContext.TrackFacts video =
                new PlaybackAutoContext.TrackFacts(
                        fact("video/hevc", now),
                        fact("hvc1.2.4", now),
                        fact(2, now),
                        fact(4, now),
                        fact(1920, now),
                        fact(1080, now),
                        fact(24f, now),
                        fact(PlaybackAutoContext.HdrType.HDR10, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ColorSnapshot.unknown()),
                        fact(4_000_000L, now),
                        fact(6_000_000L, now));
        PlaybackAutoContext.MediaFacts media =
                new PlaybackAutoContext.MediaFacts(
                        1,
                        video,
                        PlaybackAutoContext.TrackFacts.unknown(),
                        new PlaybackAutoContext.DecoderFacts(
                                1,
                                fact("decoder-name", now),
                                PlaybackAutoContext.Fact.unknown(""),
                                fact(PlaybackAutoContext.DecodeMode.HARDWARE,
                                        now),
                                fact(false, now)),
                        PlaybackAutoContext.OutputFacts.unknown(),
                        PlaybackAutoContext.DisplayFacts.unknown());
        PlaybackAutoContext.DeviceFacts device =
                new PlaybackAutoContext.DeviceFacts(
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.MemoryPressure.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.MemorySnapshot.unknown()),
                        fact(180L << 20, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ThermalState.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.PowerState.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.NetworkCost.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.NetworkSnapshot.unknown()));
        PlaybackAutoContext.ResourceFacts resource =
                new PlaybackAutoContext.ResourceFacts(
                        fact(PlaybackAutoContext.Protocol.HLS, now),
                        fact(PlaybackAutoContext.StreamKind.VOD, now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.RangeSupport.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.TransferUnit.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.ManifestFacts.unknown()));
        PlaybackAutoContext.PathFacts path =
                new PlaybackAutoContext.PathFacts(
                        PlaybackAutoContext.Fact.unknown(PlaybackRoute.OTHER),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRoute.Owner.UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(false),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRouteCapabilities.ObservedLeg
                                        .SOURCE_SPECIFIC),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRouteCapabilities.UpstreamVisibility
                                        .UNKNOWN),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackRouteCapabilities.ControlScope.NONE),
                        fact(PlaybackAutoContext.PathKind.REMOTE, now),
                        fact(PlaybackAutoContext.PathKind.REMOTE, now),
                        fact(PlaybackAutoContext.UpstreamState.VISIBLE, now));
        return new PlaybackAutoContext(
                session,
                0,
                1,
                now,
                fact(PlaybackAutoContext.Kernel.EXO, now),
                fact(PlaybackAutoContext.DecodeMode.HARDWARE, now),
                device,
                resource,
                path,
                PlaybackAutoContext.RuntimeFacts.unknown(),
                media);
    }

    private static PlaybackAutoContext incompleteContext(
            PlaybackAutoContext.SessionToken session) {
        PlaybackAutoContext complete = context(session);
        return new PlaybackAutoContext(
                complete.session(),
                complete.startedAtElapsedMs(),
                complete.revision(),
                complete.publishedAtElapsedMs(),
                complete.kernel(),
                complete.decodeMode(),
                complete.device(),
                complete.resource(),
                complete.path(),
                complete.runtime(),
                PlaybackAutoContext.MediaFacts.unknown());
    }

    private static PlaybackAutoContext contextWithProtocol(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Protocol protocol) {
        PlaybackAutoContext complete = context(session);
        PlaybackAutoContext.ResourceFacts resource =
                new PlaybackAutoContext.ResourceFacts(
                        fact(protocol, 100),
                        complete.resource().streamKind(),
                        complete.resource().rangeSupport(),
                        complete.resource().transferUnit(),
                        complete.resource().manifest());
        return new PlaybackAutoContext(
                complete.session(),
                complete.startedAtElapsedMs(),
                complete.revision(),
                complete.publishedAtElapsedMs(),
                complete.kernel(),
                complete.decodeMode(),
                complete.device(),
                resource,
                complete.path(),
                complete.runtime(),
                complete.media());
    }

    private static PlaybackAutoContext.SessionToken token(
            String trace,
            long generation) {
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(generation + 10, 36)
                        + '-' + Long.toString(generation, 36),
                generation);
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(T value, long now) {
        return PlaybackAutoContext.Fact.forSession(
                value,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH,
                now);
    }

    private static <T> PlaybackTelemetry.Metric<T> metric(T value) {
        return PlaybackTelemetry.Metric.of(
                value,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
    }

    private static final class MemoryBackend
            implements PlaybackProfileAbStore.Backend {

        private String raw = "";

        @Override
        public String read() {
            return raw;
        }

        @Override
        public void write(String value) {
            raw = value;
        }

        @Override
        public void clear() {
            raw = "";
        }
    }
}
