package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Side-effect-free eligibility and budget policy for MPV automatic back cache. */
public final class MpvBackCachePolicy {

    public static final long MIB = 1024L * 1024L;
    public static final long MIN_BACK_BYTES = 16L * MIB;
    public static final long BALANCED_BACK_BYTES = 32L * MIB;
    public static final long MAX_BACK_BYTES = 64L * MIB;
    public static final long MIN_BACKWARD_SEEK_MS = 5_000L;
    public static final long LARGE_BACKWARD_SEEK_MS = 60_000L;

    private static final long[] BACK_TIERS = new long[]{
            0,
            MIN_BACK_BYTES,
            BALANCED_BACK_BYTES,
            MAX_BACK_BYTES
    };

    private MpvBackCachePolicy() {
    }

    public static Request requestFrom(
            PlaybackAutoContext context,
            boolean automatic,
            boolean mpv,
            boolean performancePriority,
            boolean seekable,
            boolean currentMediaLive,
            long forwardBytes,
            MpvForwardCachePolicy.Assessment forwardAssessment,
            long nowElapsedMs) {
        PlaybackAutoContext current = context == null
                ? PlaybackAutoContext.empty() : context;
        MpvForwardCachePolicy.Assessment forward = forwardAssessment == null
                ? MpvForwardCachePolicy.Assessment.inactive(
                MpvForwardCachePolicy.MIN_FORWARD_BYTES,
                MpvForwardCachePolicy.Reason.NOT_AUTOMATIC_MPV)
                : forwardAssessment;
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                current.resource().protocol();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> streamFact =
                current.resource().streamKind();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPathFact =
                current.path().playerPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> upstreamStateFact =
                current.path().upstreamState();
        boolean protocolUsable = protocolFact.isUsable(now);
        boolean streamUsable = streamFact.isUsable(now);
        boolean playerPathUsable = playerPathFact.isUsable(now);
        boolean upstreamStateUsable = upstreamStateFact.isUsable(now);
        return new Request(
                automatic,
                mpv,
                performancePriority,
                seekable,
                currentMediaLive,
                protocolUsable,
                protocolUsable ? protocolFact.value() : PlaybackAutoContext.Protocol.UNKNOWN,
                streamUsable,
                streamUsable ? streamFact.value() : PlaybackAutoContext.StreamKind.UNKNOWN,
                playerPathUsable,
                playerPathUsable ? playerPathFact.value() : PlaybackAutoContext.PathKind.UNKNOWN,
                upstreamStateUsable,
                upstreamStateUsable ? upstreamStateFact.value() : PlaybackAutoContext.UpstreamState.UNKNOWN,
                forward.pressureUsable(),
                forward.memoryPressure(),
                forward.snapshotUsable(),
                forward.memorySampleAtElapsedMs(),
                forward.critical(),
                MpvForwardCachePolicy.normalizeTier(forwardBytes),
                forward.safeTargetBytes());
    }

    public static Assessment resolve(Request request) {
        Request input = request == null ? Request.inactive() : request;
        if (!input.automatic() || !input.mpv()) {
            return Assessment.inactive(Reason.NOT_AUTOMATIC_MPV, input);
        }
        if (!input.performancePriority()) {
            return Assessment.inactive(Reason.CONFIG_PRIORITY, input);
        }
        if (!input.seekable()) {
            return Assessment.disabled(Reason.NOT_SEEKABLE, input);
        }
        if (isLive(input)) {
            return Assessment.disabled(Reason.LIVE_RESOURCE, input);
        }
        if (isOpaque(input)) {
            return Assessment.disabled(Reason.OPAQUE_PATH, input);
        }
        if (input.critical()) {
            return Assessment.reset(Reason.CRITICAL_PRESSURE, input);
        }
        if (input.pressureUsable()
                && input.memoryPressure() == PlaybackAutoContext.MemoryPressure.MODERATE) {
            return Assessment.reset(Reason.MODERATE_PRESSURE, input);
        }
        if (!input.pressureUsable()
                || input.memoryPressure() != PlaybackAutoContext.MemoryPressure.NORMAL
                || !input.snapshotUsable()) {
            return Assessment.reset(Reason.MEMORY_UNKNOWN, input);
        }

        long unusedTotalBudget = Math.max(0,
                input.totalBudgetBytes() - input.forwardBytes());
        // The native option policy also requires back <= forward. This keeps a
        // 24 MiB forward target from being paired with an invalid 32/64 MiB back target.
        long safeCapacity = Math.min(unusedTotalBudget, input.forwardBytes());
        long safeBack = tierForCapacity(safeCapacity);
        Reason reason = safeBack > 0 ? Reason.SAFE_BUDGET : Reason.NO_TOTAL_HEADROOM;
        return new Assessment(
                true,
                true,
                reason,
                safeBack,
                input.totalBudgetBytes(),
                input.forwardBytes(),
                input.memoryPressure(),
                input.pressureUsable(),
                input.snapshotUsable(),
                input.memorySampleAtElapsedMs(),
                safeBack == 0,
                true);
    }

    public static SeekObservation observeSeek(
            boolean explicitSeek,
            boolean sameMediaItem,
            long oldPositionMs,
            long newPositionMs) {
        if (!explicitSeek) return SeekObservation.ignored(SeekKind.NOT_EXPLICIT_SEEK);
        if (!sameMediaItem) return SeekObservation.ignored(SeekKind.MEDIA_ITEM_CHANGE);
        if (oldPositionMs < 0 || newPositionMs < 0) {
            return SeekObservation.ignored(SeekKind.UNKNOWN_POSITION);
        }
        long distanceMs = oldPositionMs > newPositionMs
                ? oldPositionMs - newPositionMs : 0;
        if (distanceMs == 0) {
            return SeekObservation.ignored(SeekKind.FORWARD_OR_EQUAL);
        }
        if (distanceMs < MIN_BACKWARD_SEEK_MS) {
            return new SeekObservation(false, distanceMs, false, SeekKind.TOO_SMALL);
        }
        return new SeekObservation(
                true,
                distanceMs,
                distanceMs >= LARGE_BACKWARD_SEEK_MS,
                SeekKind.BACKWARD);
    }

    public static long learnedTier(int backwardSeekEvidence) {
        int evidence = Math.max(0, backwardSeekEvidence);
        if (evidence < 2) return 0;
        if (evidence == 2) return MIN_BACK_BYTES;
        if (evidence == 3) return BALANCED_BACK_BYTES;
        return MAX_BACK_BYTES;
    }

    public static long tierForCapacity(long capacityBytes) {
        long capacity = Math.max(0, capacityBytes);
        long selected = 0;
        for (long tier : BACK_TIERS) {
            if (tier > capacity) break;
            selected = tier;
        }
        return selected;
    }

    public static long normalizeTier(long bytes) {
        return tierForCapacity(Math.min(MAX_BACK_BYTES, Math.max(0, bytes)));
    }

    public static long nextTier(long currentBytes, long ceilingBytes) {
        long current = normalizeTier(currentBytes);
        long ceiling = normalizeTier(ceilingBytes);
        if (current >= ceiling) return ceiling;
        for (long tier : BACK_TIERS) {
            if (tier > current) return Math.min(tier, ceiling);
        }
        return ceiling;
    }

    private static boolean isLive(Request input) {
        return input.currentMediaLive()
                || input.streamKindUsable()
                && (input.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || input.streamKind() == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE)
                || input.protocolUsable()
                && (input.protocol() == PlaybackAutoContext.Protocol.RTSP
                || input.protocol() == PlaybackAutoContext.Protocol.RTMP);
    }

    private static boolean isOpaque(Request input) {
        return input.playerPathUsable()
                && input.playerPath() == PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK
                || input.upstreamStateUsable()
                && input.upstreamState() == PlaybackAutoContext.UpstreamState.OPAQUE;
    }

    public enum Reason {
        NOT_AUTOMATIC_MPV("not-automatic-mpv"),
        CONFIG_PRIORITY("mpv-conf-priority"),
        NOT_SEEKABLE("not-seekable"),
        LIVE_RESOURCE("live-resource"),
        OPAQUE_PATH("opaque-path"),
        CRITICAL_PRESSURE("critical-pressure"),
        MODERATE_PRESSURE("moderate-pressure"),
        MEMORY_UNKNOWN("memory-unknown"),
        NO_TOTAL_HEADROOM("no-total-headroom"),
        SAFE_BUDGET("safe-budget");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum SeekKind {
        NOT_EXPLICIT_SEEK("not-explicit-seek"),
        MEDIA_ITEM_CHANGE("media-item-change"),
        UNKNOWN_POSITION("unknown-position"),
        FORWARD_OR_EQUAL("forward-or-equal"),
        TOO_SMALL("too-small"),
        BACKWARD("backward");

        private final String label;

        SeekKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record SeekObservation(
            boolean qualifying,
            long distanceMs,
            boolean large,
            SeekKind kind) {

        public SeekObservation {
            distanceMs = Math.max(0, distanceMs);
            kind = kind == null ? SeekKind.NOT_EXPLICIT_SEEK : kind;
        }

        static SeekObservation ignored(SeekKind kind) {
            return new SeekObservation(false, 0, false, kind);
        }

        public static SeekObservation none() {
            return ignored(SeekKind.NOT_EXPLICIT_SEEK);
        }
    }

    public record Request(
            boolean automatic,
            boolean mpv,
            boolean performancePriority,
            boolean seekable,
            boolean currentMediaLive,
            boolean protocolUsable,
            PlaybackAutoContext.Protocol protocol,
            boolean streamKindUsable,
            PlaybackAutoContext.StreamKind streamKind,
            boolean playerPathUsable,
            PlaybackAutoContext.PathKind playerPath,
            boolean upstreamStateUsable,
            PlaybackAutoContext.UpstreamState upstreamState,
            boolean pressureUsable,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean snapshotUsable,
            long memorySampleAtElapsedMs,
            boolean critical,
            long forwardBytes,
            long totalBudgetBytes) {

        public Request {
            protocol = protocol == null ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            playerPath = playerPath == null ? PlaybackAutoContext.PathKind.UNKNOWN : playerPath;
            upstreamState = upstreamState == null
                    ? PlaybackAutoContext.UpstreamState.UNKNOWN : upstreamState;
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
            memorySampleAtElapsedMs = memorySampleAtElapsedMs < 0 ? -1 : memorySampleAtElapsedMs;
            forwardBytes = MpvForwardCachePolicy.normalizeTier(forwardBytes);
            totalBudgetBytes = MpvForwardCachePolicy.normalizeTier(totalBudgetBytes);
        }

        static Request inactive() {
            return new Request(
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    PlaybackAutoContext.Protocol.UNKNOWN,
                    false,
                    PlaybackAutoContext.StreamKind.UNKNOWN,
                    false,
                    PlaybackAutoContext.PathKind.UNKNOWN,
                    false,
                    PlaybackAutoContext.UpstreamState.UNKNOWN,
                    false,
                    PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    false,
                    -1,
                    false,
                    MpvForwardCachePolicy.MIN_FORWARD_BYTES,
                    MpvForwardCachePolicy.MIN_FORWARD_BYTES);
        }
    }

    public record Assessment(
            boolean active,
            boolean eligible,
            Reason reason,
            long safeBackBytes,
            long totalBudgetBytes,
            long forwardBytes,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean pressureUsable,
            boolean snapshotUsable,
            long memorySampleAtElapsedMs,
            boolean forceZero,
            boolean normalMemoryEvidence) {

        public Assessment {
            reason = reason == null ? Reason.MEMORY_UNKNOWN : reason;
            safeBackBytes = normalizeTier(safeBackBytes);
            totalBudgetBytes = MpvForwardCachePolicy.normalizeTier(totalBudgetBytes);
            forwardBytes = MpvForwardCachePolicy.normalizeTier(forwardBytes);
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
            memorySampleAtElapsedMs = memorySampleAtElapsedMs < 0 ? -1 : memorySampleAtElapsedMs;
        }

        static Assessment inactive(Reason reason, Request request) {
            return new Assessment(false, false, reason, 0,
                    request.totalBudgetBytes(), request.forwardBytes(),
                    request.memoryPressure(), request.pressureUsable(),
                    request.snapshotUsable(), request.memorySampleAtElapsedMs(),
                    false, false);
        }

        static Assessment disabled(Reason reason, Request request) {
            return new Assessment(true, false, reason, 0,
                    request.totalBudgetBytes(), request.forwardBytes(),
                    request.memoryPressure(), request.pressureUsable(),
                    request.snapshotUsable(), request.memorySampleAtElapsedMs(),
                    true, false);
        }

        static Assessment reset(Reason reason, Request request) {
            return new Assessment(true, true, reason, 0,
                    request.totalBudgetBytes(), request.forwardBytes(),
                    request.memoryPressure(), request.pressureUsable(),
                    request.snapshotUsable(), request.memorySampleAtElapsedMs(),
                    true, false);
        }
    }
}
