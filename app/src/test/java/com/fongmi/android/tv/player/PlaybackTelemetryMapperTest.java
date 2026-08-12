package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackTelemetryMapperTest {

    @Test
    public void knownValuesKeepEvidenceAndExpiryRules() {
        long sampledAt = 1_000;
        PlaybackTelemetry.RuntimeObservation observation = new PlaybackTelemetry.RuntimeObservation(
                metric(PlaybackAutoContext.PlaybackPhase.READY),
                metric(true), metric(2_000L), metric(100_000L), metric(5_000L),
                metric(8_000_000L), metric(4_000_000L), metric(24f), metric(2L),
                metric(1), metric(300L), metric(900L), metric(1_200L));

        PlaybackAutoContext.RuntimeFacts runtime = PlaybackTelemetryMapper.map(observation, sampledAt);

        assertTrue(runtime.phase().hasValue());
        assertEquals(PlaybackAutoContext.ExpiryRule.UNTIL_REPLACED, runtime.phase().expiryRule());
        assertEquals(PlaybackAutoContext.ExpiryRule.TTL, runtime.bufferedDurationMs().expiryRule());
        assertEquals(PlaybackTelemetryMapper.VOLATILE_FACT_TTL_MS, runtime.bufferedDurationMs().expiresAtElapsedMs() - sampledAt);
        assertEquals(PlaybackAutoContext.ExpiryRule.TTL, runtime.mediaBitrateBitsPerSecond().expiryRule());
        assertEquals(PlaybackTelemetryMapper.MEDIA_FACT_TTL_MS, runtime.mediaBitrateBitsPerSecond().expiresAtElapsedMs() - sampledAt);
        assertEquals(PlaybackAutoContext.ExpiryRule.SESSION, runtime.droppedFrames().expiryRule());
        assertEquals(PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, runtime.phase().source());
    }

    @Test
    public void unknownValuesRemainUnknownInsteadOfZero() {
        PlaybackAutoContext.RuntimeFacts runtime = PlaybackTelemetryMapper.map(
                PlaybackTelemetry.RuntimeObservation.unknown(), 2_000);

        assertFalse(runtime.bufferedDurationMs().hasValue());
        assertFalse(runtime.positionMs().hasValue());
        assertFalse(runtime.durationMs().hasValue());
        assertEquals(0L, runtime.bufferedDurationMs().value().longValue());
        assertEquals(PlaybackAutoContext.ValueSource.UNKNOWN, runtime.bufferedDurationMs().source());
    }

    @Test
    public void ttlAndSessionFactsHaveDifferentLifetimes() {
        PlaybackAutoContext.RuntimeFacts runtime = PlaybackTelemetryMapper.map(
                new PlaybackTelemetry.RuntimeObservation(
                        metric(PlaybackAutoContext.PlaybackPhase.READY),
                        metric(true), metric(1L), metric(10L), metric(2L),
                        metric(100L), metric(200L), metric(30f), metric(3L),
                        metric(2), metric(400L), metric(500L), metric(600L)), 10_000);

        assertTrue(runtime.bandwidthBitsPerSecond().isExpired(25_000));
        assertFalse(runtime.droppedFrames().isExpired(25_000));
        assertFalse(runtime.phase().isExpired(25_000));
    }

    @Test
    public void invalidNumericMetricsBecomeUnknown() {
        PlaybackAutoContext.RuntimeFacts runtime = PlaybackTelemetryMapper.map(
                new PlaybackTelemetry.RuntimeObservation(
                        metric(PlaybackAutoContext.PlaybackPhase.READY),
                        metric(false), metric(-1L), metric(-1L), metric(-1L),
                        metric(-1L), metric(-1L), metric(Float.NaN), metric(-1L),
                        metric(-1), metric(-1L), metric(-1L), metric(-1L)), 1_000);

        assertFalse(runtime.positionMs().hasValue());
        assertFalse(runtime.renderedFrameRate().hasValue());
        assertFalse(runtime.rebufferCount().hasValue());
    }

    private static <T> PlaybackTelemetry.Metric<T> metric(T value) {
        return PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
    }
}
