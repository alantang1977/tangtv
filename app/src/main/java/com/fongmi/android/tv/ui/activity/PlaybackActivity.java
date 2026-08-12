package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.VideoAspectMode;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.exo.ExoOutputModeManager;
import com.fongmi.android.tv.player.exo.ExoOutputModePolicy;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.subtitle.RealtimeSubtitleController;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.VideoAspectModeDialog;
import com.fongmi.android.tv.ui.custom.CustomSeekView;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.function.IntConsumer;

public abstract class PlaybackActivity extends BaseActivity implements MediaController.Listener, Player.Listener, ServiceConnection {

    private static final String SIZE_TAG = "MPV_SIZE";

    private ListenableFuture<MediaController> mControllerFuture;
    private MediaController mController;
    private PlaybackService mService;
    private boolean audioOnly;
    private boolean redirect;
    private boolean playbackExiting;
    private String preparedPlaybackKey;
    private boolean bound;
    private boolean stop;
    private boolean lock;
    private int render = -1;
    private int requestedAspectMode = VideoAspectMode.ORIGINAL;
    private ExoOutputModeManager exoOutputModeManager;

    protected MediaController controller() {
        return mController;
    }

    protected PlaybackService service() {
        return mService;
    }

    protected PlayerManager player() {
        return mService == null ? null : mService.player();
    }

    protected boolean isServiceReady() {
        return mService != null && mService.player() != null && !mService.player().isReleased();
    }

    protected View.OnClickListener guarded(Runnable action) {
        return v -> {
            if (isServiceReady()) action.run();
        };
    }

    protected View.OnClickListener guardedView(java.util.function.Consumer<View> action) {
        return v -> {
            if (isServiceReady()) action.accept(v);
        };
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (mService == null) return;
        if (redirect) mService.clearNavigationCallback(getNavigationCallback());
        else mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
    }

    protected boolean isPlaybackExiting() {
        return playbackExiting;
    }

    protected void markPlaybackExiting() {
        this.playbackExiting = true;
    }

    protected void finishPlayback() {
        markPlaybackExiting();
        stopPlayback();
        finish();
    }

    protected void stopPlayback() {
        if (mService != null && isOwner()) {
            mService.shutdown();
        } else if (mController != null) {
            mController.stop();
        }
    }

    protected void updateNavigationKey() {
        if (mService != null) mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract CustomSeekView getSeekView();

    protected abstract PlayerView getExoView();

    protected abstract String getPlaybackKey();

    protected boolean deferPlaybackServiceBinding() {
        return false;
    }

    protected boolean isOwner() {
        String key = getPlaybackKey();
        PlayerManager manager = player();
        return key == null || (manager != null && key.equals(manager.getKey()));
    }

    protected boolean isIdle() {
        return mController.getPlaybackState() == Player.STATE_IDLE;
    }

    protected boolean isEnded() {
        return mController.getPlaybackState() == Player.STATE_ENDED;
    }

    protected boolean isBuffering() {
        return mController.getPlaybackState() == Player.STATE_BUFFERING;
    }

    protected boolean isPaused() {
        return !isBuffering() && !isIdle();
    }

    protected void onServiceConnected() {
    }

    protected boolean isLutAllowed() {
        return true;
    }

    protected void onPrepare() {
    }

    protected void onPlayerRebuilt() {
    }

    protected void onTracksChanged() {
    }

    protected void onTitlesChanged() {
    }

    protected boolean onSourceHttpError(int statusCode, String msg) {
        return false;
    }

    protected void onControllerReady(Player controller) {
    }

    protected void onPlayerPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
    }

    protected void onError(String msg) {
    }

    protected void onReload(String msg) {
        onError(msg);
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onSurfaceAttached() {
    }

    protected void onFirstFrameRendered() {
    }

    protected void onSeekStarted() {
    }

    protected void showResizeModeDialog(int currentMode, IntConsumer action) {
        if (action == null) return;
        VideoAspectModeDialog.show(this, currentMode, action);
    }

    protected void applyResizeMode(int resizeMode) {
        int mode = VideoAspectMode.sanitize(resizeMode);
        requestedAspectMode = mode;
        applyResizeModeNow(mode);
        PlayerView view = getExoView();
        view.post(() -> {
            if (requestedAspectMode == mode && !isFinishing() && !isDestroyed()) applyResizeModeNow(mode);
        });
    }

    private void applyResizeModeNow(int resizeMode) {
        PlayerView view = getExoView();
        VideoAspectMode.Spec spec = VideoAspectMode.resolve(resizeMode, viewportAspectRatio(view), PlayerSetting.getCustomAspectRatio());
        int effectiveResizeMode = effectiveResizeMode(spec.resizeMode());
        logSurfaceState("applyResizeMode before mode=" + resizeMode + " effective=" + effectiveResizeMode + " aspect=" + spec.targetAspectRatio());
        if (mService != null) player().setVideoAspect(spec.targetAspectRatio(), spec.stretch());
        view.setResizeMode(effectiveResizeMode);
        AspectRatioFrameLayout content = view.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        float contentAspectRatio = spec.hasTargetAspectRatio() ? spec.targetAspectRatio() : sourceAspectRatio();
        if (!VideoAspectMode.isValidRatio(contentAspectRatio)) contentAspectRatio = 0f;
        if (content != null) content.setAspectRatio(contentAspectRatio);
        view.requestLayout();
        View surface = view.getVideoSurfaceView();
        if (surface != null) surface.requestLayout();
        logSurfaceState("applyResizeMode after mode=" + resizeMode + " effective=" + effectiveResizeMode + " aspect=" + contentAspectRatio);
    }

    private float viewportAspectRatio(PlayerView view) {
        View container = view.getParent() instanceof View parent ? parent : view;
        int width = container.getWidth();
        int height = container.getHeight();
        if (width <= 0 || height <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
            height = getResources().getDisplayMetrics().heightPixels;
        }
        return width > 0 && height > 0 ? (float) width / height : 0f;
    }

    private float sourceAspectRatio() {
        if (mService == null || player() == null || player().getPlayer() == null) return 0f;
        VideoSize size = player().getPlayer().getVideoSize();
        return size.width > 0 && size.height > 0 ? size.width * size.pixelWidthHeightRatio / size.height : 0f;
    }

    private int effectiveResizeMode(int resizeMode) {
        if (mService != null && player().isMpv() && !player().isMpvSurfaceDirect() && resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            return AspectRatioFrameLayout.RESIZE_MODE_FILL;
        }
        return resizeMode;
    }

    protected void onReclaim() {
    }

    protected boolean shouldBindPlaybackService() {
        return true;
    }

    protected boolean shouldPauseOnBackground() {
        return PlayerSetting.isBackgroundOff();
    }

    protected boolean seekTo(long deltaMs) {
        onSeekStarted();
        long targetMs = Math.max(0, player().getPosition() + deltaMs);
        long durationMs = player().getDuration();
        boolean seekToEnd = durationMs > 0 && targetMs >= durationMs;
        mController.seekTo(seekToEnd ? durationMs : targetMs);
        if (!seekToEnd) mController.play();
        return seekToEnd;
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        if (rejectUnsupportedDrm(key, result)) {
            return;
        } else if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            onError(result.getMsg());
        } else if (result.getRealUrl().isEmpty()) {
            onError(ResUtil.getString(R.string.error_play_url));
        } else if (result.needParse() || useParse) {
            preparedPlaybackKey = null;
            attachSurface();
            player().parse(key, result, useParse, metadata, PlayerSetting.isAutoPlay());
        } else {
            preparedPlaybackKey = null;
            attachSurface();
            player().start(PlaySpec.from(result, key, metadata), timeout, PlayerSetting.isAutoPlay());
        }
        syncKeepScreenOn();
    }

    private boolean rejectUnsupportedDrm(String key, Result result) {
        if (result == null || result.getDrm() == null || !isSelectedMpvPlayer()) return false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "reject drm for mpv key=%s drm=%s", key, result.getDrm().getType());
        onError(ResUtil.getString(R.string.error_play_mpv_drm_unsupported));
        return true;
    }

    private boolean isSelectedMpvPlayer() {
        return mService != null ? player().isMpv() : PlayerSetting.getPlayer() == PlayerSetting.MPV;
    }

    private void bindPlaybackService() {
        if (bound || shouldRejectPlaybackConnection()) return;
        long start = System.currentTimeMillis();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "bind service start key=%s", getPlaybackKey());
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        bound = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "bind service requested cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
    }

    private void bindPlaybackServiceAfterFirstFrame() {
        View root = getExoView().getRootView();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(() -> {
                    if (!isFinishing() && !isDestroyed()) bindPlaybackService();
                });
                return true;
            }
        });
    }

    private void buildControllerAsync(SessionToken token) {
        if (mControllerFuture != null || shouldRejectPlaybackConnection()) return;
        if (token == null) {
            handleControllerConnectionFailure(new IllegalStateException("Playback session token is unavailable"));
            return;
        }
        long start = System.currentTimeMillis();
        try {
            mControllerFuture = new MediaController.Builder(this, token).setListener(this).buildAsync();
            mControllerFuture.addListener(this::handleControllerConnected, ContextCompat.getMainExecutor(this));
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "controller build requested cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
        } catch (RuntimeException e) {
            handleControllerConnectionFailure(e);
        }
    }

    private void handleControllerConnectionFailure(Exception e) {
        SpiderDebug.log("playback-flow", e);
        releaseController();
        if (!shouldRejectPlaybackConnection()) finishPlayback();
    }

    protected void onControllerConnected() {
    }

    protected void onControllerReadyReconciled() {
    }

    private void reconcileControllerReadyState() {
        PlayerManager manager = player();
        if (mController == null || manager == null) return;
        MediaItem managerItem = manager.getCurrentMediaItem();
        MediaItem controllerItem = mController.getCurrentMediaItem();
        String managerMediaId = managerItem == null ? null : managerItem.mediaId;
        String controllerMediaId = controllerItem == null ? null : controllerItem.mediaId;
        if (!PlaybackStateReconciliation.shouldReplayReady(getPlaybackKey(), preparedPlaybackKey, manager.getKey(), managerMediaId, controllerMediaId, manager.getPlaybackState(), mController.getPlaybackState())) return;
        onControllerReadyReconciled();
    }

    private void handleControllerConnected() {
        if (shouldRejectPlaybackConnection() || mControllerFuture == null) return;
        long start = System.currentTimeMillis();
        try {
            mController = mControllerFuture.get();
            getSeekView().setPlayer(mController);
            getSeekView().setSeekListener(this::onSeekStarted);
            onControllerReady(mController);
            mController.addListener(this);
            reconcileControllerReadyState();
        } catch (Exception e) {
            handleControllerConnectionFailure(e);
            return;
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "controller connected cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
        syncKeepScreenOn();
        if (mController != null) onControllerConnected();
    }

    private boolean shouldRejectPlaybackConnection() {
        return playbackExiting || isFinishing() || isDestroyed();
    }

    private PendingIntent buildSessionIntent() {
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        Bundle extras = getIntent().getExtras();
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean shouldReclaim() {
        return mService != null && !isOwner();
    }

    private void closePiP() {
        if (!isInPictureInPictureMode()) return;
        detach();
        finish();
    }

    private void attachSurface() {
        attachSurface(true);
    }

    private void attachSurface(boolean restoreExoShutter) {
        if (mService == null) return;
        int targetRender = getRender();
        logSurfaceState("attach start target=" + targetRender);
        if (restoreExoShutter) syncShutter(true);
        else hideVideoShutter();
        if (render != targetRender) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "switch render from=%d to=%d", render, targetRender);
            if (getExoView().getPlayer() != null) getExoView().setPlayer(null);
            getExoView().setRender(targetRender);
            render = targetRender;
            if (restoreExoShutter) syncShutter(true);
            else hideVideoShutter();
            logSurfaceState("attach after setRender target=" + targetRender);
        }
        if (getExoView().getPlayer() == null) {
            getExoView().setPlayer(player().getPlayer());
            logSurfaceState("attach after setPlayer");
            syncVideoSurfaceSize(null);
            if (restoreExoShutter) syncShutter();
            else hideVideoShutter();
            if (player().isNativePlayer()) getExoView().post(this::syncShutter);
        }
        publishRenderTarget(getExoView().getVideoSurfaceView());
        onSurfaceAttached();
        logSurfaceState("attach done");
    }

    private void syncVideoSurfaceSize(VideoSize size) {
        if (mService == null) return;
        View surface = getExoView().getVideoSurfaceView();
        if (!(surface instanceof SurfaceView surfaceView)) return;
        if (!PlaybackPerformanceSetting.isSurfaceFixedSizeEnabled() || getRender() != PlayerSetting.RENDER_SURFACE || player().isNativePlayer()) {
            surfaceView.getHolder().setSizeFromLayout();
            logSurfaceState("syncVideoSurfaceSize layout size=" + (size == null ? "null" : size.width + "x" + size.height));
            return;
        }
        int width = size != null && size.width > 0 ? size.width : player().getVideoWidth();
        int height = size != null && size.height > 0 ? size.height : player().getVideoHeight();
        if (width <= 0 || height <= 0) return;
        ExoUtil.EnhancedVideoProfile profile = ExoUtil.getEnhancedVideoProfile();
        float scale = Math.min((float) profile.width() / width, (float) profile.height() / height);
        if (scale < 1f) {
            width = Math.max(1, Math.round(width * scale));
            height = Math.max(1, Math.round(height * scale));
        }
        surfaceView.getHolder().setFixedSize(width, height);
        logSurfaceState("syncVideoSurfaceSize fixed=" + width + "x" + height);
    }

    private void logSurfaceState(String step) {
        PlayerView view = getExoView();
        if (view == null) return;
        View surface = view.getVideoSurfaceView();
        View content = view.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        String playerText = mService == null ? "none" : player().getPlayerText();
        boolean nativePlayer = mService != null && player().isNativePlayer();
        int targetRender = mService == null ? -1 : getRender();
        Log.d(SIZE_TAG, "playback " + step
                + " key=" + getPlaybackKey()
                + " player=" + playerText
                + " native=" + nativePlayer
                + " render=" + render
                + " target=" + targetRender
                + " resize=" + view.getResizeMode()
                + " playerView=" + viewSize(view)
                + " content=" + viewSize(content)
                + " surface=" + surfaceName(surface) + ":" + viewSize(surface));
    }

    private static String viewSize(View view) {
        if (view == null) return "null";
        return view.getWidth() + "x" + view.getHeight();
    }

    private static String surfaceName(View view) {
        return view == null ? "null" : view.getClass().getSimpleName();
    }

    private void syncShutter() {
        syncShutter(false);
    }

    private void syncShutter(boolean restoreExo) {
        if (mService == null) return;
        boolean nativePlayer = player().isNativePlayer();
        View shutter = getExoView().findViewById(androidx.media3.ui.R.id.exo_shutter);
        if (nativePlayer) {
            getExoView().setShutterBackgroundColor(Color.TRANSPARENT);
            if (shutter != null) shutter.setVisibility(View.GONE);
        } else if (restoreExo) {
            getExoView().setShutterBackgroundColor(Color.BLACK);
            if (shutter != null) shutter.setVisibility(View.VISIBLE);
        }
    }

    private void hideVideoShutter() {
        View shutter = getExoView().findViewById(androidx.media3.ui.R.id.exo_shutter);
        getExoView().setShutterBackgroundColor(Color.TRANSPARENT);
        if (shutter != null) shutter.setVisibility(View.GONE);
    }

    private void detachSurface() {
        getExoView().setPlayer(null);
        if (mService != null) player().publishPlaybackRenderTarget(PlaybackAutoContext.RenderTarget.DETACHED);
    }

    private void resetVideoSurfaceForDecoderSwitch() {
        int targetRender = getRender();
        int temporaryRender = targetRender == PlayerSetting.RENDER_TEXTURE ? PlayerSetting.RENDER_SURFACE : PlayerSetting.RENDER_TEXTURE;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "reset video surface for decoder switch temp=%d target=%d", temporaryRender, targetRender);
        getExoView().setPlayer(null);
        getExoView().setRender(temporaryRender);
        getExoView().setRender(targetRender);
        render = -1;
    }

    protected void reattachVideoSurfaceAfterReparent() {
        if (mService == null) return;
        PlayerView view = getExoView();
        view.setKeepContentOnPlayerReset(true);
        hideVideoShutter();
        detachSurface();
        boolean posted = view.post(() -> {
            if (mService != null && !isFinishing() && !isDestroyed()) attachSurface(false);
            view.setKeepContentOnPlayerReset(false);
        });
        if (!posted) view.setKeepContentOnPlayerReset(false);
    }

    protected void setRender() {
        render = -1;
        detachSurface();
        attachSurface();
    }

    private int getRender() {
        if (mService != null && player().isNativePlayer()) return 0;
        return PlayerSetting.getRender();
    }

    private void releasePlaybackService() {
        if (mService != null) releaseService(isOwner());
        detach();
    }

    private void releaseService(boolean owner) {
        mService.removePlayerCallback(mPlayerCallback);
        mService.clearNavigationCallback(getNavigationCallback());
        if (owner && mService.isKeepAlive()) {
            mService.resetSessionActivity();
        } else if (mService.hasExternalClient() || mService.hasPlayerCallback()) {
            if (owner) mService.suspend();
            mService.resetSessionActivity();
        } else if (owner) {
            mService.shutdown();
        }
    }

    private void detach() {
        releaseController();
        releaseBinding();
    }

    private void releaseController() {
        getSeekView().setPlayer(null);
        if (mController != null) mController.removeListener(this);
        if (mControllerFuture != null) MediaController.releaseFuture(mControllerFuture);
        mControllerFuture = null;
        mController = null;
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        if (mService != null) mService.removePlayerCallback(mPlayerCallback);
        getSeekView().setProgressPlayer(null);
        unbindService(this);
        mService = null;
    }

    private void syncKeepScreenOn() {
        if (shouldKeepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private boolean shouldKeepScreenOn() {
        if (!isOwner()) return false;
        PlayerManager manager = player();
        if (manager == null || manager.isReleased() || manager.isEmpty()) return false;
        Player active = mController != null ? mController : manager.getPlayer();
        int state = active.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return false;
        if (active.isPlaying()) return true;
        return active.getPlayWhenReady() && (state == Player.STATE_BUFFERING || state == Player.STATE_READY);
    }

    private String lifecycleState() {
        String playerKey = null;
        boolean released = true;
        if (mService != null && mService.player() != null) {
            released = mService.player().isReleased();
            if (!released) playerKey = mService.player().getKey();
        }
        return "activity=" + getClass().getSimpleName() +
                " key=" + getPlaybackKey() +
                " playerKey=" + playerKey +
                " owner=" + isOwner() +
                " bound=" + bound +
                " service=" + (mService != null) +
                " controller=" + (mController != null) +
                " released=" + released +
                " redirect=" + redirect +
                " stop=" + stop +
                " finishing=" + isFinishing() +
                " destroyed=" + isDestroyed() +
                " keepScreen=" + ((getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
    }

    private final PlaybackService.PlayerCallback mPlayerCallback = new PlaybackService.PlayerCallback() {

        @Override
        public void onPrepare() {
            if (isOwner()) {
                MediaItem item = player().getCurrentMediaItem();
                preparedPlaybackKey = item == null ? null : item.mediaId;
                PlaybackActivity.this.onPrepare();
                reconcileControllerReadyState();
            }
        }

        @Override
        public void onTracksChanged() {
            if (isOwner()) PlaybackActivity.this.onTracksChanged();
        }

        @Override
        public void onTitlesChanged() {
            if (isOwner()) PlaybackActivity.this.onTitlesChanged();
        }

        @Override
        public boolean onSourceHttpError(int statusCode, String msg) {
            return isOwner() && PlaybackActivity.this.onSourceHttpError(statusCode, msg);
        }

        @Override
        public void onError(String msg) {
            if (isOwner()) PlaybackActivity.this.onError(msg);
        }

        @Override
        public void onReload(String msg) {
            if (isOwner()) PlaybackActivity.this.onReload(msg);
        }

        @Override
        public void onPlayerRebuild(Player player, boolean resetVideoSurface) {
            if (isOwner()) {
                getSeekView().setProgressPlayer(player);
                if (resetVideoSurface) resetVideoSurfaceForDecoderSwitch();
                setRender();
                applyResizeMode(requestedAspectMode);
                PlaybackActivity.this.onPlayerRebuilt();
            }
        }
    };

    @Override
    protected void initView(Bundle savedInstanceState) {
        long start = System.currentTimeMillis();
        super.initView(savedInstanceState);
        if (!shouldBindPlaybackService()) return;
        ExoUtil.setPlayerView(getExoView());
        RealtimeSubtitleController.get().bind(getExoView());
        exoOutputModeManager = new ExoOutputModeManager(getWindow());
        if (deferPlaybackServiceBinding()) bindPlaybackServiceAfterFirstFrame();
        else bindPlaybackService();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "initView cost=%dms key=%s deferred=%s", System.currentTimeMillis() - start, getPlaybackKey(), deferPlaybackServiceBinding());
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        syncKeepScreenOn();
        if (!isOwner()) return;
        syncShutter();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "playing changed isPlaying=%s state=%d %s", isPlaying, mController == null ? -1 : mController.getPlaybackState(), lifecycleState());
        onPlayingChanged(isPlaying);
    }

    @Override
public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        syncKeepScreenOn();
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (isOwner()) onPlayerPositionDiscontinuity(oldPosition, newPosition, reason);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "state changed state=%d %s", state, lifecycleState());
        syncKeepScreenOn();
        if (!isOwner()) return;
        syncShutter();
        onStateChanged(state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize size) {
        if (!isOwner()) return;
        syncShutter();
        logSurfaceState("onVideoSizeChanged size=" + size.width + "x" + size.height + " ratio=" + size.pixelWidthHeightRatio);
        publishRenderTarget(getExoView().getVideoSurfaceView());
        syncVideoSurfaceSize(size);
        applyExoOutputMode();
        onSizeChanged(size);
    }

    private void applyExoOutputMode() {
        if (mService == null || exoOutputModeManager == null) return;
        publishRenderTarget(getExoView().getVideoSurfaceView());
        ExoOutputModeManager.Result result;
        if (player().isExo() && getRender() == PlayerSetting.RENDER_SURFACE) {
            Format format = player().getVideoFormat();
            result = exoOutputModeManager.apply(format);
        } else {
            result = exoOutputModeManager.observe("observation-only");
        }
        publishDisplayFacts(result);
        if (SpiderDebug.isEnabled() && result.decision() != null && result.decision().mode() != null) {
            ExoOutputModePolicy.Mode mode = result.decision().mode();
            SpiderDebug.log("playback-flow", "exo output mode reason=%s applied=%s target=%dx%d@%.3fHz", result.reason(), result.applied(), mode.width(), mode.height(), mode.refreshRateMilliHz() / 1000f);
        }
    }

    private void restoreExoOutputMode() {
        if (exoOutputModeManager == null) return;
        ExoOutputModeManager.Result result = exoOutputModeManager.restore();
        if (mService != null) publishDisplayFacts(result);
    }

    private void publishDisplayFacts(ExoOutputModeManager.Result result) {
        if (mService == null || result == null) return;
        player().publishPlaybackDisplayFacts(
                toDisplayMode(result.currentMode()),
                toDisplayMode(result.requestedMode()));
        ExoOutputModePolicy.Decision decision = result.decision();
        boolean hasTarget = decision != null && decision.mode() != null;
        PlaybackTelemetry.DecisionOutcome outcome = result.applied()
                ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                : !hasTarget ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                : decision.changeRequired() ? PlaybackTelemetry.DecisionOutcome.SELECTED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        player().publishPlaybackDecision(new PlaybackTelemetry.DecisionEvent(
                PlaybackTelemetry.DecisionDomain.DISPLAY_MODE,
                outcome,
                displayModeLabel(result.currentMode()),
                displayModeLabel(result.requestedMode()),
                result.applied() ? "window-requested" : displayModeLabel(result.currentMode()),
                result.reason(),
                result.applied() ? "none" : result.reason(),
                java.util.List.of(
                        displayModeInput("current_mode_id", result.currentMode() == null ? null : result.currentMode().id(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("current_width", result.currentMode() == null ? null : result.currentMode().width(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("current_height", result.currentMode() == null ? null : result.currentMode().height(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("current_refresh_millihz", result.currentMode() == null ? null : result.currentMode().refreshRateMilliHz(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("target_mode_id", result.requestedMode() == null ? null : result.requestedMode().id(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        displayModeInput("target_width", result.requestedMode() == null ? null : result.requestedMode().width(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        displayModeInput("target_height", result.requestedMode() == null ? null : result.requestedMode().height(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        displayModeInput("target_refresh_millihz", result.requestedMode() == null ? null : result.requestedMode().refreshRateMilliHz(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        PlaybackTelemetry.DecisionInput.bool("change_required", decision != null && decision.changeRequired(),
                                PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                        PlaybackTelemetry.DecisionInput.bool("window_request", result.applied(),
                                PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH))));
    }

    private static PlaybackTelemetry.DecisionInput displayModeInput(
            String name, Integer value, PlaybackAutoContext.ValueSource source) {
        return value == null
                ? PlaybackTelemetry.DecisionInput.unknown(name)
                : PlaybackTelemetry.DecisionInput.number(name, value,
                source, PlaybackAutoContext.Confidence.HIGH);
    }

    private static String displayModeLabel(ExoOutputModePolicy.Mode mode) {
        return mode == null ? "unknown" : "mode-" + mode.id();
    }

    private void publishRenderTarget(View surface) {
        if (mService == null) return;
        PlaybackAutoContext.RenderTarget target = surface instanceof SurfaceView
                ? PlaybackAutoContext.RenderTarget.SURFACE_VIEW
                : surface instanceof TextureView
                ? PlaybackAutoContext.RenderTarget.TEXTURE_VIEW
                : PlaybackAutoContext.RenderTarget.UNKNOWN;
        player().publishPlaybackRenderTarget(target);
    }

    private static PlaybackAutoContext.DisplayMode toDisplayMode(ExoOutputModePolicy.Mode mode) {
        return mode == null ? PlaybackAutoContext.DisplayMode.unknown()
                : new PlaybackAutoContext.DisplayMode(mode.id(), mode.width(), mode.height(), mode.refreshRateMilliHz());
    }

    @Override
    public void onRenderedFirstFrame() {
        if (isOwner()) onFirstFrameRendered();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (shouldRejectPlaybackConnection()) return;
        long start = System.currentTimeMillis();
        PlaybackService connectedService = ((PlaybackService.LocalBinder) binder).getService();
        if (shouldRejectPlaybackConnection()) return;
        mService = connectedService;
        buildControllerAsync(mService.getSessionToken());
        if (shouldRejectPlaybackConnection()) return;
        mService.replaceBinding(this::closePiP);
        mService.setSessionActivity(buildSessionIntent());
        mService.setPlaybackForeground(true);
        mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
        mService.addPlayerCallback(mPlayerCallback);
        getSeekView().setProgressPlayer(player().getPlayer());
        player().setLutAllowed(isLutAllowed());
        syncKeepScreenOn();
        player().setDanmakuForeground(true);
        publishRenderTarget(getExoView().getVideoSurfaceView());
        applyExoOutputMode();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "service connected cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service connected %s", lifecycleState());
        onServiceConnected();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service disconnected name=%s %s", name, lifecycleState());
        releaseController();
        getSeekView().setProgressPlayer(null);
        mService = null;
        preparedPlaybackKey = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mService != null) {
            mService.setPlaybackForeground(true);
            if (isOwner()) player().setDanmakuForeground(true);
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity resume %s", lifecycleState());
        playbackExiting = false;
        setRedirect(false);
        applyExoOutputMode();
        if (shouldReclaim()) {
            detachSurface();
            onReclaim();
        }
        syncKeepScreenOn();
    }

    @Override
    protected void onPause() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity pause %s", lifecycleState());
        super.onPause();
        if (isRedirect() && mController != null) mController.pause();
    }

    @Override
    protected void onStop() {
        if (mService != null) {
            mService.setPlaybackForeground(false);
            if (isOwner()) player().setDanmakuForeground(false);
        }
        restoreExoOutputMode();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity stop backgroundOff=%s %s", PlayerSetting.isBackgroundOff(), lifecycleState());
        super.onStop();
        if (isOwner() && !isAudioOnly() && shouldPauseOnBackground() && mController != null) mController.pause();
    }

    @Override
    public void onTrimMemory(int level) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity trimMemory level=%d %s", level, lifecycleState());
        super.onTrimMemory(level);
    }

    @Override
    protected void onDestroy() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity destroy beforeRelease %s", lifecycleState());
        RealtimeSubtitleController.get().unbind(getExoView());
        restoreExoOutputMode();
        super.onDestroy();
        if (isChangingConfigurations()) {
            if (mService != null) mService.removePlayerCallback(mPlayerCallback);
            detach();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity destroy configuration change preserved service key=%s", getPlaybackKey());
            return;
        }
        releasePlaybackService();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity destroy afterRelease activity=%s key=%s", getClass().getSimpleName(), getPlaybackKey());
    }
}
