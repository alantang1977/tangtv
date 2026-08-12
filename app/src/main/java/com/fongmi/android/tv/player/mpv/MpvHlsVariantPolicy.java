package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackThroughputHistory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure policy for MPV HLS initial selection and one-step downgrade evidence. */
public final class MpvHlsVariantPolicy {

    public static final long CONSERVATIVE_INITIAL_BITS_PER_SECOND = 15_000_000L;
    public static final int TRUSTED_THROUGHPUT_SAFETY_PERCENT = 75;
    public static final int RUNTIME_SHORTFALL_PERCENT = 90;
    public static final long LOW_BUFFER_MS = 8_000L;
    public static final long DECLINING_BUFFER_CEILING_MS = 15_000L;
    public static final long MAX_NUMERIC_OPTION = Integer.MAX_VALUE;

    private MpvHlsVariantPolicy() {
    }

    public static InitialAssessment resolveInitial(
            boolean automatic,
            boolean mpvKernel,
            boolean performanceOptionsPriority,
            PlaybackThroughputHistory.Match history) {
        if (!automatic) return InitialAssessment.inactive(Reason.NOT_AUTOMATIC);
        if (!mpvKernel) return InitialAssessment.inactive(Reason.NOT_MPV);
        if (!performanceOptionsPriority) {
            return InitialAssessment.inactive(Reason.CONFIG_PRIORITY);
        }
        PlaybackThroughputHistory.Match match = history == null
                ? PlaybackThroughputHistory.Match.unavailable(
                PlaybackThroughputHistory.Reason.NO_MATCH) : history;
        if (match.usable() && match.bitsPerSecond() > 0) {
            long ceiling = percent(match.bitsPerSecond(),
                    TRUSTED_THROUGHPUT_SAFETY_PERCENT);
            ceiling = Math.clamp(ceiling, 1, MAX_NUMERIC_OPTION);
            return new InitialAssessment(true, optionFor(ceiling), ceiling,
                    match.bitsPerSecond(), match.ageMs(), match.pathKind(),
                    match.confidence(), Reason.TRUSTED_HISTORY);
        }
        return new InitialAssessment(true,
                optionFor(CONSERVATIVE_INITIAL_BITS_PER_SECOND),
                CONSERVATIVE_INITIAL_BITS_PER_SECOND,
                0, 0, PlaybackAutoContext.PathKind.UNKNOWN,
                PlaybackAutoContext.Confidence.UNKNOWN,
                Reason.CONSERVATIVE_BOOTSTRAP);
    }

    public static Assessment assess(RuntimeRequest request) {
        RuntimeRequest safe = request == null ? RuntimeRequest.inactive() : request;
        if (!safe.automatic()) return Assessment.inactive(Reason.NOT_AUTOMATIC);
        if (!safe.mpvKernel()) return Assessment.inactive(Reason.NOT_MPV);
        if (!safe.performanceOptionsPriority()) {
            return Assessment.inactive(Reason.CONFIG_PRIORITY);
        }
        if (!safe.protocolUsable()
                || safe.protocol() != PlaybackAutoContext.Protocol.HLS) {
            return Assessment.inactive(safe.protocolUsable()
                    ? Reason.NOT_HLS : Reason.PROTOCOL_UNKNOWN);
        }
        if (!safe.streamKindUsable()
                || !reloadableStream(safe.streamKind())) {
            return Assessment.inactive(safe.streamKindUsable()
                    ? Reason.STREAM_NOT_RELOADABLE : Reason.STREAM_UNKNOWN);
        }
        Ladder ladder = Ladder.resolve(safe.variants(), safe.selectedVariant());
        if (!ladder.selectedKnown()) {
            return Assessment.inactive(Reason.SELECTED_VARIANT_UNKNOWN);
        }
        Variant next = ladder.nextLower();
        if (next == null) {
            return new Assessment(true, Reason.LOWEST_VARIANT,
                    ladder.selected(), null, false, false, false,
                    safe.rawThroughputBitsPerSecond(), safe.bufferedDurationMs());
        }
        long selectedBits = ladder.selected().selectionBitsPerSecond();
        boolean throughputShortfall = safe.rawThroughputUsable()
                && safe.rawThroughputBitsPerSecond()
                <= percent(selectedBits, RUNTIME_SHORTFALL_PERCENT);
        boolean bufferRisk = safe.buffering()
                || safe.bufferUsable()
                && (safe.bufferedDurationMs() <= LOW_BUFFER_MS
                || safe.bufferDeclining()
                && safe.bufferedDurationMs() <= DECLINING_BUFFER_CEILING_MS);
        boolean hardRisk = safe.underrunRisk()
                || safe.rebufferRisk()
                || safe.buffering();
        Reason reason = hardRisk && throughputShortfall && bufferRisk
                ? Reason.DOWNGRADE_EVIDENCE : Reason.EVIDENCE_INCOMPLETE;
        return new Assessment(true, reason, ladder.selected(), next,
                hardRisk, throughputShortfall, bufferRisk,
                safe.rawThroughputBitsPerSecond(), safe.bufferedDurationMs());
    }

    public static String optionFor(long bitsPerSecond) {
        long value = Math.clamp(bitsPerSecond, 0, MAX_NUMERIC_OPTION);
        return Long.toString(value);
    }

    public static boolean reloadableStream(PlaybackAutoContext.StreamKind streamKind) {
        return streamKind == PlaybackAutoContext.StreamKind.VOD
                || streamKind == PlaybackAutoContext.StreamKind.LIVE
                || streamKind == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
    }

    private static long percent(long value, int percent) {
        if (value <= 0 || percent <= 0) return 0;
        if (value > Long.MAX_VALUE / percent) return Long.MAX_VALUE / 100L;
        return value * percent / 100L;
    }

    public record InitialAssessment(
            boolean active,
            String option,
            long ceilingBitsPerSecond,
            long trustedThroughputBitsPerSecond,
            long evidenceAgeMs,
            PlaybackAutoContext.PathKind pathKind,
            PlaybackAutoContext.Confidence confidence,
            Reason reason) {

        public InitialAssessment {
            option = option == null ? "" : option;
            ceilingBitsPerSecond = Math.max(0, ceilingBitsPerSecond);
            trustedThroughputBitsPerSecond = Math.max(0,
                    trustedThroughputBitsPerSecond);
            evidenceAgeMs = Math.max(0, evidenceAgeMs);
            pathKind = pathKind == null
                    ? PlaybackAutoContext.PathKind.UNKNOWN : pathKind;
            confidence = confidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            reason = reason == null ? Reason.CONSERVATIVE_BOOTSTRAP : reason;
        }

        static InitialAssessment inactive(Reason reason) {
            return new InitialAssessment(false, "", 0, 0, 0,
                    PlaybackAutoContext.PathKind.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN, reason);
        }
    }

    public record RuntimeRequest(
            boolean automatic,
            boolean mpvKernel,
            boolean performanceOptionsPriority,
            PlaybackAutoContext.Protocol protocol,
            boolean protocolUsable,
            PlaybackAutoContext.StreamKind streamKind,
            boolean streamKindUsable,
            List<Variant> variants,
            Variant selectedVariant,
            boolean underrunRisk,
            boolean rebufferRisk,
            boolean buffering,
            boolean bufferDeclining,
            boolean bufferUsable,
            long bufferedDurationMs,
            long rawThroughputBitsPerSecond,
            boolean rawThroughputUsable) {

        public RuntimeRequest {
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            variants = variants == null ? List.of() : List.copyOf(variants);
            bufferedDurationMs = Math.max(0, bufferedDurationMs);
            rawThroughputBitsPerSecond = Math.max(0,
                    rawThroughputBitsPerSecond);
        }

        static RuntimeRequest inactive() {
            return new RuntimeRequest(false, false, false,
                    PlaybackAutoContext.Protocol.UNKNOWN, false,
                    PlaybackAutoContext.StreamKind.UNKNOWN, false,
                    List.of(), null, false, false, false, false,
                    false, 0, 0, false);
        }
    }

    public record Assessment(
            boolean active,
            Reason reason,
            Variant selectedVariant,
            Variant nextLowerVariant,
            boolean hardRisk,
            boolean throughputShortfall,
            boolean bufferRisk,
            long rawThroughputBitsPerSecond,
            long bufferedDurationMs) {

        public Assessment {
            reason = reason == null ? Reason.EVIDENCE_INCOMPLETE : reason;
            rawThroughputBitsPerSecond = Math.max(0,
                    rawThroughputBitsPerSecond);
            bufferedDurationMs = Math.max(0, bufferedDurationMs);
        }

        public static Assessment inactive(Reason reason) {
            return new Assessment(false, reason, null, null,
                    false, false, false, 0, 0);
        }

        public long selectedBitsPerSecond() {
            return selectedVariant == null ? 0
                    : selectedVariant.selectionBitsPerSecond();
        }

        public long nextLowerBitsPerSecond() {
            return nextLowerVariant == null ? 0
                    : nextLowerVariant.selectionBitsPerSecond();
        }
    }

    public record Variant(
            long bandwidthBitsPerSecond,
            long averageBandwidthBitsPerSecond,
            int width,
            int height) {

        public Variant {
            bandwidthBitsPerSecond = Math.max(0, bandwidthBitsPerSecond);
            averageBandwidthBitsPerSecond = Math.max(0,
                    averageBandwidthBitsPerSecond);
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        public long selectionBitsPerSecond() {
            return bandwidthBitsPerSecond > 0
                    ? bandwidthBitsPerSecond : averageBandwidthBitsPerSecond;
        }
    }

    public enum Reason {
        TRUSTED_HISTORY("trusted-history"),
        CONSERVATIVE_BOOTSTRAP("conservative-bootstrap"),
        NOT_AUTOMATIC("not-automatic"),
        NOT_MPV("not-mpv"),
        CONFIG_PRIORITY("config-priority"),
        PROTOCOL_UNKNOWN("protocol-unknown"),
        NOT_HLS("not-hls"),
        STREAM_UNKNOWN("stream-unknown"),
        STREAM_NOT_RELOADABLE("stream-not-reloadable"),
        SELECTED_VARIANT_UNKNOWN("selected-variant-unknown"),
        LOWEST_VARIANT("lowest-variant"),
        EVIDENCE_INCOMPLETE("evidence-incomplete"),
        DOWNGRADE_EVIDENCE("downgrade-evidence");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private record Ladder(Variant selected, List<Variant> variants) {

        private static Ladder resolve(List<Variant> variants, Variant selected) {
            Map<Long, Variant> byBitrate = new LinkedHashMap<>();
            List<Variant> safe = variants == null ? List.of() : variants;
            for (Variant variant : safe) {
                if (variant == null || variant.selectionBitsPerSecond() <= 0) continue;
                byBitrate.putIfAbsent(variant.selectionBitsPerSecond(), variant);
            }
            List<Variant> sorted = new ArrayList<>(byBitrate.values());
            sorted.sort(Comparator.comparingLong(Variant::selectionBitsPerSecond));
            Variant resolvedSelected = selected == null
                    || selected.selectionBitsPerSecond() <= 0 ? null : selected;
            return new Ladder(resolvedSelected, List.copyOf(sorted));
        }

        private boolean selectedKnown() {
            return selected != null && selected.selectionBitsPerSecond() > 0;
        }

        private Variant nextLower() {
            if (!selectedKnown()) return null;
            long selectedBits = selected.selectionBitsPerSecond();
            Variant lower = null;
            for (Variant variant : variants) {
                long bitrate = variant.selectionBitsPerSecond();
                if (bitrate >= selectedBits) break;
                lower = variant;
            }
            return lower;
        }
    }
}
