package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvHlsPreloadGateTest {

    @Test
    public void blockedGenerationNeverResumesAfterGateReopens() {
        MpvHlsPreloadGate gate = new MpvHlsPreloadGate();
        long original = gate.acquire();

        assertTrue(gate.allows(original));
        assertEquals(MpvHlsPreloadGate.Transition.BLOCKED, gate.update(false));
        assertFalse(gate.allows(original));
        assertEquals(-1, gate.acquire());
        assertEquals(MpvHlsPreloadGate.Transition.UNCHANGED, gate.update(false));

        assertEquals(MpvHlsPreloadGate.Transition.ALLOWED, gate.update(true));
        long recovered = gate.acquire();
        assertTrue(recovered > original);
        assertFalse(gate.allows(original));
        assertTrue(gate.allows(recovered));
    }

    @Test
    public void invalidateCancelsCurrentWorkWithoutChangingAdmission() {
        MpvHlsPreloadGate gate = new MpvHlsPreloadGate();
        long original = gate.acquire();

        gate.invalidate();
        long replacement = gate.acquire();

        assertTrue(gate.allowed());
        assertFalse(gate.allows(original));
        assertTrue(gate.allows(replacement));
        assertTrue(replacement > original);
    }

    @Test
    public void commitRunsOnlyForCurrentAllowedGeneration() throws Exception {
        MpvHlsPreloadGate gate = new MpvHlsPreloadGate();
        AtomicInteger commits = new AtomicInteger();
        long cancelled = gate.acquire();

        gate.update(false);
        assertFalse(gate.commitIfAllowed(cancelled, () -> {
            commits.incrementAndGet();
            return true;
        }));
        assertEquals(0, commits.get());

        gate.update(true);
        assertFalse(gate.commitIfAllowed(cancelled, () -> {
            commits.incrementAndGet();
            return true;
        }));
        assertEquals(0, commits.get());
        long current = gate.acquire();
        assertTrue(gate.commitIfAllowed(current, () -> {
            commits.incrementAndGet();
            return true;
        }));
        assertEquals(1, commits.get());
    }

    @Test
    public void foregroundRequestsCancelOnceAndBlockAcquisitionUntilAllFinish() {
        MpvHlsPreloadGate gate = new MpvHlsPreloadGate();
        long original = gate.acquire();

        assertTrue(gate.foregroundStarted());
        assertFalse(gate.foregroundStarted());
        assertEquals(2, gate.foregroundRequests());
        assertEquals(-1, gate.acquire());
        assertFalse(gate.allows(original));

        gate.foregroundEnded();
        assertEquals(-1, gate.acquire());
        gate.foregroundEnded();
        long replacement = gate.acquire();

        assertTrue(replacement > original);
        assertTrue(gate.allows(replacement));
    }

    @Test
    public void pausedModeAllowsPreloadPastAStalledForegroundRequest() {
        MpvHlsPreloadGate gate = new MpvHlsPreloadGate();
        assertTrue(gate.setForegroundBlocking(false));
        long pausedGeneration = gate.acquire();

        assertFalse(gate.foregroundStarted());
        assertEquals(1, gate.foregroundRequests());
        assertTrue(gate.allows(pausedGeneration));
        assertEquals(pausedGeneration, gate.acquire());

        assertTrue(gate.setForegroundBlocking(true));
        assertFalse(gate.allows(pausedGeneration));
        assertEquals(-1, gate.acquire());
        gate.foregroundEnded();
        assertTrue(gate.acquire() > pausedGeneration);
    }
}
