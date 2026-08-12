package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Estimates source cadence from presentation timestamps when the container omits frameRate. */
final class ObservedVideoFrameRateEstimator {

    private static final int MAX_SAMPLES = 48;
    private static final int MIN_SAMPLES = 8;
    private static final long MIN_FRAME_DELTA_US = 5_000L;
    private static final long MAX_FRAME_DELTA_US = 200_000L;

    private final Deque<Long> deltasUs = new ArrayDeque<>();
    private long lastPresentationTimeUs = C.TIME_UNSET;

    synchronized void reset() {
        deltasUs.clear();
        lastPresentationTimeUs = C.TIME_UNSET;
    }

    synchronized void observe(long presentationTimeUs) {
        if (presentationTimeUs == C.TIME_UNSET || presentationTimeUs < 0) return;
        if (lastPresentationTimeUs != C.TIME_UNSET) {
            long deltaUs = presentationTimeUs - lastPresentationTimeUs;
            if (deltaUs >= MIN_FRAME_DELTA_US && deltaUs <= MAX_FRAME_DELTA_US) {
                deltasUs.addLast(deltaUs);
                while (deltasUs.size() > MAX_SAMPLES) deltasUs.removeFirst();
            } else if (deltaUs > MAX_FRAME_DELTA_US) {
                deltasUs.clear();
            }
        }
        lastPresentationTimeUs = presentationTimeUs;
    }

    synchronized Estimate estimate() {
        if (deltasUs.size() < MIN_SAMPLES) return Estimate.unknown();
        List<Long> sorted = new ArrayList<>(deltasUs);
        Collections.sort(sorted);
        long medianUs = sorted.get(sorted.size() / 2);
        if (medianUs <= 0) return Estimate.unknown();
        return new Estimate(1_000_000f / medianUs, sorted.size());
    }

    record Estimate(float frameRate, int sampleCount) {
        private static Estimate unknown() {
            return new Estimate(0f, 0);
        }
    }
}
