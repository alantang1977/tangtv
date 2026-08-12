package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackMemoryPolicyTest {

    @Test
    public void lowMemoryCallbackIsCriticalHighConfidenceEvidence() {
        PlaybackMemoryPolicy.Result result = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.LOW_MEMORY, null, null, null, null, null), 34);

        assertEquals(PlaybackAutoContext.MemoryPressure.CRITICAL, result.pressure());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, result.confidence());
        assertEquals(PlaybackMemoryPolicy.Reason.LOW_MEMORY_CALLBACK, result.reason());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK, result.source());
        assertTrue(result.callbackEvidence());
    }

    @Test
    public void systemSignalsAndExhaustedJavaHeapAreCritical() {
        PlaybackMemoryPolicy.Result lowMemory = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.PERIODIC, false, 1_000L, 100L, true, null), 34);
        PlaybackMemoryPolicy.Result threshold = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.PERIODIC, false, 100L, 100L, false, null), 34);
        PlaybackMemoryPolicy.Result heap = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.PERIODIC, false, 1_000L, 100L, false, null,
                        100L, 100L, 0L), 34);

        assertEquals(PlaybackMemoryPolicy.Reason.SYSTEM_LOW_MEMORY, lowMemory.reason());
        assertEquals(PlaybackMemoryPolicy.Reason.SYSTEM_THRESHOLD, threshold.reason());
        assertEquals(PlaybackMemoryPolicy.Reason.JAVA_HEAP_EXHAUSTED, heap.reason());
        assertEquals(PlaybackAutoContext.MemoryPressure.CRITICAL, lowMemory.pressure());
        assertEquals(PlaybackAutoContext.MemoryPressure.CRITICAL, threshold.pressure());
        assertEquals(PlaybackAutoContext.MemoryPressure.CRITICAL, heap.pressure());
    }

    @Test
    public void legacyRunningTrimLevelsMapConservativelyBeforeApi34() {
        PlaybackMemoryPolicy.Result critical = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.TRIM_MEMORY, null, null, null, null,
                        PlaybackMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL), 33);
        PlaybackMemoryPolicy.Result low = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.TRIM_MEMORY, null, null, null, null,
                        PlaybackMemoryPolicy.TRIM_MEMORY_RUNNING_LOW), 33);
        PlaybackMemoryPolicy.Result moderate = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.TRIM_MEMORY, null, null, null, null,
                        PlaybackMemoryPolicy.TRIM_MEMORY_RUNNING_MODERATE), 33);

        assertEquals(PlaybackAutoContext.MemoryPressure.CRITICAL, critical.pressure());
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM, critical.confidence());
        assertEquals(PlaybackAutoContext.MemoryPressure.MODERATE, low.pressure());
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM, low.confidence());
        assertEquals(PlaybackAutoContext.MemoryPressure.MODERATE, moderate.pressure());
        assertEquals(PlaybackAutoContext.Confidence.LOW, moderate.confidence());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK, critical.source());
    }

    @Test
    public void api34DoesNotUseDeprecatedRunningTrimLevels() {
        PlaybackMemoryPolicy.Result unknown = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.TRIM_MEMORY, null, null, null, null,
                        PlaybackMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL), 34);
        PlaybackMemoryPolicy.Result normal = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.TRIM_MEMORY, false, 1_000L, 100L, false,
                        PlaybackMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL), 34);

        assertFalse(unknown.known());
        assertEquals(PlaybackAutoContext.MemoryPressure.UNKNOWN, unknown.pressure());
        assertEquals(PlaybackAutoContext.MemoryPressure.NORMAL, normal.pressure());
        assertEquals(PlaybackMemoryPolicy.Reason.SYSTEM_NORMAL, normal.reason());
    }

    @Test
    public void explicitSystemHeadroomIsNormalAndMissingEvidenceStaysUnknown() {
        PlaybackMemoryPolicy.Result normal = PlaybackMemoryPolicy.evaluate(
                snapshot(PlaybackAutoContext.MemoryTrigger.PERIODIC, false, 101L, 100L, false, null), 34);
        PlaybackMemoryPolicy.Result unknown = PlaybackMemoryPolicy.evaluate(
                PlaybackAutoContext.MemorySnapshot.unknown(), 34);

        assertEquals(PlaybackAutoContext.MemoryPressure.NORMAL, normal.pressure());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, normal.confidence());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API, normal.source());
        assertFalse(unknown.known());
    }

    @Test
    public void snapshotConfidenceRequiresObservableMetrics() {
        PlaybackAutoContext.MemorySnapshot complete = snapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC, false, 1_000L, 100L, false, null,
                50L, 100L, 50L);
        PlaybackAutoContext.MemorySnapshot partial = snapshot(
                PlaybackAutoContext.MemoryTrigger.SESSION_START, null, null, null, null, null);

        assertEquals(PlaybackAutoContext.Confidence.HIGH, PlaybackMemoryPolicy.snapshotConfidence(complete));
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM, PlaybackMemoryPolicy.snapshotConfidence(partial));
        assertEquals(PlaybackAutoContext.Confidence.UNKNOWN,
                PlaybackMemoryPolicy.snapshotConfidence(PlaybackAutoContext.MemorySnapshot.unknown()));
    }

    @Test
    public void snapshotSanitizesInvalidMetricsAndClampsHeadroom() {
        PlaybackAutoContext.MemorySnapshot snapshot = new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                -1L, 100L, 200L, null, -2L, -3L, -4L, null, -5, -6, -7L);

        assertEquals(null, snapshot.javaHeapUsedBytes());
        assertEquals(Long.valueOf(100), snapshot.javaHeapLimitBytes());
        assertEquals(Long.valueOf(100), snapshot.javaHeapHeadroomBytes());
        assertEquals(null, snapshot.systemTotalBytes());
        assertEquals(null, snapshot.lastTrimLevel());
        assertTrue(snapshot.hasEvidence());
    }

    private static PlaybackAutoContext.MemorySnapshot snapshot(
            PlaybackAutoContext.MemoryTrigger trigger,
            Boolean lowRam,
            Long available,
            Long threshold,
            Boolean lowMemory,
            Integer trimLevel) {
        return snapshot(trigger, lowRam, available, threshold, lowMemory, trimLevel,
                null, null, null);
    }

    private static PlaybackAutoContext.MemorySnapshot snapshot(
            PlaybackAutoContext.MemoryTrigger trigger,
            Boolean lowRam,
            Long available,
            Long threshold,
            Boolean lowMemory,
            Integer trimLevel,
            Long javaUsed,
            Long javaLimit,
            Long javaHeadroom) {
        return new PlaybackAutoContext.MemorySnapshot(
                trigger,
                javaUsed,
                javaLimit,
                javaHeadroom,
                lowRam,
                2_000L,
                available,
                threshold,
                lowMemory,
                trimLevel,
                100,
                10L);
    }
}
