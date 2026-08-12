package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/* loaded from: classes3.dex */
public abstract class AbstractMediaPlayer implements IMediaPlayer {
    private IMediaPlayer.Listener mListener;

    public final void notifyOnBufferingUpdate(int i8) {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onBufferingUpdate((IMediaPlayer) this, i8);
        }
    }

    public final void notifyOnBufferingUpdate(long j8) {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onBufferingUpdate(this, j8);
        }
    }

    public final void notifyOnCompletion() {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onCompletion(this);
        }
    }

    public final boolean notifyOnError(int i8, int i9) {
        IMediaPlayer.Listener listener = this.mListener;
        return listener != null && listener.onError(this, i8, i9);
    }

    public final void notifyOnInfo(int i8, int i9) {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onInfo(this, i8, i9);
        }
    }

    public final void notifyOnPrepared() {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onPrepared(this);
        }
    }

    public final void notifyOnTimedText(IjkTimedText ijkTimedText) {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onTimedText(this, ijkTimedText);
        }
    }

    public final void notifyOnVideoSizeChanged(int i8, int i9, int i10, int i11) {
        IMediaPlayer.Listener listener = this.mListener;
        if (listener != null) {
            listener.onVideoSizeChanged(this, i8, i9, i10, i11);
        }
    }

    public void resetListeners() {
        this.mListener = null;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer
    public void setDataSource(IMediaDataSource iMediaDataSource) {
        throw new UnsupportedOperationException();
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer
    public final IMediaPlayer setListener(IMediaPlayer.Listener listener) {
        this.mListener = listener;
        return this;
    }
}
