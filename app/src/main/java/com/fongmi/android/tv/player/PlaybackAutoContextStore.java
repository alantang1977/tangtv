package com.fongmi.android.tv.player;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public final class PlaybackAutoContextStore {

    private static final PlaybackAutoContextStore PROCESS = new PlaybackAutoContextStore();

    private final AtomicReference<PlaybackAutoContext> current = new AtomicReference<>(PlaybackAutoContext.empty());
    private final AtomicLong generations = new AtomicLong();

    public static PlaybackAutoContextStore process() {
        return PROCESS;
    }

    public PlaybackAutoContext snapshot() {
        return current.get();
    }

    public synchronized PlaybackAutoContext.SessionToken beginSession(String traceId, long startedAtElapsedMs) {
        String normalizedTraceId = PlaybackTrace.normalize(traceId);
        if (PlaybackTrace.NONE.equals(normalizedTraceId)) return PlaybackAutoContext.SessionToken.none();
        PlaybackAutoContext.SessionToken token = new PlaybackAutoContext.SessionToken(normalizedTraceId, generations.incrementAndGet());
        current.set(PlaybackAutoContext.begin(token, startedAtElapsedMs));
        return token;
    }

    public boolean publishPlaybackFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel,
            PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decodeMode,
            PlaybackAutoContext.PathFacts path,
            long publishedAtElapsedMs) {
        return update(session, publishedAtElapsedMs, context -> context.withPlaybackFacts(kernel, decodeMode, path));
    }

    public boolean publishPlaybackFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel,
            PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decodeMode,
            PlaybackAutoContext.ResourceFacts resource,
            PlaybackAutoContext.PathFacts path,
            long publishedAtElapsedMs) {
        return update(session, publishedAtElapsedMs, context -> context.withPlaybackFacts(kernel, decodeMode, resource, path));
    }

    public boolean publishDeviceFacts(PlaybackAutoContext.SessionToken session, PlaybackAutoContext.DeviceFacts device, long publishedAtElapsedMs) {
        return update(session, publishedAtElapsedMs, context -> context.withDeviceFacts(device));
    }

    public boolean publishMemoryFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshot,
            PlaybackAutoContext.Fact<Long> diagnosticPssBytes,
            long publishedAtElapsedMs) {
        if (pressure == null && snapshot == null && diagnosticPssBytes == null) return false;
        return update(session, publishedAtElapsedMs,
                context -> context.withMemoryFacts(pressure, snapshot, diagnosticPssBytes));
    }

    public boolean publishSystemConditionFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermal,
            PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> power,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> networkCost,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> networkSnapshot,
            long publishedAtElapsedMs) {
        if (thermal == null && power == null && networkCost == null && networkSnapshot == null) return false;
        return update(session, publishedAtElapsedMs,
                context -> context.withSystemConditionFacts(thermal, power, networkCost, networkSnapshot));
    }

    public boolean publishResourceFacts(PlaybackAutoContext.SessionToken session, PlaybackAutoContext.ResourceFacts resource, long publishedAtElapsedMs) {
        return update(session, publishedAtElapsedMs, context -> context.withResourceFacts(resource));
    }

    public boolean publishPathFacts(PlaybackAutoContext.SessionToken session, PlaybackAutoContext.PathFacts path, long publishedAtElapsedMs) {
        return update(session, publishedAtElapsedMs, context -> context.withPathFacts(path));
    }

    public boolean publishRuntimeFacts(PlaybackAutoContext.SessionToken session, PlaybackAutoContext.RuntimeFacts runtime, long publishedAtElapsedMs) {
        return update(session, publishedAtElapsedMs, context -> context.withRuntimeFacts(runtime));
    }

    public boolean publishMediaEngineFacts(
            PlaybackAutoContext.SessionToken session,
            long trackSequence,
            PlaybackAutoContext.TrackFacts videoTrack,
            PlaybackAutoContext.TrackFacts audioTrack,
            PlaybackAutoContext.DecoderFacts decoder,
            PlaybackAutoContext.OutputFacts output,
            long publishedAtElapsedMs) {
        if (trackSequence < 0 || videoTrack == null && audioTrack == null && decoder == null && output == null) return false;
        return update(session, publishedAtElapsedMs, context -> {
            PlaybackAutoContext.MediaFacts media = context.media().withEngineFacts(
                    trackSequence, videoTrack, audioTrack, decoder, output);
            return media == null ? null : context.withMediaFacts(media);
        });
    }

    public boolean publishRenderTargetFact(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.RenderTarget> renderTarget,
            long publishedAtElapsedMs) {
        if (renderTarget == null) return false;
        return update(session, publishedAtElapsedMs,
                context -> context.withMediaFacts(context.media().withRenderTarget(renderTarget)));
    }

    public boolean publishDisplayFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.DisplayFacts display,
            long publishedAtElapsedMs) {
        if (display == null) return false;
        return update(session, publishedAtElapsedMs,
                context -> context.withMediaFacts(context.media().withDisplayFacts(display)));
    }

    public boolean clear(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active()) return false;
        while (true) {
            PlaybackAutoContext context = current.get();
            if (!session.equals(context.session())) return false;
            if (current.compareAndSet(context, PlaybackAutoContext.empty())) return true;
        }
    }

    private boolean update(PlaybackAutoContext.SessionToken session, long publishedAtElapsedMs, UnaryOperator<PlaybackAutoContext> reducer) {
        if (session == null || !session.active() || reducer == null) return false;
        while (true) {
            PlaybackAutoContext context = current.get();
            if (!session.equals(context.session())) return false;
            PlaybackAutoContext candidate = reducer.apply(context);
            if (candidate == null || !session.equals(candidate.session())) return false;
            long nextRevision = context.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : context.revision() + 1;
            long publishedAt = Math.max(context.publishedAtElapsedMs(), publishedAtElapsedMs);
            PlaybackAutoContext next = candidate.withPublication(nextRevision, publishedAt);
            if (current.compareAndSet(context, next)) return true;
        }
    }
}
