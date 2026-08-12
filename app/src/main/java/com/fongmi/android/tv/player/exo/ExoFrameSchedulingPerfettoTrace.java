package com.fongmi.android.tv.player.exo;

import android.os.Build;
import android.os.Trace;

import com.fongmi.android.tv.player.PlaybackTrace;

/** Low-overhead Perfetto markers for one frame scheduling playback session. */
final class ExoFrameSchedulingPerfettoTrace {

    private static String activeName = "";
    private static int activeCookie;

    private ExoFrameSchedulingPerfettoTrace() {
    }

    static synchronized void begin(
            String traceId,
            ExoFrameSchedulingExperimentPolicy.Decision decision) {
        reset();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || decision == null
                || !decision.experimentApplied()
                || PlaybackTrace.NONE.equals(
                PlaybackTrace.normalize(traceId))) return;
        String unit = decision.sessionUnitId();
        int cookie = traceId == null ? 1 : traceId.hashCode();
        if (cookie == 0) cookie = 1;
        String name = "webhtv.exo.frame-ab." + unit;
        try {
            Trace.beginAsyncSection(name, cookie);
            activeName = name;
            activeCookie = cookie;
        } catch (Throwable ignored) {
            activeName = "";
            activeCookie = 0;
        }
    }

    static synchronized void finish(
            ExoFrameSchedulingExperimentMetrics.Snapshot snapshot) {
        if (activeName.isEmpty()) return;
        try {
            if (snapshot != null) {
                if (snapshot.firstFrameMs() >= 0) {
                    Trace.setCounter("webhtv.exo.frame.first_frame_ms",
                            snapshot.firstFrameMs());
                }
                Trace.setCounter("webhtv.exo.frame.dropped",
                        snapshot.droppedFrames());
                if (snapshot.timing().releaseFrameCount() > 0) {
                    Trace.setCounter("webhtv.exo.frame.release_lead_us",
                            snapshot.timing().averageReleaseLeadUs());
                    Trace.setCounter("webhtv.exo.frame.late_release",
                            snapshot.timing().lateReleaseFrameCount());
                }
                if (snapshot.timing().releaseJitterSampleCount() > 0) {
                    Trace.setCounter("webhtv.exo.frame.jitter_us",
                            snapshot.timing().averageReleaseJitterUs());
                }
                if (snapshot.timing().callbackGapSampleCount() > 0) {
                    Trace.setCounter("webhtv.exo.frame.callback_gap_us",
                            snapshot.timing().averageCallbackGapUs());
                }
            }
            Trace.endAsyncSection(activeName, activeCookie);
        } catch (Throwable ignored) {
        } finally {
            activeName = "";
            activeCookie = 0;
        }
    }

    static synchronized void reset() {
        if (activeName.isEmpty()) return;
        try {
            Trace.endAsyncSection(activeName, activeCookie);
        } catch (Throwable ignored) {
        } finally {
            activeName = "";
            activeCookie = 0;
        }
    }
}
