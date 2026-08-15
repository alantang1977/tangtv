package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;

/** Session-local evidence set only by the renderer that actually opens the HDR10 fallback codec. */
public final class ExoDolbyVisionPlaybackState {

    private volatile Snapshot snapshot = Snapshot.inactive();

    public void activate(Format sourceFormat, Format outputFormat) {
        snapshot = new Snapshot(true, sourceFormat, outputFormat);
    }

    public void reset() {
        snapshot = Snapshot.inactive();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(
            boolean hdr10FallbackActive,
            @Nullable Format sourceFormat,
            @Nullable Format outputFormat) {

        private static Snapshot inactive() {
            return new Snapshot(false, null, null);
        }
    }
}
