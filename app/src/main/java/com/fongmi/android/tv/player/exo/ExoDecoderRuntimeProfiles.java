package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

/** Process-level access to persistent EXO video decoder runtime profiles. */
public final class ExoDecoderRuntimeProfiles implements ExoDecoderRuntimeSession.ProfileAccess {

    private static final String PREFERENCE_KEY = "perf_exo_decoder_runtime_v1";

    private final ExoDecoderRuntimeProfileStore store;
    private final ExoDecoderRuntimeKey.Environment environment;

    ExoDecoderRuntimeProfiles(
            ExoDecoderRuntimeProfileStore.Backend backend,
            ExoDecoderRuntimeKey.Environment environment) {
        this.store = new ExoDecoderRuntimeProfileStore(backend);
        this.environment = environment;
    }

    public static ExoDecoderRuntimeProfiles process() {
        return Holder.INSTANCE;
    }

    public ExoDecoderRuntimeSession newSession() {
        return new ExoDecoderRuntimeSession(this);
    }

    @Override
    public ExoDecoderRuntimeKey.Environment environment() {
        return environment;
    }

    @Override
    public boolean isBlacklisted(ExoDecoderRuntimeKey.Key key, long nowEpochMs) {
        return store.lookup(key, nowEpochMs).blacklisted();
    }

    @Override
    public ExoDecoderRuntimeProfileStore.Entry recordFailure(
            ExoDecoderRuntimeKey.Key key,
            ExoDecoderRuntimeProfileStore.FailureKind failureKind,
            ExoDecoderRuntimeProfileStore.Metrics metrics,
            long nowEpochMs) {
        ExoDecoderRuntimeProfileStore.RecordResult result =
                store.recordFailure(key, failureKind, metrics, nowEpochMs);
        log("failure", key, result.entry());
        return result.entry();
    }

    @Override
    public ExoDecoderRuntimeProfileStore.Entry recordFirstFrame(
            ExoDecoderRuntimeKey.Key key,
            long nowEpochMs) {
        ExoDecoderRuntimeProfileStore.RecordResult result =
                store.recordFirstFrame(key, nowEpochMs);
        log("first-frame", key, result.entry());
        return result.entry();
    }

    @Override
    public ExoDecoderRuntimeProfileStore.Entry recordStableSuccess(
            ExoDecoderRuntimeKey.Key key,
            ExoDecoderRuntimeProfileStore.Metrics metrics,
            long nowEpochMs) {
        ExoDecoderRuntimeProfileStore.RecordResult result =
                store.recordStableSuccess(key, metrics, nowEpochMs);
        log("stable", key, result.entry());
        return result.entry();
    }

    @Override
    public ExoDecoderRuntimeProfileStore.Entry recordObservation(
            ExoDecoderRuntimeKey.Key key,
            ExoDecoderRuntimeProfileStore.Metrics metrics,
            long nowEpochMs) {
        ExoDecoderRuntimeProfileStore.RecordResult result =
                store.recordObservation(key, metrics, nowEpochMs);
        if (result.recorded()) log("observation", key, result.entry());
        return result.entry();
    }

    @Override
    public ExoDecoderRuntimeProfileStore.Entry recordFallback(
            ExoDecoderRuntimeKey.Key key,
            ExoDecoderRuntimeProfileStore.FallbackResult fallbackResult,
            long nowEpochMs) {
        ExoDecoderRuntimeProfileStore.RecordResult result =
                store.recordFallback(key, fallbackResult, nowEpochMs);
        log(fallbackResult == ExoDecoderRuntimeProfileStore.FallbackResult.SUCCESS
                ? "fallback-success" : "fallback-failure", key, result.entry());
        return result.entry();
    }

    @Override
    public void logExclusion(ExoDecoderRuntimeKey.Key key, boolean transientOnly) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log(
                "exo-decoder-profile",
                "action=exclude key=%s scope=%s mime=%s size=%dx%d fpsMilli=%d secure=%s output=%s tunneling=%s",
                ExoDecoderRuntimeKey.shortId(key),
                transientOnly ? "session" : "persistent",
                key.mimeType(),
                key.width(),
                key.height(),
                key.frameRateMilli(),
                key.secure(),
                key.outputTarget().name().toLowerCase(java.util.Locale.US),
                key.tunneling());
    }

    private static void log(
            String action,
            ExoDecoderRuntimeKey.Key key,
            ExoDecoderRuntimeProfileStore.Entry entry) {
        if (!SpiderDebug.isEnabled() || key == null || entry == null) return;
        SpiderDebug.log(
                "exo-decoder-profile",
                "action=%s key=%s firstFrames=%d failures=%d consecutive=%d stable=%d blacklisted=%s drops=%d observedMs=%d recoverableErrors=%d fallbackOk=%d fallbackFailed=%d kind=%s",
                action,
                ExoDecoderRuntimeKey.shortId(key),
                entry.firstFrameCount(),
                entry.failureCount(),
                entry.consecutiveFailures(),
                entry.successCount(),
                entry.blacklisted(),
                entry.droppedFrames(),
                entry.observedDurationMs(),
                entry.recoverableCodecErrors(),
                entry.fallbackSuccessCount(),
                entry.fallbackFailureCount(),
                entry.lastFailureKind().name().toLowerCase(java.util.Locale.US));
    }

    public static ExoDecoderRuntimeSession.OutputConfig currentOutput(boolean tunneling) {
        return new ExoDecoderRuntimeSession.OutputConfig(
                PlayerSetting.getRender() == PlayerSetting.RENDER_TEXTURE
                        ? ExoDecoderRuntimeSession.OutputTarget.TEXTURE
                        : ExoDecoderRuntimeSession.OutputTarget.SURFACE,
                tunneling);
    }

    private static final class Holder {

        private static final ExoDecoderRuntimeProfiles INSTANCE =
                new ExoDecoderRuntimeProfiles(
                        new PreferencesBackend(),
                        ExoDecoderRuntimeKey.currentEnvironment());
    }

    private static final class PreferencesBackend
            implements ExoDecoderRuntimeProfileStore.Backend {

        @Override
        public String read() {
            return Prefers.getString(PREFERENCE_KEY);
        }

        @Override
        public void write(String value) {
            Prefers.put(PREFERENCE_KEY, value);
        }

        @Override
        public void clear() {
            Prefers.remove(PREFERENCE_KEY);
        }
    }
}
