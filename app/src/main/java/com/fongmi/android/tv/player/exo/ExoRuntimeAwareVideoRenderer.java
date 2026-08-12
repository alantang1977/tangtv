package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.Format;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;

import java.util.ArrayList;
import java.util.List;

/** MediaCodec renderer that removes only exact, observed runtime-blacklisted combinations. */
final class ExoRuntimeAwareVideoRenderer extends MediaCodecVideoRenderer {

    private final ExoDecoderRuntimeSession runtimeSession;
    private final ExoDecoderRuntimeSession.OutputConfig output;

    ExoRuntimeAwareVideoRenderer(
            Context context,
            MediaCodecAdapter.Factory codecAdapterFactory,
            MediaCodecSelector mediaCodecSelector,
            long allowedJoiningTimeMs,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            ExoDecoderRuntimeSession runtimeSession,
            ExoDecoderRuntimeSession.OutputConfig output,
            ExoFrameSchedulingExperimentPolicy.Decision
                    frameSchedulingDecision) {
        super(builder(
                context,
                codecAdapterFactory,
                mediaCodecSelector,
                allowedJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                frameSchedulingDecision));
        this.runtimeSession = runtimeSession;
        this.output = output;
    }

    @Override
    public String getName() {
        return "MediaCodecVideoRenderer-RuntimeProfile";
    }

    @Override
    protected List<MediaCodecInfo> getDecoderInfos(
            MediaCodecSelector selector,
            Format format,
            boolean secure) throws MediaCodecUtil.DecoderQueryException {
        return filter(super.getDecoderInfos(selector, format, secure), format, secure);
    }

    private List<MediaCodecInfo> filter(
            List<MediaCodecInfo> infos,
            Format format,
            boolean secure) {
        if (infos == null || infos.isEmpty()) return List.of();
        List<MediaCodecInfo> allowed = new ArrayList<>(infos.size());
        long nowEpochMs = System.currentTimeMillis();
        for (MediaCodecInfo info : infos) {
            if (info == null || runtimeSession.shouldExclude(
                    info.name, format, secure, output, nowEpochMs)) {
                continue;
            }
            allowed.add(info);
        }
        return allowed;
    }

    private static Builder builder(
            Context context,
            MediaCodecAdapter.Factory codecAdapterFactory,
            MediaCodecSelector mediaCodecSelector,
            long allowedJoiningTimeMs,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            ExoFrameSchedulingExperimentPolicy.Decision
                    frameSchedulingDecision) {
        Builder builder = new Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(
                        DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
        ExoFrameSchedulingRendererSettings.from(frameSchedulingDecision)
                .apply(builder);
        if (PlaybackPerformanceSetting.isLateDropInputEnabled()) {
            builder.experimentalSetLateThresholdToDropDecoderInputUs(
                    ExoUtil.ENHANCED_LATE_THRESHOLD_TO_DROP_INPUT_US);
        }
        return builder;
    }
}
