package tv.danmaku.ijk.media.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.List;
import java.util.Map;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/* loaded from: classes3.dex */
public interface IMediaPlayer {
    public static final int MEDIA_ERROR_IO = -1004;
    public static final int MEDIA_ERROR_MALFORMED = -1007;
    public static final int MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK = 200;
    public static final int MEDIA_ERROR_SERVER_DIED = 100;
    public static final int MEDIA_ERROR_TIMED_OUT = -110;
    public static final int MEDIA_ERROR_UNKNOWN = 1;
    public static final int MEDIA_ERROR_UNSUPPORTED = -1010;
    public static final int MEDIA_INFO_AUDIO_DECODED_START = 10003;
    public static final int MEDIA_INFO_AUDIO_RENDERING_START = 10002;
    public static final int MEDIA_INFO_AUDIO_SEEK_RENDERING_START = 10009;
    public static final int MEDIA_INFO_BAD_INTERLEAVING = 800;
    public static final int MEDIA_INFO_BUFFERING_END = 702;
    public static final int MEDIA_INFO_BUFFERING_START = 701;
    public static final int MEDIA_INFO_COMPONENT_OPEN = 10007;
    public static final int MEDIA_INFO_FIND_STREAM_INFO = 10006;
    public static final int MEDIA_INFO_MEDIA_ACCURATE_SEEK_COMPLETE = 10100;
    public static final int MEDIA_INFO_METADATA_UPDATE = 802;
    public static final int MEDIA_INFO_NETWORK_BANDWIDTH = 703;
    public static final int MEDIA_INFO_NOT_SEEKABLE = 801;
    public static final int MEDIA_INFO_OPEN_INPUT = 10005;
    public static final int MEDIA_INFO_STARTED_AS_NEXT = 2;
    public static final int MEDIA_INFO_SUBTITLE_TIMED_OUT = 902;
    public static final int MEDIA_INFO_TIMED_TEXT_ERROR = 900;
    public static final int MEDIA_INFO_UNKNOWN = 1;
    public static final int MEDIA_INFO_UNSUPPORTED_SUBTITLE = 901;
    public static final int MEDIA_INFO_VIDEO_DECODED_START = 10004;
    public static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;
    public static final int MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001;
    public static final int MEDIA_INFO_VIDEO_SEEK_RENDERING_START = 10008;
    public static final int MEDIA_INFO_VIDEO_TRACK_LAGGING = 700;

    public interface Listener {
        void onBufferingUpdate(IMediaPlayer iMediaPlayer, int i8);

        void onBufferingUpdate(IMediaPlayer iMediaPlayer, long j8);

        void onCompletion(IMediaPlayer iMediaPlayer);

        boolean onError(IMediaPlayer iMediaPlayer, int i8, int i9);

        void onInfo(IMediaPlayer iMediaPlayer, int i8, int i9);

        void onPrepared(IMediaPlayer iMediaPlayer);

        void onTimedText(IMediaPlayer iMediaPlayer, IjkTimedText ijkTimedText);

        void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int i8, int i9, int i10, int i11);
    }

    void deselectTrack(int i8);

    int getAudioSessionId();

    boolean getCurrentFrame(Bitmap bitmap);

    long getCurrentPosition();

    long getDuration();

    int getSelectedTrack(int i8);

    float getSpeed();

    List<ITrackInfo> getTrackInfo();

    int getVideoHeight();

    int getVideoSarDen();

    int getVideoSarNum();

    int getVideoWidth();

    boolean isLooping();

    @Deprecated
    boolean isPlayable();

    boolean isPlaying();

    void pause();

    void prepareAsync();

    void release();

    void reset();

    void seekTo(long j8);

    void selectTrack(int i8);

    void setAudioStreamType(int i8);

    void setDataSource(Context context, Uri uri, Map<String, String> map);

    void setDataSource(String str);

    void setDataSource(IMediaDataSource iMediaDataSource);

    void setDisplay(SurfaceHolder surfaceHolder);

    @Deprecated
    void setKeepInBackground(boolean z6);

    IMediaPlayer setListener(Listener listener);

    @Deprecated
    void setLogEnabled(boolean z6);

    void setLooping(boolean z6);

    void setOption(int i8, String str, long j8);

    void setOption(int i8, String str, String str2);

    void setScreenOnWhilePlaying(boolean z6);

    void setSpeed(float f);

    void setSurface(Surface surface);

    void setVolume(float f, float f4);

    void setWakeMode(Context context, int i8);

    void start();

    void stop();
}
