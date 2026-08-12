package com.fongmi.android.tv.player.exo;

final class ExoAdaptiveVideoBitratePolicy {

    static final int SAFETY_NUMERATOR = 4;
    static final int SAFETY_DENOMINATOR = 5;
    static final int MIN_TRACK_BITRATE = 1_000_000;

    private ExoAdaptiveVideoBitratePolicy() {
    }

    static boolean shouldDowngrade(int profileBitrate, long bitrateEstimate) {
        if (profileBitrate <= 0 || bitrateEstimate <= 0) return false;
        long requiredScaled = (long) profileBitrate * SAFETY_DENOMINATOR;
        if (bitrateEstimate > Long.MAX_VALUE / SAFETY_NUMERATOR) return false;
        return bitrateEstimate * SAFETY_NUMERATOR < requiredScaled;
    }

    static int resolveTrackBitrateCap(int currentMaxVideoBitrate, long bitrateEstimate) {
        if (currentMaxVideoBitrate <= 0 || bitrateEstimate <= 0) return currentMaxVideoBitrate;
        long scaledEstimate = multiplyThenDivide(bitrateEstimate, SAFETY_NUMERATOR, SAFETY_DENOMINATOR);
        long safeEstimate = Math.max(MIN_TRACK_BITRATE, scaledEstimate);
        return (int) Math.min(currentMaxVideoBitrate, Math.min(Integer.MAX_VALUE, safeEstimate));
    }

    static SelectionCheck checkSelectedTrack(int requestedCap, int selectedBitrate) {
        int normalizedCap = Math.max(0, requestedCap);
        int normalizedSelected = Math.max(0, selectedBitrate);
        Status status;
        if (normalizedCap == 0 || normalizedSelected == 0) status = Status.UNKNOWN;
        else if (normalizedSelected <= normalizedCap) status = Status.WITHIN_CAP;
        else status = Status.EXCEEDS_CAP;
        return new SelectionCheck(normalizedCap, normalizedSelected, status);
    }

    private static long multiplyThenDivide(long value, int multiplier, int divisor) {
        long quotient = value / divisor;
        long remainder = value % divisor;
        return quotient * multiplier + remainder * multiplier / divisor;
    }

    enum Status {
        UNKNOWN("unknown"),
        WITHIN_CAP("within-cap"),
        EXCEEDS_CAP("exceeds-cap");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record SelectionCheck(int requestedCap, int selectedBitrate, Status status) {
    }
}
