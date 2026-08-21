package androidx.media3.mpvplayer;

final class MpvOsdSurfacePolicy {

    private MpvOsdSurfacePolicy() {
    }

    static boolean requiresSurface(boolean subtitlesVisible,
                                   String primaryCurrent,
                                   String primarySelection,
                                   String secondaryCurrent,
                                   String secondarySelection) {
        if (!subtitlesVisible) return false;
        return isSelected(primaryCurrent)
                || isSelected(primarySelection)
                || isSelected(secondaryCurrent)
                || isSelected(secondarySelection);
    }

    private static boolean isSelected(String value) {
        if (value == null) return false;
        String normalized = value.trim();
        return !normalized.isEmpty()
                && !"no".equalsIgnoreCase(normalized)
                && !"none".equalsIgnoreCase(normalized)
                && !"auto".equalsIgnoreCase(normalized);
    }
}
