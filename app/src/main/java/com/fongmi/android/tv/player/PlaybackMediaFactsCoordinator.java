package com.fongmi.android.tv.player;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fongmi.android.tv.player.engine.PlayerEngine;

/** Session- and track-sequence-safe reducer for runtime media, output and display facts. */
public final class PlaybackMediaFactsCoordinator {

    private final PlaybackAutoContextStore store;
    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private long trackSequence;
    private EngineObservationKey lastEngineObservation;
    private PlaybackAutoContext.RenderTarget lastRenderTarget;
    private DisplayObservationKey lastDisplayObservation;

    public PlaybackMediaFactsCoordinator(PlaybackAutoContextStore store) {
        this.store = store;
    }

    public synchronized boolean beginSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active() || !session.equals(store.snapshot().session())) return false;
        this.session = session;
        trackSequence = 0;
        lastEngineObservation = null;
        lastRenderTarget = null;
        lastDisplayObservation = null;
        return true;
    }

    public synchronized boolean endSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.equals(this.session)) return false;
        this.session = PlaybackAutoContext.SessionToken.none();
        trackSequence = 0;
        lastEngineObservation = null;
        lastRenderTarget = null;
        lastDisplayObservation = null;
        return true;
    }

    public synchronized boolean publishEngineFacts(
            PlaybackAutoContext.SessionToken session,
            long observedTrackSequence,
            PlayerEngine.PlaybackFactsSnapshot snapshot,
            boolean acceptDecoder,
            long sampledAtElapsedMs) {
        if (!isCurrent(session) || observedTrackSequence < trackSequence) return false;
        PlayerEngine.PlaybackFactsSnapshot observed = snapshot == null
                ? PlayerEngine.PlaybackFactsSnapshot.empty() : snapshot;
        EngineObservationKey key = EngineObservationKey.from(observedTrackSequence, observed, acceptDecoder);
        if (key.equals(lastEngineObservation)) return false;
        boolean sequenceAdvanced = observedTrackSequence > trackSequence;
        PlaybackMediaFactsMapper.MappedEngineFacts mapped = PlaybackMediaFactsMapper.map(
                observed, observedTrackSequence, acceptDecoder, sampledAtElapsedMs);
        PlaybackAutoContext.TrackFacts video = mapped.videoTrack().hasEvidence()
                ? mapped.videoTrack() : sequenceAdvanced ? PlaybackAutoContext.TrackFacts.unknown() : null;
        PlaybackAutoContext.TrackFacts audio = mapped.audioTrack().hasEvidence()
                ? mapped.audioTrack() : sequenceAdvanced ? PlaybackAutoContext.TrackFacts.unknown() : null;
        PlaybackAutoContext.DecoderFacts decoder = mapped.decoder();
        if (sequenceAdvanced && decoder == null) decoder = PlaybackAutoContext.DecoderFacts.unknown(observedTrackSequence);
        boolean published = store.publishMediaEngineFacts(session, observedTrackSequence,
                video, audio, decoder, mapped.output(), sampledAtElapsedMs);
        if (published) {
            trackSequence = observedTrackSequence;
            lastEngineObservation = key;
        }
        return published;
    }

    public synchronized boolean publishRenderTarget(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.RenderTarget renderTarget,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        PlaybackAutoContext.RenderTarget observed = renderTarget == null
                ? PlaybackAutoContext.RenderTarget.UNKNOWN : renderTarget;
        if (observed == lastRenderTarget) return false;
        PlaybackAutoContext.Fact<PlaybackAutoContext.RenderTarget> fact = observed == PlaybackAutoContext.RenderTarget.UNKNOWN
                ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RenderTarget.UNKNOWN)
                : PlaybackAutoContext.Fact.untilReplaced(observed, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs);
        boolean published = store.publishRenderTargetFact(session, fact, sampledAtElapsedMs);
        if (published) lastRenderTarget = observed;
        return published;
    }

    public synchronized boolean publishDisplayFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        PlaybackAutoContext.DisplayMode current = currentMode == null
                ? PlaybackAutoContext.DisplayMode.unknown() : currentMode;
        PlaybackAutoContext.DisplayMode requested = requestedMode == null
                ? PlaybackAutoContext.DisplayMode.unknown() : requestedMode;
        DisplayObservationKey key = new DisplayObservationKey(current, requested);
        if (key.equals(lastDisplayObservation)) return false;
        PlaybackAutoContext.DisplayFacts display = new PlaybackAutoContext.DisplayFacts(
                current.hasEvidence()
                        ? PlaybackAutoContext.Fact.untilReplaced(current, PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                        : PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DisplayMode.unknown()),
                requested.hasEvidence()
                        ? PlaybackAutoContext.Fact.untilReplaced(requested, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                        : PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DisplayMode.unknown()));
        boolean published = store.publishDisplayFacts(session, display, sampledAtElapsedMs);
        if (published) lastDisplayObservation = key;
        return published;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken session) {
        return session != null && session.active() && session.equals(this.session)
                && session.equals(store.snapshot().session());
    }

    private record DisplayObservationKey(
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode) {
    }

    private record EngineObservationKey(
            long trackSequence,
            boolean acceptDecoder,
            FormatKey selectedVideo,
            FormatKey selectedAudio,
            FormatKey decoderVideo,
            FormatKey decoderAudio,
            String videoDecoderName,
            String audioDecoderName,
            PlayerEngine.DecoderKind decoderKind,
            Boolean secure,
            String hwdec,
            String videoOutput,
            Boolean tunneling) {

        private static EngineObservationKey from(
                long trackSequence,
                PlayerEngine.PlaybackFactsSnapshot snapshot,
                boolean acceptDecoder) {
            return new EngineObservationKey(
                    trackSequence,
                    acceptDecoder,
                    FormatKey.from(snapshot.selectedVideoFormat()),
                    FormatKey.from(snapshot.selectedAudioFormat()),
                    FormatKey.from(snapshot.videoDecoderFormat()),
                    FormatKey.from(snapshot.audioDecoderFormat()),
                    PlaybackMediaFactsMapper.safeLabel(snapshot.videoDecoderName()),
                    PlaybackMediaFactsMapper.safeLabel(snapshot.audioDecoderName()),
                    snapshot.videoDecoderKind(),
                    snapshot.secureVideoDecoder(),
                    PlaybackMediaFactsMapper.safeLabel(snapshot.hwdecCurrent()),
                    PlaybackMediaFactsMapper.safeLabel(snapshot.currentVideoOutput()),
                    snapshot.tunneling());
        }
    }

    private record FormatKey(
            String mimeType,
            String codecs,
            int width,
            int height,
            int frameRateMilli,
            int averageBitrate,
            int peakBitrate,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            boolean hdrStaticMetadata) {

        private static FormatKey from(Format format) {
            if (format == null) return new FormatKey("", "", -1, -1, -1,
                    -1, -1, -1, -1, -1, false);
            ColorInfo color = format.colorInfo;
            return new FormatKey(
                    PlaybackMediaFactsMapper.safeLabel(format.sampleMimeType),
                    PlaybackMediaFactsMapper.safeLabel(format.codecs),
                    format.width > 0 ? format.width : -1,
                    format.height > 0 ? format.height : -1,
                    format.frameRate > 0 && Float.isFinite(format.frameRate) ? Math.round(format.frameRate * 1_000f) : -1,
                    format.averageBitrate > 0 ? format.averageBitrate : -1,
                    format.peakBitrate > 0 ? format.peakBitrate : -1,
                    color == null ? -1 : color.colorSpace,
                    color == null ? -1 : color.colorRange,
                    color == null ? -1 : color.colorTransfer,
                    color != null && color.hdrStaticInfo != null && color.hdrStaticInfo.length > 0);
        }
    }
}
