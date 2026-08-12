package com.fongmi.android.tv.player.ijk;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Process-level access to persistent IJK runtime path profiles. */
public final class IjkRuntimeProfiles
        implements IjkRuntimeProfileController.ProfileAccess {

    private static final String PREFERENCE_KEY = "perf_ijk_runtime_profile_v1";

    private final IjkRuntimeProfileStore store;
    private final IjkRuntimeProfileKey.Environment environment;

    IjkRuntimeProfiles(
            IjkRuntimeProfileStore.Backend backend,
            IjkRuntimeProfileKey.Environment environment) {
        this.store = new IjkRuntimeProfileStore(backend);
        this.environment = environment;
    }

    public static IjkRuntimeProfiles process() {
        return Holder.INSTANCE;
    }

    public IjkRuntimeProfileController newController() {
        return new IjkRuntimeProfileController(this);
    }

    @Override
    public IjkRuntimeProfileKey.Environment environment() {
        return environment;
    }

    @Override
    public boolean isExcluded(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            long nowEpochMs) {
        return store.lookup(key, path, nowEpochMs).excluded();
    }

    @Override
    public IjkRuntimeProfilePolicy.Path preferredVerifiedPath(
            IjkRuntimeProfileKey.Key key,
            Set<IjkRuntimeProfilePolicy.Path> blocked,
            boolean softEligible,
            long nowEpochMs) {
        List<IjkRuntimeProfileStore.Entry> entries = store.entriesForKey(
                key, nowEpochMs);
        return entries.stream()
                .filter(IjkRuntimeProfileStore.Entry::verified)
                .filter(entry -> blocked == null
                        || !blocked.contains(entry.path()))
                .filter(entry -> entry.path()
                        != IjkRuntimeProfilePolicy.Path.IJK_SOFT
                        || softEligible)
                .max(Comparator
                        .comparingInt(IjkRuntimeProfileStore.Entry
                                ::stableSuccessCount)
                        .thenComparingInt(entry -> -entry.failureCount())
                        .thenComparingLong(IjkRuntimeProfileStore.Entry
                                ::updatedAtEpochMs))
                .map(IjkRuntimeProfileStore.Entry::path)
                .orElse(null);
    }

    @Override
    public IjkRuntimeProfileStore.Entry recordFirstFrame(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            long nowEpochMs) {
        IjkRuntimeProfileStore.RecordResult result = store.recordFirstFrame(
                key, path, nowEpochMs);
        log("first-frame", key, path, result.entry());
        return result.entry();
    }

    @Override
    public IjkRuntimeProfileStore.Entry recordFailure(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            IjkRuntimeProfilePolicy.FailureKind failureKind,
            IjkRuntimeProfileStore.Metrics metrics,
            long nowEpochMs) {
        IjkRuntimeProfileStore.RecordResult result = store.recordFailure(
                key, path, failureKind, metrics, nowEpochMs);
        log("failure", key, path, result.entry());
        return result.entry();
    }

    @Override
    public IjkRuntimeProfileStore.Entry recordStableSuccess(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            IjkRuntimeProfileStore.Metrics metrics,
            long nowEpochMs) {
        IjkRuntimeProfileStore.RecordResult result =
                store.recordStableSuccess(
                        key, path, metrics, nowEpochMs);
        log("stable", key, path, result.entry());
        return result.entry();
    }

    @Override
    public IjkRuntimeProfileStore.Entry recordObservation(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            IjkRuntimeProfileStore.Metrics metrics,
            long nowEpochMs) {
        IjkRuntimeProfileStore.RecordResult result = store.recordObservation(
                key, path, metrics, nowEpochMs);
        if (result.recorded()) {
            log("observation", key, path, result.entry());
        }
        return result.entry();
    }

    @Override
    public IjkRuntimeProfileStore.Entry recordFallback(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            IjkRuntimeProfileStore.FallbackResult fallbackResult,
            long nowEpochMs) {
        IjkRuntimeProfileStore.RecordResult result = store.recordFallback(
                key, path, fallbackResult, nowEpochMs);
        log(fallbackResult == IjkRuntimeProfileStore.FallbackResult.SUCCESS
                        ? "fallback-success" : "fallback-failure",
                key,
                path,
                result.entry());
        return result.entry();
    }

    @Override
    public void logExclusion(
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            boolean transientOnly) {
        if (!SpiderDebug.isEnabled() || key == null || path == null) return;
        SpiderDebug.log(
                "ijk-runtime-profile",
                "action=exclude key=%s path=%s scope=%s",
                IjkRuntimeProfileKey.shortId(key),
                path.label(),
                transientOnly ? "session" : "persistent");
    }

    private static void log(
            String action,
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path,
            IjkRuntimeProfileStore.Entry entry) {
        if (!SpiderDebug.isEnabled()
                || key == null
                || path == null
                || entry == null) return;
        SpiderDebug.log(
                "ijk-runtime-profile",
                "action=%s key=%s path=%s firstFrames=%d stable=%d failures=%d consecutive=%d excluded=%s rebuffers=%d maxDropPermille=%d minRenderedPermille=%d nativeGrowth=%d pssGrowth=%d fallbackOk=%d fallbackFailed=%d kind=%s",
                action,
                IjkRuntimeProfileKey.shortId(key),
                path.label(),
                entry.firstFrameCount(),
                entry.stableSuccessCount(),
                entry.failureCount(),
                entry.consecutiveFailures(),
                entry.excluded(),
                entry.rebufferCount(),
                entry.maxDropRatePermille(),
                entry.minRenderedRatioPermille(),
                entry.maxNativeHeapGrowthBytes(),
                entry.maxPssGrowthBytes(),
                entry.fallbackSuccessCount(),
                entry.fallbackFailureCount(),
                entry.lastFailureKind().name().toLowerCase(Locale.US));
    }

    private static final class Holder {

        private static final IjkRuntimeProfiles INSTANCE =
                new IjkRuntimeProfiles(
                        new PreferencesBackend(),
                        IjkRuntimeProfileKey.currentEnvironment());
    }

    private static final class PreferencesBackend
            implements IjkRuntimeProfileStore.Backend {

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
