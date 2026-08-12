package com.fongmi.android.tv.player.exo;

/**
 * Immutable capability snapshot for one playback session.
 *
 * <p>The source and network fields are intentionally conservative until a source-specific
 * provider is available. They make the decision boundary explicit without pretending that a
 * URL alone describes a random-access cloud object or a measured network path.</p>
 */
public final class ExoPlaybackCapability {

    private ExoPlaybackCapability() {
    }

    public record Report(DisplayCapability display, DecoderCapability decoder, SourceCapability source, NetworkCapability network) {
        public static Report deviceOnly(DisplayCapability display, DecoderCapability decoder) {
            return new Report(display, decoder, SourceCapability.unknown(), NetworkCapability.unknown());
        }
    }

    public record DisplayCapability(int maxWidth, int maxHeight, int currentWidth, int currentHeight, float currentRefreshRate) {
    }

    public record DecoderCapability(String name, String mimeType, int width, int height, int frameRate, int bitrate, int maxVideoBitrate, boolean hardwareAccelerated, boolean performancePoint) {
        public boolean supported() {
            return name != null && !name.isEmpty() && !"none".equals(name);
        }
    }

    public record SourceCapability(int width, int height, float frameRate, long bitrate, String container, boolean seekable) {
        public static SourceCapability unknown() {
            return new SourceCapability(0, 0, 0, 0, "", false);
        }
    }

    public record NetworkCapability(long estimatedBitrate, boolean directRange, boolean rangeSupported) {
        public static NetworkCapability unknown() {
            return new NetworkCapability(0, false, false);
        }
    }
}
