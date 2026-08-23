package com.fongmi.android.tv.ad.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AdAudioDiagnosticsTest {

    @Test
    public void snapshotContainsOnlyFixedCodeCounters() {
        AdAudioDiagnostics diagnostics = new AdAudioDiagnostics();
        diagnostics.record(AdAudioDiagnostics.Code.QUEUE_OVERFLOW);
        diagnostics.record(AdAudioDiagnostics.Code.QUEUE_OVERFLOW);
        diagnostics.record(AdAudioDiagnostics.Code.SEEK_REJECTED);

        AdAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();

        assertEquals(2L, snapshot.count(AdAudioDiagnostics.Code.QUEUE_OVERFLOW));
        assertEquals(1L, snapshot.count(AdAudioDiagnostics.Code.SEEK_REJECTED));
        assertEquals(AdAudioDiagnostics.Code.SEEK_REJECTED, snapshot.lastCode());
    }
}
