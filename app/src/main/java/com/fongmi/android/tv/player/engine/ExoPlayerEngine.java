package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.exo.ErrorMsgProvider;
import com.fongmi.android.tv.player.exo.ExoDecoderRuntimeProfiles;
import com.fongmi.android.tv.player.exo.ExoDecoderRuntimeSession;
import com.fongmi.android.tv.player.exo.ExoFrameSchedulingPlayerSettings;
import com.fongmi.android.tv.player.exo.ExoFrameSchedulingSessionLock;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.ExoTunnelingProgressWatchdog;
import com.fongmi.android.tv.player.exo.ExoTunnelingRuntimeState;
import com.fongmi.android.tv.player.exo.ExoTunnelingWatchdog;
import com.fongmi.android.tv.player.exo.PlaybackBytePositionDataSource;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.player.exo.PlaybackAnalyticsListener;
import com.fongmi.android.tv.player.exo.PreCache;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ExoPlayerEngine implements PlayerEngine {

    private static final AtomicInteger PREPARE_GENERATION = new AtomicInteger();

    public interface PrepareListener {

        PrepareListener NONE = new PrepareListener() {
        };

        default void onPrepareStarted(int generation) {
        }

        default void onPrepareReady(int generation) {
        }

        default void onPrepareCanceled(int generation) {
        }
    }

    private final ErrorMsgProvider provider;
    private final PreCache preCache;
    private final Set<String> attemptedFormats;
    private final PrepareListener prepareListener;
    private final ExoDecoderRuntimeSession decoderRuntimeSession;
    private final ExoFrameSchedulingSessionLock frameSchedulingSessionLock;
    private PlaySpec spec;
    private String activeFormat;
    private ExoPlayer player;
    private Player.Listener prepareReadyListener;
    private int decode;
    private int pendingPrepareGeneration = -1;
    private boolean playWhenReady;
    private boolean cacheSessionActive;
    private boolean tunnelingFallbackAttempted;
    private boolean tunnelingEnabledForSession;
    private boolean decoderRuntimeEnabledForPlayer;
    private ExoFrameSchedulingPlayerSettings frameSchedulingSettings;
    private ExoFrameSchedulingPlayerSettings pendingFrameSchedulingSettings;
    private ExoDecoderRuntimeSession.OutputConfig frameSchedulingOutput;
    private PlaybackResourceClassifier.Classification resourceClassification;
    private long byteSessionSequence = -1;
    private final ExoTunnelingWatchdog tunnelingWatchdog = new ExoTunnelingWatchdog();
    private final ExoTunnelingProgressWatchdog tunnelingProgressWatchdog = new ExoTunnelingProgressWatchdog();
    private final Runnable tunnelingWatchdogRunnable = this::onTunnelingWatchdogTimeout;
    private final Runnable tunnelingProgressWatchdogRunnable = this::checkTunnelingProgress;
    private final Runnable decoderRuntimeStableRunnable = this::onDecoderRuntimeStable;
    private boolean firstFrameRendered;
    private boolean decoderRuntimeStableScheduled;
    private final Player.Listener tunnelingWatchdogListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            firstFrameRendered = true;
            recordDecoderRuntimeFirstFrame();
            tunnelingWatchdog.onFirstFrame();
            App.removeCallbacks(tunnelingWatchdogRunnable);
            if (player.isPlaying()) armTunnelingProgressWatchdog();
            armDecoderRuntimeStableWindow();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying && firstFrameRendered) {
                armTunnelingProgressWatchdog();
                armDecoderRuntimeStableWindow();
            } else if (!isPlaying) {
                cancelTunnelingProgressWatchdog();
                cancelDecoderRuntimeStableWindow();
            }
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY && player.isPlaying() && firstFrameRendered) {
                armTunnelingProgressWatchdog();
                armDecoderRuntimeStableWindow();
            } else if (state != Player.STATE_READY) {
                cancelTunnelingProgressWatchdog();
                cancelDecoderRuntimeStableWindow();
            }
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            if (player.isPlaying() && firstFrameRendered) armTunnelingProgressWatchdog();
        }

        @Override
        public void onPlayerError(@androidx.annotation.NonNull PlaybackException error) {
            tunnelingWatchdog.onError();
            App.removeCallbacks(tunnelingWatchdogRunnable);
            cancelTunnelingProgressWatchdog();
            cancelDecoderRuntimeStableWindow();
        }
    };

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this(decode, listener, PrepareListener.NONE);
    }

    public ExoPlayerEngine(int decode, Player.Listener listener, PrepareListener prepareListener) {
        this.decoderRuntimeSession = ExoDecoderRuntimeProfiles.process().newSession();
        this.decoderRuntimeEnabledForPlayer =
                PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO);
        this.frameSchedulingSettings =
                ExoFrameSchedulingPlayerSettings.capture(decode);
        this.frameSchedulingSessionLock =
                new ExoFrameSchedulingSessionLock(
                        frameSchedulingSettings.decision());
        this.frameSchedulingOutput = ExoDecoderRuntimeProfiles.currentOutput(
                ExoUtil.isTunnelingEnabled(decode, false));
        MediaSourceFactory.acquireCacheSession();
        try {
            this.player = ExoUtil.buildPlayer(
                    decode,
                    listener,
                    false,
                    decoderRuntimeSession,
                    frameSchedulingSettings);
        } catch (RuntimeException | Error e) {
            MediaSourceFactory.releaseCacheSession();
            throw e;
        }
        this.cacheSessionActive = true;
        this.provider = new ErrorMsgProvider();
        this.preCache = new PreCache();
        this.attemptedFormats = new HashSet<>();
        this.prepareListener = prepareListener == null ? PrepareListener.NONE : prepareListener;
        this.decode = decode;
        this.tunnelingEnabledForSession = ExoUtil.isTunnelingEnabled(decode, false);
        this.firstFrameRendered = false;
        this.player.addListener(tunnelingWatchdogListener);
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        cancelPendingPrepare();
        Runnable cacheRelease = null;
        if (cacheSessionActive) {
            cacheSessionActive = false;
            cacheRelease = MediaSourceFactory::releaseCacheSession;
        }
        preCache.release(cacheRelease);
        cancelTunnelingWatchdog();
        cancelTunnelingProgressWatchdog();
        cancelDecoderRuntimeStableWindow();
        finishDecoderRuntimeAttempt();
        PlaybackAnalyticsListener.finishSession(player.getCurrentPosition());
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        cancelPendingPrepare();
        ExoFrameSchedulingPlayerSettings schedulingSettings =
                settingsForRebuild();
        preCache.stop("engine-rebuild");
        cancelTunnelingWatchdog();
        cancelTunnelingProgressWatchdog();
        cancelDecoderRuntimeStableWindow();
        finishDecoderRuntimeAttempt();
        PlaybackAnalyticsListener.finishSession(player.getCurrentPosition());
        player.release();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "rebuild decode=%d", decode);
        tunnelingEnabledForSession = ExoUtil.isTunnelingEnabled(decode, tunnelingFallbackAttempted);
        decoderRuntimeEnabledForPlayer =
                PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO);
        frameSchedulingOutput = ExoDecoderRuntimeProfiles.currentOutput(
                tunnelingEnabledForSession);
        player = ExoUtil.buildPlayer(
                decode,
                listener,
                tunnelingFallbackAttempted,
                decoderRuntimeSession,
                schedulingSettings);
        frameSchedulingSettings = schedulingSettings;
        frameSchedulingSessionLock.onRendererRebuilt(
                schedulingSettings.decision());
        player.addListener(tunnelingWatchdogListener);
        return player;
    }

    public boolean prepareFrameSchedulingForNextPlayback() {
        ExoFrameSchedulingPlayerSettings desired =
                ExoFrameSchedulingPlayerSettings.capture(decode);
        if (!frameSchedulingSettings.samePlayerConfiguration(desired)) {
            pendingFrameSchedulingSettings = desired;
            return true;
        }
        pendingFrameSchedulingSettings = null;
        frameSchedulingSessionLock.lockForNextPlayback(desired.decision());
        return false;
    }

    private ExoFrameSchedulingPlayerSettings settingsForRebuild() {
        ExoFrameSchedulingPlayerSettings pending =
                pendingFrameSchedulingSettings;
        pendingFrameSchedulingSettings = null;
        if (pending != null) return pending;
        String specTraceId = spec == null
                ? PlaybackTrace.NONE
                : PlaybackTrace.normalize(spec.getPlaybackTraceId());
        if (spec != null
                && !PlaybackTrace.NONE.equals(specTraceId)
                && specTraceId.equals(
                PlaybackAnalyticsListener.getPlaybackTraceId())) {
            return frameSchedulingSettings.withDecision(
                    frameSchedulingSessionLock.sessionDecision());
        }
        return ExoFrameSchedulingPlayerSettings.capture(decode);
    }

    public boolean disableTunnelingForSession() {
        if (!tunnelingEnabledForSession || tunnelingFallbackAttempted) return false;
        tunnelingFallbackAttempted = true;
        tunnelingEnabledForSession = false;
        frameSchedulingOutput = ExoDecoderRuntimeProfiles.currentOutput(false);
        cancelTunnelingProgressWatchdog();
        cancelTunnelingWatchdog();
        int failures = ExoTunnelingRuntimeState.recordFailure(ExoUtil.getTunnelingRuntimeKey(decode));
        PlaybackTrace.log("exo-tunnel", getPlaybackTraceId(), "disable tunneling for current session");
        PlaybackTrace.log("exo-tunnel", getPlaybackTraceId(), "runtime failure count=%d blacklisted=%s", failures, failures >= ExoTunnelingRuntimeState.BLACKLIST_THRESHOLD);
        return true;
    }

    private void armTunnelingWatchdog() {
        if (!tunnelingEnabledForSession) return;
        tunnelingWatchdog.arm(android.os.SystemClock.elapsedRealtime());
        App.post(tunnelingWatchdogRunnable, ExoTunnelingWatchdog.FIRST_FRAME_TIMEOUT_MS);
    }

    private void cancelTunnelingWatchdog() {
        tunnelingWatchdog.reset();
        App.removeCallbacks(tunnelingWatchdogRunnable);
    }

    private void armTunnelingProgressWatchdog() {
        if (!tunnelingEnabledForSession || !firstFrameRendered || !player.isPlaying() || player.getPlaybackState() != Player.STATE_READY) return;
        tunnelingProgressWatchdog.arm(android.os.SystemClock.elapsedRealtime(), player.getCurrentPosition());
        App.post(tunnelingProgressWatchdogRunnable, 1_000L);
    }

    private void cancelTunnelingProgressWatchdog() {
        tunnelingProgressWatchdog.reset();
        App.removeCallbacks(tunnelingProgressWatchdogRunnable);
    }

    private void armDecoderRuntimeStableWindow() {
        if (!decoderRuntimeEnabledForPlayer
                || !isHard()
                || decoderRuntimeStableScheduled
                || !firstFrameRendered
                || !player.isPlaying()
                || player.getPlaybackState() != Player.STATE_READY) {
            return;
        }
        decoderRuntimeStableScheduled = true;
        App.post(
                decoderRuntimeStableRunnable,
                ExoDecoderRuntimeSession.STABLE_PLAYBACK_WINDOW_MS);
    }

    private void cancelDecoderRuntimeStableWindow() {
        decoderRuntimeStableScheduled = false;
        App.removeCallbacks(decoderRuntimeStableRunnable);
    }

    private void onDecoderRuntimeStable() {
        decoderRuntimeStableScheduled = false;
        if (!decoderRuntimeEnabledForPlayer
                || !isHard()
                || !firstFrameRendered
                || !player.isPlaying()
                || player.getPlaybackState() != Player.STATE_READY) {
            return;
        }
        decoderRuntimeSession.recordStable(
                currentDecoderRuntimeEvidence(),
                android.os.SystemClock.elapsedRealtime(),
                System.currentTimeMillis());
    }

    private void recordDecoderRuntimeFirstFrame() {
        if (!decoderRuntimeEnabledForPlayer || !isHard()) return;
        decoderRuntimeSession.recordFirstFrame(
                currentDecoderRuntimeEvidence(),
                System.currentTimeMillis());
    }

    private void checkTunnelingProgress() {
        if (!tunnelingEnabledForSession || !firstFrameRendered || !player.isPlaying() || player.getPlaybackState() != Player.STATE_READY) return;
        long nowMs = android.os.SystemClock.elapsedRealtime();
        long positionMs = player.getCurrentPosition();
        if (tunnelingProgressWatchdog.shouldTimeout(nowMs, positionMs)) {
            long position = Math.max(0, positionMs);
            boolean wasPlayWhenReady = player.getPlayWhenReady();
            if (!disableTunnelingForSession()) return;
            PlaybackTrace.log("exo-tunnel", getPlaybackTraceId(), "progress watchdog fallback position=%d", position);
            player.stop();
            startInternal(position, wasPlayWhenReady);
            return;
        }
        tunnelingProgressWatchdog.observe(nowMs, positionMs);
        App.post(tunnelingProgressWatchdogRunnable, 1_000L);
    }

    private void onTunnelingWatchdogTimeout() {
        if (!tunnelingWatchdog.shouldTimeout(android.os.SystemClock.elapsedRealtime())) return;
        long position = Math.max(0, player.getCurrentPosition());
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        if (!disableTunnelingForSession()) return;
        PlaybackTrace.log("exo-tunnel", getPlaybackTraceId(), "first-frame watchdog fallback position=%d", position);
        player.stop();
        startInternal(position, wasPlayWhenReady);
    }

    @Override
    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    @Override
    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public String getRenderDiagnostics() {
        String key = ExoUtil.getTunnelingRuntimeKey(decode);
        int failures = ExoTunnelingRuntimeState.failureCount(key);
        return String.format(Locale.US, "tunnel requested %s / fallback %s / failures %d / blacklisted %s",
                tunnelingEnabledForSession ? "yes" : "no",
                tunnelingFallbackAttempted ? "yes" : "no",
                failures,
                ExoTunnelingRuntimeState.isBlacklisted(key) ? "yes" : "no");
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, true);
    }

    @Override
    public void start(PlaySpec spec, boolean playWhenReady) {
        finishDecoderRuntimeAttempt();
        lockCompatibleFrameSchedulingDecision();
        this.spec = spec;
        this.activeFormat = spec.getFormat();
        this.resourceClassification = PlaybackResourceClassifier.classifyRequest(spec.getUrl(), spec.getFormat(), spec.getFormat());
        this.playWhenReady = playWhenReady;
        if (decoderRuntimeEnabledForPlayer) {
            decoderRuntimeSession.beginPlayback(spec.getPlaybackTraceId());
        }
        resetAttemptedFormats();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "start decode=%d format=%s play=%s headers=%s urlLen=%d", decode, spec.getFormat(), playWhenReady, spec.getHeaders() == null ? 0 : spec.getHeaders().size(), spec.getUrl() == null ? 0 : spec.getUrl().length());
        startInternal(C.TIME_UNSET, playWhenReady);
    }

    @Override
    public void start(PlaySpec spec, long position, boolean playWhenReady) {
        finishDecoderRuntimeAttempt();
        lockCompatibleFrameSchedulingDecision();
        this.spec = spec;
        this.activeFormat = spec.getFormat();
        this.resourceClassification = PlaybackResourceClassifier.classifyRequest(spec.getUrl(), spec.getFormat(), spec.getFormat());
        this.playWhenReady = playWhenReady;
        if (decoderRuntimeEnabledForPlayer) {
            decoderRuntimeSession.beginPlayback(spec.getPlaybackTraceId());
        }
        resetAttemptedFormats();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "start decode=%d format=%s position=%d play=%s headers=%s urlLen=%d", decode, spec.getFormat(), position, playWhenReady, spec.getHeaders() == null ? 0 : spec.getHeaders().size(), spec.getUrl() == null ? 0 : spec.getUrl().length());
        startInternal(position, playWhenReady);
    }

    @Override
    public void restart(PlaySpec spec, long position, boolean playWhenReady) {
        finishDecoderRuntimeAttempt();
        this.spec = spec;
        this.activeFormat = spec.getFormat();
        this.resourceClassification = PlaybackResourceClassifier.classifyRequest(spec.getUrl(), spec.getFormat(), spec.getFormat());
        this.playWhenReady = playWhenReady;
        if (decoderRuntimeEnabledForPlayer) {
            decoderRuntimeSession.beginPlayback(spec.getPlaybackTraceId());
        }
        resetAttemptedFormats();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "restart decode=%d format=%s position=%d play=%s headers=%s urlLen=%d", decode, spec.getFormat(), position, playWhenReady, spec.getHeaders() == null ? 0 : spec.getHeaders().size(), spec.getUrl() == null ? 0 : spec.getUrl().length());
        cancelPendingPrepare();
        preCache.stop("engine-restart");
        player.stop();
        startInternal(position, playWhenReady);
    }

    private void lockCompatibleFrameSchedulingDecision() {
        ExoFrameSchedulingPlayerSettings desired =
                ExoFrameSchedulingPlayerSettings.capture(decode);
        if (frameSchedulingSettings.samePlayerConfiguration(desired)) {
            frameSchedulingSessionLock.lockForNextPlayback(
                    desired.decision());
        }
    }

    @Override
    public void stop() {
        cancelPendingPrepare();
        preCache.stop("player-stop");
        cancelDecoderRuntimeStableWindow();
        finishDecoderRuntimeAttempt();
        PlaybackAnalyticsListener.finishSession(player.getCurrentPosition());
        player.stop();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    @Override
    public PlaybackResourceClassifier.Classification getResourceClassification() {
        PlaybackResourceClassifier.Classification current = resourceClassification;
        if (current == null) {
            current = PlaybackResourceClassifier.classifyRequest(spec == null ? null : spec.getUrl(), spec == null ? null : spec.getFormat(), spec == null ? null : spec.getFormat());
        }
        if (byteSessionSequence >= 0 && PlaybackBytePositionDataSource.resourceSessionSequence() == byteSessionSequence) {
            PlaybackResourceClassifier.Classification observed = PlaybackBytePositionDataSource.latestResourceClassification();
            current = PlaybackResourceClassifier.merge(current, observed);
        }
        if (player == null) return current;
        try {
            return PlaybackResourceClassifier.observePlayer(current, player.isCurrentMediaItemLive(), player.getDuration());
        } catch (Throwable ignored) {
            return current;
        }
    }

    @Override
    public void setTrack(List<Track> tracks) {
        TrackUtil.setTrackSelection(player, tracks);
    }

    @Override
    public void resetTrack() {
        TrackUtil.reset(player);
    }

    @Override
public void resetTrack(int type) {
        TrackUtil.reset(player, type);
    }

    @Override
    public void restoreVideoTrack() {
        TrackUtil.enable(player, C.TRACK_TYPE_VIDEO);
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    @Override
    public boolean supportsVideoEffects() {
        return true;
    }

    @Override
    public void setVideoEffects(List<Effect> effects) {
        player.setVideoEffects(effects);
    }

    @Override
    public Format getVideoFormat() {
        return player.getVideoFormat();
    }

    @Override
    public PlaybackFactsSnapshot getPlaybackFactsSnapshot() {
        PlaybackAnalyticsListener.Snapshot analytics = PlaybackAnalyticsListener.getSnapshot();
        boolean currentAnalyticsSession = !PlaybackTrace.NONE.equals(getPlaybackTraceId())
                && getPlaybackTraceId().equals(PlaybackAnalyticsListener.getPlaybackTraceId());
        Format analyticsVideo = currentAnalyticsSession ? analytics.videoFormat() : null;
        Format analyticsAudio = currentAnalyticsSession ? analytics.audioFormat() : null;
        Format selectedVideo = analyticsVideo != null
                ? analyticsVideo : TrackUtil.explicitlySelectedFormat(getCurrentTracks(), C.TRACK_TYPE_VIDEO);
        Format selectedAudio = analyticsAudio != null
                ? analyticsAudio : TrackUtil.explicitlySelectedFormat(getCurrentTracks(), C.TRACK_TYPE_AUDIO);
        return new PlaybackFactsSnapshot(
                selectedVideo,
                selectedAudio,
                analyticsVideo,
                analyticsAudio,
                currentAnalyticsSession ? analytics.videoDecoderName() : "",
                currentAnalyticsSession ? analytics.audioDecoderName() : "",
                DecoderKind.UNKNOWN,
                null,
                "",
                "",
                currentAnalyticsSession ? tunnelingEnabledForSession : null);
    }

    @Override
    public long getDroppedFrames() {
        return PlaybackAnalyticsListener.getSnapshot().droppedFrames();
    }

    @Override
    public String getPlaybackTraceId() {
        return spec == null ? PlaybackTrace.NONE : spec.getPlaybackTraceId();
    }

    @Override
    public boolean haveTitle() {
        return !player.getCurrentMediaEditions().isEmpty();
    }

    @Override
    public List<MediaEdition> getCurrentMediaEditions() {
        return player.getCurrentMediaEditions();
    }

    @Override
    public boolean selectEdition(MediaEdition edition) {
        return player.selectEdition(edition);
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        ErrorAction action = switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "handleError code=%d action=%s decode=%d format=%s originalFormat=%s", e.errorCode, action, decode, activeFormat, spec == null ? null : spec.getFormat());
        return action;
    }

    public boolean observeDecoderRuntimeFailure(PlaybackException error) {
        if (!decoderRuntimeEnabledForPlayer || !isHard() || error == null) return false;
        cancelDecoderRuntimeStableWindow();
        return decoderRuntimeSession.recordFatalFailure(
                decoderRuntimeEvidence(error),
                error.errorCode,
                android.os.SystemClock.elapsedRealtime(),
                System.currentTimeMillis());
    }

    public boolean prepareDecoderRuntimeFallback() {
        return decoderRuntimeEnabledForPlayer
                && isHard()
                && decoderRuntimeSession.prepareRuntimeFallback();
    }

    public void stopAutomaticPreload(String reason) {
        preCache.stopAutomatic(reason);
    }

    /** Discards the stale RTSP queue and seeks only when Media3 exposes a live default edge. */
    public boolean recoverRtspLiveEdge() {
        if (player == null
                || !player.isCurrentMediaItemLive()
                || !player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
            return false;
        }
        Timeline timeline = player.getCurrentTimeline();
        int index = player.getCurrentMediaItemIndex();
        if (timeline.isEmpty() || index < 0 || index >= timeline.getWindowCount()) return false;
        Timeline.Window window = timeline.getWindow(index, new Timeline.Window());
        if (!window.isLive() || !window.isDynamic) return false;
        preCache.stop("rtsp-live-edge-recovery");
        PlaybackTrace.log("exo-rtsp-live", getPlaybackTraceId(),
                "action=seek-live-edge");
        player.seekToDefaultPosition();
        preparePlayer();
        return true;
    }

    private void startInternal() {
        startInternal(C.TIME_UNSET, true);
    }

    private void startInternal(long position) {
        startInternal(position, true);
    }

    private void startInternal(long position, boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        firstFrameRendered = false;
        cancelTunnelingProgressWatchdog();
        cancelDecoderRuntimeStableWindow();
        armTunnelingWatchdog();
        finishDecoderRuntimeAttempt();
        PlaybackAnalyticsListener.finishSession(player.getCurrentPosition());
        PlaybackAnalyticsListener.beginSession(
                spec.getPlaybackTraceId(),
                frameSchedulingSessionLock.sessionDecision(),
                frameSchedulingOutput,
                frameSchedulingSettings.codecQueueModeLabel());
        if (decoderRuntimeEnabledForPlayer) {
            decoderRuntimeSession.beginAttempt(
                    ExoDecoderRuntimeProfiles.currentOutput(tunnelingEnabledForSession),
                    android.os.SystemClock.elapsedRealtime());
        }
        byteSessionSequence = PlaybackBytePositionDataSource.resourceSessionSequence();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "prepare position=%d decode=%d format=%s originalFormat=%s play=%s", position, decode, activeFormat, spec.getFormat(), playWhenReady);
        ExoPerformanceSetting.beginAutoSession();
        if (!playWhenReady) player.pause();
        MediaItem item = ExoUtil.getMediaItem(spec.copyWithFormat(activeFormat), decode);
        player.setMediaItem(item, position);
        preCache.start(player, item, spec.getPlaybackTraceId(), spec.getPlaybackRoute());
        preparePlayer();
        if (playWhenReady) player.play();
    }

    private void preparePlayer() {
        int generation = beginPrepare();
        prepareListener.onPrepareStarted(generation);
        player.prepare();
    }

    private int beginPrepare() {
        cancelPendingPrepare();
        int generation = PREPARE_GENERATION.incrementAndGet();
        pendingPrepareGeneration = generation;
        Player.Listener readyListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state != Player.STATE_READY || generation != pendingPrepareGeneration || prepareReadyListener != this) return;
                player.removeListener(this);
                prepareReadyListener = null;
                pendingPrepareGeneration = -1;
                prepareListener.onPrepareReady(generation);
            }
        };
        prepareReadyListener = readyListener;
        player.addListener(readyListener);
        return generation;
    }

    @Override
    public void cancelPendingPrepare() {
        int generation = pendingPrepareGeneration;
        if (generation < 0) return;
        pendingPrepareGeneration = -1;
        Player.Listener readyListener = prepareReadyListener;
        prepareReadyListener = null;
        if (readyListener != null) player.removeListener(readyListener);
        prepareListener.onPrepareCanceled(generation);
    }

    private void finishDecoderRuntimeAttempt() {
        if (!decoderRuntimeEnabledForPlayer) return;
        decoderRuntimeSession.finishAttempt(
                currentDecoderRuntimeEvidence(),
                android.os.SystemClock.elapsedRealtime(),
                System.currentTimeMillis());
    }

    private ExoDecoderRuntimeSession.Evidence currentDecoderRuntimeEvidence() {
        PlaybackAnalyticsListener.Snapshot analytics =
                PlaybackAnalyticsListener.getSnapshot();
        Format format = analytics.videoFormat() == null
                ? player.getVideoFormat() : analytics.videoFormat();
        String decoderName = analytics.videoDecoderName();
        boolean secure = isSecureDecoderName(decoderName);
        return new ExoDecoderRuntimeSession.Evidence(
                decoderName,
                format,
                secure,
                analytics.droppedFrames(),
                PlaybackAnalyticsListener.getFrameTimingSnapshot().codecErrorCount());
    }

    private ExoDecoderRuntimeSession.Evidence decoderRuntimeEvidence(
            PlaybackException error) {
        PlaybackAnalyticsListener.Snapshot analytics =
                PlaybackAnalyticsListener.getSnapshot();
        PlaybackAnalyticsListener.DecoderFailureEvidence failure =
                PlaybackAnalyticsListener.getDecoderFailureEvidence(error);
        String decoderName = failure.decoderName();
        boolean secure = failure.secureDecoderRequired()
                || isSecureDecoderName(decoderName);
        return new ExoDecoderRuntimeSession.Evidence(
                decoderName,
                failure.format(),
                secure,
                analytics.droppedFrames(),
                PlaybackAnalyticsListener.getFrameTimingSnapshot().codecErrorCount());
    }

    private static boolean isSecureDecoderName(String decoderName) {
        if (decoderName == null || decoderName.isBlank()) return false;
        String lower = decoderName.toLowerCase(Locale.US);
        return lower.contains(".secure")
                || lower.contains("secure.decoder")
                || lower.endsWith("-secure");
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        preparePlayer();
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryFormat(int errorCode) {
        String format = ExoUtil.getMimeType(errorCode);
        String key = formatKey(format);
        if (format == null || attemptedFormats.contains(key)) {
            PlaybackTrace.log("player-engine", getPlaybackTraceId(), "retryFormat stopped errorCode=%d attempted=%s", errorCode, attemptedFormats);
            return ErrorAction.FATAL;
        }
        attemptedFormats.add(key);
        activeFormat = format;
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "retryFormat errorCode=%d newFormat=%s position=%d", errorCode, format, player.getCurrentPosition());
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }

    private void resetAttemptedFormats() {
        attemptedFormats.clear();
        attemptedFormats.add(formatKey(activeFormat));
    }

    private String formatKey(String format) {
        return format == null || format.isBlank() ? "<auto>" : format.toLowerCase(Locale.ROOT);
    }
}
