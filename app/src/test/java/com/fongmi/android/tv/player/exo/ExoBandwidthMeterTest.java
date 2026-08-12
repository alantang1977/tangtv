package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.fongmi.android.tv.player.PlaybackAutoContextStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ExoBandwidthMeterTest {

    @Test
    public void unknownNetworkUsesMedia3DefaultInitialEstimate() {
        DefaultBandwidthMeter meter = ExoUtil.buildEnhancedBandwidthMeter(null);

        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE, meter.getBitrateEstimate());
    }

    @Test
    public void automaticMeterPreservesMedia3PriorAndTransferListenerContract() {
        BandwidthMeter built = ExoUtil.buildAutomaticBandwidthMeter(null);
        ExoPathAwareBandwidthMeter meter = testMeter();

        assertTrue(built instanceof ExoPathAwareBandwidthMeter);
        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE,
                meter.getBitrateEstimate());
        assertSame(built, built.getTransferListener());
    }

    @Test
    public void knownNetworksUseMedia3CountryAndNetworkDefaults() {
        long wifiEstimate = initialEstimate(C.NETWORK_TYPE_WIFI);
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI.contains(wifiEstimate));
        assertEquals(wifiEstimate, initialEstimate(C.NETWORK_TYPE_ETHERNET));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_2G.contains(initialEstimate(C.NETWORK_TYPE_2G)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_3G.contains(initialEstimate(C.NETWORK_TYPE_3G)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_4G.contains(initialEstimate(C.NETWORK_TYPE_4G)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA.contains(initialEstimate(C.NETWORK_TYPE_5G_NSA)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA.contains(initialEstimate(C.NETWORK_TYPE_5G_SA)));
        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE, initialEstimate(C.NETWORK_TYPE_OFFLINE));
        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE, initialEstimate(C.NETWORK_TYPE_OTHER));
    }

    private long initialEstimate(int networkType) {
        ExoPathAwareBandwidthMeter testMeter = testMeter();
        testMeter.rawMeterForTest().setNetworkTypeOverride(networkType);
        return testMeter.getBitrateEstimate();
    }

    private ExoPathAwareBandwidthMeter testMeter() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        DefaultBandwidthMeter raw = new DefaultBandwidthMeter.Builder(null)
                .setSlidingWindowMaxWeight(4_000)
                .setClock(new TestClock())
                .build();
        return new ExoPathAwareBandwidthMeter(
                raw,
                new ExoThroughputCoordinator(store),
                new ExoPreloadTrafficCoordinator(store),
                () -> 0);
    }

    private static final class TestClock implements Clock {

        @Override
        public long currentTimeMillis() {
            return 0;
        }

        @Override
        public long elapsedRealtime() {
            return 0;
        }

        @Override
        public long uptimeMillis() {
            return 0;
        }

        @Override
        public long nanoTime() {
            return 0;
        }

        @Override
        public HandlerWrapper createHandler(Looper looper, Handler.Callback callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onThreadBlocked() {
        }
    }
}
