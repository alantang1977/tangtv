package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The regression these cover: a projector ROM whose per-UID TrafficStats counter is
 * absent or frozen used to blank the speed readout entirely, because both readouts
 * derived speed from that counter alone. The fix counts bytes in-process (OkHttp) and
 * from the playback kernel instead, so the readout no longer depends on kernel
 * accounting that some ROMs never compiled in.
 */
public class PlaybackSpeedMeterTest {

    private static final long UNSUPPORTED = -1;

    @Test
    public void kernelSampleSurvivesUnsupportedTrafficStats() {
        Fake fake = new Fake();
        fake.trafficBytes = UNSUPPORTED;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(8_000_000);

        assertEquals(PlaybackSpeedMeter.Source.KERNEL, meter.getSource());
        assertEquals(1_000_000, meter.getBytesPerSecond());
        assertFalse(meter.isUnavailable());
        assertEquals("976 KB/s", meter.getText());
    }

    @Test
    public void kernelSampleSurvivesFrozenTrafficStatsCounter() {
        Fake fake = new Fake();
        fake.trafficBytes = 4096;
        PlaybackSpeedMeter meter = fake.meter();

        // A ROM that reports a constant never advances the delta.
        fake.advance(1000);
        meter.observe(16_000_000);

        assertEquals(PlaybackSpeedMeter.Source.KERNEL, meter.getSource());
        assertEquals(2_000_000, meter.getBytesPerSecond());
    }

    @Test
    public void okHttpCounterReportsSpeedWhenTrafficStatsIsUnsupported() {
        Fake fake = new Fake();
        fake.trafficBytes = UNSUPPORTED;
        PlaybackSpeedMeter meter = fake.meter();

        // The first sample only establishes a baseline.
        meter.observe(0);
        assertTrue(meter.isUnavailable());

        fake.okHttpBytes = 512_000;
        fake.advance(1000);
        meter.observe(0);

        assertEquals(PlaybackSpeedMeter.Source.OK_HTTP, meter.getSource());
        assertEquals(512_000, meter.getBytesPerSecond());
        assertEquals("500 KB/s", meter.getText());
    }

    @Test
    public void okHttpCounterReportsSpeedWhenTrafficStatsIsFrozen() {
        Fake fake = new Fake();
        fake.trafficBytes = 4096;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(0);
        fake.okHttpBytes = 256_000;
        fake.advance(1000);
        meter.observe(0);

        assertEquals(PlaybackSpeedMeter.Source.OK_HTTP, meter.getSource());
        assertEquals(256_000, meter.getBytesPerSecond());
    }

    @Test
    public void trafficStatsWinsWhenItSeesMoreThanOkHttp() {
        Fake fake = new Fake();
        fake.trafficBytes = 0;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(0);
        // A native engine socket bypasses Java, so only TrafficStats sees those bytes.
        fake.trafficBytes = 1_024_000;
        fake.okHttpBytes = 16_000;
        fake.advance(1000);
        meter.observe(0);

        assertEquals(PlaybackSpeedMeter.Source.TRAFFIC_STATS, meter.getSource());
        assertEquals(1_024_000, meter.getBytesPerSecond());
    }

    @Test
    public void bothCountersIdleReportsZeroRatherThanUnavailable() {
        Fake fake = new Fake();
        fake.trafficBytes = 4096;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(0);
        fake.advance(1000);
        meter.observe(0);

        assertEquals(PlaybackSpeedMeter.Source.OK_HTTP, meter.getSource());
        assertEquals(0, meter.getBytesPerSecond());
    }

    @Test
    public void firstFallbackSampleAfterKernelHandoverUsesLiveBaseline() {
        Fake fake = new Fake();
        fake.trafficBytes = 1_000_000;
        fake.okHttpBytes = 1_000_000;
        PlaybackSpeedMeter meter = fake.meter();

        // While the kernel answers, both baselines must keep tracking, or the first
        // fallback sample would bill every byte since startup to one interval.
        meter.observe(8_000_000);
        fake.trafficBytes = 1_512_000;
        fake.okHttpBytes = 1_512_000;
        fake.advance(1000);
        meter.observe(0);

        assertEquals(512_000, meter.getBytesPerSecond());
    }

    @Test
    public void counterResetIsNotReportedAsNegativeSpeed() {
        Fake fake = new Fake();
        fake.trafficBytes = 1_000_000;
        fake.okHttpBytes = 1_000_000;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(0);
        fake.trafficBytes = 4096;
        fake.okHttpBytes = 0;
        fake.advance(1000);
        meter.observe(0);

        assertEquals(PlaybackSpeedMeter.Source.NONE, meter.getSource());
        assertEquals(0, meter.getBytesPerSecond());
        assertEquals("", meter.getText());
    }

    @Test
    public void resetClearsReadoutAndRebasesBaselines() {
        Fake fake = new Fake();
        fake.trafficBytes = 1_000_000;
        fake.okHttpBytes = 1_000_000;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(8_000_000);
        meter.reset();

        assertEquals(PlaybackSpeedMeter.Source.NONE, meter.getSource());
        assertEquals(0, meter.getBytesPerSecond());
        assertEquals("", meter.getText());

        // Rebased, so the next interval bills only bytes seen since the reset.
        fake.trafficBytes = 1_512_000;
        fake.okHttpBytes = 1_512_000;
        fake.advance(1000);
        meter.observe(0);
        assertEquals(512_000, meter.getBytesPerSecond());
    }

    @Test
    public void repeatedSampleWithinSameMillisecondKeepsPreviousReadout() {
        Fake fake = new Fake();
        fake.okHttpBytes = 1_000_000;
        PlaybackSpeedMeter meter = fake.meter();

        meter.observe(0);
        fake.advance(1000);
        fake.okHttpBytes = 1_512_000;
        meter.observe(0);
        assertEquals(512_000, meter.getBytesPerSecond());

        // No elapsed time means no new sample; the last good value must stand rather
        // than divide by zero or collapse to 0.
        fake.okHttpBytes = 9_000_000;
        meter.observe(0);
        assertEquals(512_000, meter.getBytesPerSecond());
    }

    @Test
    public void formatSwitchesToMegabytesAtOneThousandKilobytes() {
        assertEquals("0 KB/s", PlaybackSpeedMeter.format(0));
        assertEquals("999 KB/s", PlaybackSpeedMeter.format(999 * 1024));
        assertEquals("1.0 MB/s", PlaybackSpeedMeter.format(1000 * 1024));
        assertEquals("0 KB/s", PlaybackSpeedMeter.format(-1));
    }

    private static final class Fake {

        private long trafficBytes;
        private long okHttpBytes;
        private long nowMs = 1_000;

        private PlaybackSpeedMeter meter() {
            return new PlaybackSpeedMeter(() -> trafficBytes, () -> okHttpBytes, () -> nowMs);
        }

        private void advance(long deltaMs) {
            nowMs += deltaMs;
        }
    }
}
