package com.fongmi.android.tv.player.exo;

import java.util.Comparator;
import java.util.List;

/** Pure display-mode selection policy. Android window changes are handled by a lifecycle owner. */
public final class ExoOutputModePolicy {

    private static final int MAX_REFRESH_MULTIPLIER = 5;
    private static final int CADENCE_TOLERANCE_MILLI_HZ = 60;

    private ExoOutputModePolicy() {
    }

    public static Decision select(List<Mode> modes, int currentModeId, Content content, Policy policy) {
        if (modes == null || modes.isEmpty()) return Decision.none("no-modes");
        Mode current = findMode(modes, currentModeId);
        if (content == null || content.frameRateMilliHz() <= 0) return new Decision(current, false, "unknown-fps");
        Policy resolvedPolicy = policy == null ? Policy.frameRateOnly() : policy;
        List<Mode> candidates = modes.stream()
                .filter(mode -> resolvedPolicy.allowResolutionChange() || current == null || mode.sameResolution(current))
                .toList();
        if (candidates.isEmpty()) return new Decision(current, false, "no-compatible-mode");
        Comparator<Mode> comparator = comparator(current, content, resolvedPolicy);
        Mode selected = candidates.stream().min(comparator).orElse(current);
        return new Decision(selected, current == null || selected.id() != current.id(), "selected");
    }

    private static Comparator<Mode> comparator(Mode current, Content content, Policy policy) {
        Comparator<Mode> cadence = Comparator
                .comparingInt((Mode mode) -> cadence(mode.refreshRateMilliHz(), content.frameRateMilliHz()).rank())
                .thenComparingInt(mode -> cadence(mode.refreshRateMilliHz(), content.frameRateMilliHz()).errorMilliHz());
        Comparator<Mode> resolution = Comparator
                .comparingLong((Mode mode) -> resolutionPenalty(mode, content))
                .thenComparingInt(mode -> current != null && mode.sameResolution(current) ? 0 : 1);
        Comparator<Mode> switchCost = Comparator
                .comparingInt((Mode mode) -> current == null || mode.sameResolution(current) ? 0 : 1)
                .thenComparingLong(mode -> resolutionPenalty(mode, content));
        Comparator<Mode> tail = Comparator
                .comparingInt((Mode mode) -> mode.id() == (current == null ? -1 : current.id()) ? 0 : 1)
                .thenComparingInt(Mode::id);
        return cadence.thenComparing(policy.preferContentResolution() ? resolution : switchCost).thenComparing(tail);
    }

    private static Cadence cadence(int refreshRateMilliHz, int frameRateMilliHz) {
        int bestError = Integer.MAX_VALUE;
        int bestMultiplier = 0;
        for (int multiplier = 1; multiplier <= MAX_REFRESH_MULTIPLIER; multiplier++) {
            int error = Math.abs(refreshRateMilliHz - frameRateMilliHz * multiplier);
            if (error < bestError) {
                bestError = error;
                bestMultiplier = multiplier;
            }
        }
        if (bestError <= CADENCE_TOLERANCE_MILLI_HZ) return new Cadence(bestMultiplier == 1 ? 0 : 1, bestError);
        return new Cadence(2, bestError);
    }

    private static long resolutionPenalty(Mode mode, Content content) {
        long modeArea = (long) mode.width() * mode.height();
        long contentArea = (long) Math.max(content.width(), content.height()) * Math.min(content.width(), content.height());
        if (contentArea <= 0) return 0;
        if (mode.width() == Math.max(content.width(), content.height()) && mode.height() == Math.min(content.width(), content.height())) return 0;
        if (modeArea >= contentArea) return modeArea - contentArea;
        return (contentArea - modeArea) * 2L;
    }

    private static Mode findMode(List<Mode> modes, int id) {
        for (Mode mode : modes) if (mode.id() == id) return mode;
        return null;
    }

    public record Mode(int id, int width, int height, int refreshRateMilliHz) {
        public Mode {
            int landscapeWidth = Math.max(width, height);
            int landscapeHeight = Math.min(width, height);
            width = landscapeWidth;
            height = landscapeHeight;
        }

        public static Mode of(int id, int width, int height, float refreshRate) {
            return new Mode(id, width, height, Math.round(refreshRate * 1_000f));
        }

        private boolean sameResolution(Mode other) {
            return other != null && width == other.width && height == other.height;
        }
    }

    public record Content(int width, int height, int frameRateMilliHz) {
        public static Content of(int width, int height, float frameRate) {
            return new Content(width, height, Math.round(frameRate * 1_000f));
        }
    }

    public record Policy(boolean allowResolutionChange, boolean preferContentResolution) {
        public static Policy frameRateOnly() {
            return new Policy(false, false);
        }

        public static Policy resolutionAndRate() {
            return new Policy(true, true);
        }
    }

    public record Decision(Mode mode, boolean changeRequired, String reason) {
        private static Decision none(String reason) {
            return new Decision(null, false, reason);
        }
    }

    private record Cadence(int rank, int errorMilliHz) {
    }
}
