package com.fongmi.android.tv.player.exo;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fongmi.android.tv.player.PlaybackTrace;

import java.util.Locale;

/** Session-scoped, privacy-safe evidence for the EXO frame scheduling A/B experiment. */
public final class ExoFrameSchedulingExperimentMetrics {

    private String traceId = PlaybackTrace.NONE;
    private String deviceDigest = "";
    private ExoFrameSchedulingExperimentPolicy.Decision decision =
            ExoFrameSchedulingExperimentPolicy.stableDecision(
                    false, false, false);
    private ExoDecoderRuntimeSession.OutputConfig output =
            ExoDecoderRuntimeSession.OutputConfig.unknown();
    private String codecQueueMode = "unknown";
    private long startedAtElapsedMs = -1;
    private long firstFrameMs = -1;
    private String decoderDigest = "";
    private String mimeType = "unknown";
    private String codecDigest = "";
    private int width;
    private int height;
    private int frameRateMilli;
    private int colorTransfer;
    private boolean hdrStaticInfo;
    private int secureHint = -1;
    private int pauseBoundaries;
    private int seekBoundaries;
    private int rebufferBoundaries;
    private int formatBoundaries;

    synchronized boolean begin(
            String traceId,
            ExoFrameSchedulingExperimentPolicy.Decision decision,
            ExoDecoderRuntimeSession.OutputConfig output,
            String codecQueueMode,
            String deviceDigest,
            long startedAtElapsedMs) {
        reset();
        String normalizedTraceId = PlaybackTrace.normalize(traceId);
        if (decision == null
                || !decision.experimentApplied()
                || PlaybackTrace.NONE.equals(normalizedTraceId)) {
            return false;
        }
        this.traceId = normalizedTraceId;
        this.decision = decision;
        this.output = output == null
                ? ExoDecoderRuntimeSession.OutputConfig.unknown() : output;
        this.codecQueueMode = safeQueueMode(codecQueueMode);
        this.deviceDigest =
                ExoFrameSchedulingExperimentIdentity.validDigest(deviceDigest)
                        ? deviceDigest : "";
        this.startedAtElapsedMs = Math.max(0, startedAtElapsedMs);
        return true;
    }

    synchronized void reset() {
        traceId = PlaybackTrace.NONE;
        deviceDigest = "";
        decision = ExoFrameSchedulingExperimentPolicy.stableDecision(
                false, false, false);
        output = ExoDecoderRuntimeSession.OutputConfig.unknown();
        codecQueueMode = "unknown";
        startedAtElapsedMs = -1;
        firstFrameMs = -1;
        decoderDigest = "";
        mimeType = "unknown";
        codecDigest = "";
        width = 0;
        height = 0;
        frameRateMilli = 0;
        colorTransfer = 0;
        hdrStaticInfo = false;
        secureHint = -1;
        pauseBoundaries = 0;
        seekBoundaries = 0;
        rebufferBoundaries = 0;
        formatBoundaries = 0;
    }

    synchronized void observeDecoder(String decoderName) {
        if (!active()) return;
        decoderDigest = ExoFrameSchedulingExperimentIdentity.decoderDigest(
                decoderName);
        if (decoderName == null || decoderName.isBlank()) {
            secureHint = -1;
            return;
        }
        String normalized = decoderName.toLowerCase(Locale.US);
        secureHint = normalized.contains(".secure")
                || normalized.contains("secure.decoder")
                || normalized.endsWith("-secure") ? 1 : 0;
    }

    synchronized void observeFormat(Format format) {
        if (!active() || format == null) return;
        mimeType = safeMime(format.sampleMimeType);
        codecDigest = ExoFrameSchedulingExperimentIdentity.codecDigest(
                format.codecs);
        width = Math.max(0, format.width);
        height = Math.max(0, format.height);
        frameRateMilli = format.frameRate > 0
                && Float.isFinite(format.frameRate)
                ? Math.min(1_000_000, Math.round(format.frameRate * 1_000f))
                : 0;
        ColorInfo colorInfo = format.colorInfo;
        colorTransfer = colorInfo == null
                ? 0 : Math.max(0, colorInfo.colorTransfer);
        hdrStaticInfo = colorInfo != null
                && colorInfo.hdrStaticInfo != null
                && colorInfo.hdrStaticInfo.length > 0;
    }

    synchronized void observeFirstFrame(long renderTimeMs) {
        if (!active() || firstFrameMs >= 0 || startedAtElapsedMs < 0) return;
        firstFrameMs = Math.max(0, renderTimeMs - startedAtElapsedMs);
    }

    synchronized void observeBoundary(Boundary boundary) {
        if (!active() || boundary == null || firstFrameMs < 0) return;
        switch (boundary) {
            case PAUSE -> pauseBoundaries = saturatedIncrement(pauseBoundaries);
            case SEEK -> seekBoundaries = saturatedIncrement(seekBoundaries);
            case REBUFFER -> rebufferBoundaries = saturatedIncrement(rebufferBoundaries);
            case FORMAT_CHANGE -> formatBoundaries = saturatedIncrement(formatBoundaries);
        }
    }

    synchronized Snapshot snapshot(
            ExoFrameTimingMetrics.Snapshot timing,
            long droppedFrames,
            int rebufferCount) {
        return new Snapshot(
                active(),
                traceId,
                decision.sessionUnitId(),
                decision.experimentApplied(),
                decision.reason(),
                decision.assignmentStatus(),
                ExoFrameSchedulingExperimentIdentity.shortDigest(deviceDigest),
                ExoFrameSchedulingExperimentIdentity.shortDigest(decoderDigest),
                mimeType,
                ExoFrameSchedulingExperimentIdentity.shortDigest(codecDigest),
                width,
                height,
                frameRateMilli,
                colorTransfer,
                hdrStaticInfo,
                secureHint,
                output.target(),
                output.tunneling(),
                decision.rendererSettings().earlySchedulingThresholdUs(),
                decision.rendererSettings().durationToProgressEnabled(),
                decision.dynamicSchedulingEnabled(),
                codecQueueMode,
                firstFrameMs,
                Math.max(0, droppedFrames),
                Math.max(0, rebufferCount),
                pauseBoundaries,
                seekBoundaries,
                rebufferBoundaries,
                formatBoundaries,
                timing);
    }

    private boolean active() {
        return startedAtElapsedMs >= 0
                && !PlaybackTrace.NONE.equals(traceId);
    }

    private static int saturatedIncrement(int value) {
        return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;
    }

    private static String safeQueueMode(String value) {
        if ("async".equals(value) || "sync".equals(value)
                || "auto".equals(value)) return value;
        return "unknown";
    }

    private static String safeMime(String value) {
        if (value == null) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.length() > 96
                || !normalized.matches(
                "[a-z0-9.+_-]+/[a-z0-9.+_-]+")) {
            return "unknown";
        }
        return normalized;
    }

    public enum Boundary {
        PAUSE,
        SEEK,
        REBUFFER,
        FORMAT_CHANGE
    }

    public record Snapshot(
            boolean active,
            String traceId,
            String unitId,
            boolean experimentApplied,
            ExoFrameSchedulingExperimentPolicy.Reason reason,
            ExoFrameSchedulingExperimentPolicy.Status assignmentStatus,
            String deviceId,
            String decoderId,
            String mimeType,
            String codecId,
            int width,
            int height,
            int frameRateMilli,
            int colorTransfer,
            boolean hdrStaticInfo,
            int secureHint,
            ExoDecoderRuntimeSession.OutputTarget outputTarget,
            boolean tunneling,
            long earlySchedulingThresholdUs,
            boolean durationToProgressRequested,
            boolean dynamicSchedulingEnabled,
            String codecQueueMode,
            long firstFrameMs,
            long droppedFrames,
            int rebufferCount,
            int pauseBoundaries,
            int seekBoundaries,
            int rebufferBoundaries,
            int formatBoundaries,
            ExoFrameTimingMetrics.Snapshot timing) {

        public Snapshot {
            traceId = PlaybackTrace.normalize(traceId);
            unitId = unitId == null || unitId.isBlank()
                    ? "stable-50-duration-off" : unitId;
            reason = reason == null
                    ? ExoFrameSchedulingExperimentPolicy.Reason.INVALID_ASSIGNMENT
                    : reason;
            assignmentStatus = assignmentStatus == null
                    ? ExoFrameSchedulingExperimentPolicy.Status.CORRUPT
                    : assignmentStatus;
            deviceId = safeId(deviceId);
            decoderId = safeId(decoderId);
            mimeType = safeMime(mimeType);
            codecId = safeId(codecId);
            width = Math.max(0, width);
            height = Math.max(0, height);
            frameRateMilli = Math.max(0, frameRateMilli);
            colorTransfer = Math.max(0, colorTransfer);
            secureHint = Math.max(-1, Math.min(1, secureHint));
            outputTarget = outputTarget == null
                    ? ExoDecoderRuntimeSession.OutputTarget.UNKNOWN
                    : outputTarget;
            earlySchedulingThresholdUs = Math.max(0,
                    earlySchedulingThresholdUs);
            codecQueueMode = safeQueueMode(codecQueueMode);
            firstFrameMs = Math.max(-1, firstFrameMs);
            droppedFrames = Math.max(0, droppedFrames);
            rebufferCount = Math.max(0, rebufferCount);
            pauseBoundaries = Math.max(0, pauseBoundaries);
            seekBoundaries = Math.max(0, seekBoundaries);
            rebufferBoundaries = Math.max(0, rebufferBoundaries);
            formatBoundaries = Math.max(0, formatBoundaries);
            timing = timing == null
                    ? new ExoFrameTimingMetrics().snapshot() : timing;
        }

        public static Snapshot none() {
            return new Snapshot(
                    false,
                    PlaybackTrace.NONE,
                    "stable-50-duration-off",
                    false,
                    ExoFrameSchedulingExperimentPolicy.Reason.NOT_AUTOMATIC,
                    ExoFrameSchedulingExperimentPolicy.Status.UNASSIGNED,
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    0, 0, 0, 0, false, -1,
                    ExoDecoderRuntimeSession.OutputTarget.UNKNOWN,
                    false,
                    ExoFrameSchedulingExperimentPolicy
                            .BASELINE_EARLY_SCHEDULING_THRESHOLD_US,
                    false,
                    false,
                    "unknown",
                    -1,
                    0,
                    0,
                    0, 0, 0, 0,
                    new ExoFrameTimingMetrics().snapshot());
        }

        public String logSummary() {
            return "unit=" + unitId
                    + " applied=" + experimentApplied
                    + " reason=" + reason.label()
                    + " assignment=" + assignmentStatus.name()
                    .toLowerCase(Locale.US)
                    + " device=" + deviceId
                    + " decoder=" + decoderId
                    + " mime=" + mimeType
                    + " codec=" + codecId
                    + " size=" + width + "x" + height
                    + " fpsMilli=" + frameRateMilli
                    + " colorTransfer=" + colorTransfer
                    + " hdrStatic=" + hdrStaticInfo
                    + " secureHint=" + secureHint
                    + " output=" + outputTarget.name().toLowerCase(Locale.US)
                    + " tunneling=" + tunneling
                    + " earlyUs=" + earlySchedulingThresholdUs
                    + " durationRequested=" + durationToProgressRequested
                    + " dynamicScheduling=" + dynamicSchedulingEnabled
                    + " codecQueue=" + codecQueueMode
                    + " firstFrameMs=" + firstFrameMs
                    + " dropped=" + droppedFrames
                    + " rebuffers=" + rebufferCount
                    + " averageOffsetUs=" + timing.averageOffsetUs()
                    + " offsetBuckets="
                    + timing.processingOffsetBuckets().compact()
                    + " averageReleaseLeadUs="
                    + timing.averageReleaseLeadUs()
                    + " releaseLeadBuckets="
                    + timing.releaseLeadBuckets().compact()
                    + " lateReleaseFrames="
                    + timing.lateReleaseFrameCount()
                    + " maxLateReleaseUs=" + timing.maxLateReleaseUs()
                    + " averageJitterUs="
                    + timing.averageReleaseJitterUs()
                    + " jitterBuckets="
                    + timing.releaseJitterBuckets().compact()
                    + " averageCallbackGapUs="
                    + timing.averageCallbackGapUs()
                    + " callbackGapBuckets="
                    + timing.callbackGapBuckets().compact()
                    + " maxCallbackGapUs=" + timing.maxCallbackGapUs()
                    + " codecErrors=" + timing.codecErrorCount()
                    + " boundaries=" + pauseBoundaries + "/"
                    + seekBoundaries + "/" + rebufferBoundaries + "/"
                    + formatBoundaries;
        }

        private static String safeId(String value) {
            if (value == null || "unknown".equals(value)) return "unknown";
            if (value.length() != 12) return "unknown";
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character >= '0' && character <= '9') continue;
                if (character >= 'a' && character <= 'f') continue;
                return "unknown";
            }
            return value;
        }
    }
}
