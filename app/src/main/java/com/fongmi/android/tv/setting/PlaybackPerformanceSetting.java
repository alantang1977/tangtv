package com.fongmi.android.tv.setting;

import android.content.SharedPreferences;

import com.github.catvod.utils.Prefers;

import java.util.Map;

public class PlaybackPerformanceSetting {

    public static final int PROFILE_RECOMMENDED = 0;
    public static final int PROFILE_COMPATIBLE = 1;
    public static final int PROFILE_CUSTOM = 2;
    public static final int PROFILE_LIGHTWEIGHT = 3;
    public static final int PROFILE_ORIGINAL = 4;
    public static final int PROFILE_AUTO = 5;

    public static final String KEY_PROFILE = "playback_performance_profile";
    private static final String KEY_PROFILE_MIGRATED = "playback_performance_profile_per_kernel";
    private static final String KEY_PROFILE_EXO = "perf_exo_profile";
    private static final String KEY_PROFILE_MPV = "perf_mpv_profile";
    private static final String KEY_PROFILE_IJK = "perf_ijk_profile";
    private static final String KEY_INITIALIZED = "playback_performance_initialized";
    private static final String KEY_BUFFER_WATERMARKS_MIGRATED = "playback_performance_buffer_watermarks_v2";
    private static final String KEY_EXO_SIZE_PRIORITY_MIGRATED = "playback_performance_exo_size_priority_v1";
    private static final String KEY_PRELOAD_DEFAULTS_MIGRATED = "playback_performance_preload_defaults_v1";
    private static final String KEY_EXO_LOAD_CONTROL_MIGRATED = "playback_performance_exo_load_control_v2";
    private static final String KEY_EXO_BACK_BUFFER_MIGRATED = "playback_performance_exo_back_buffer_v1";
    private static final String KEY_EXO_REBUFFER_MIGRATED = "playback_performance_exo_rebuffer_v3";
    private static final String KEY_MPV_REBUFFER_MIGRATED = "playback_performance_mpv_rebuffer_v1";
    private static final String KEY_MPV_AUTO_BASELINE_MIGRATED = "playback_performance_mpv_auto_baseline_v1";
    private static final String KEY_PROFILE_MERGE_SCHEMA =
            "playback_performance_profile_merge_schema";
    private static final String KEY_PROFILE_MERGE_ROLLED_BACK =
            "playback_performance_profile_merge_rolled_back";
    private static final String KEY_PROFILE_MERGE_MIGRATED_MASK =
            "playback_performance_profile_merge_migrated_mask";
    private static final String KEY_PROFILE_AUTO_LIGHT_MIGRATED =
            "playback_performance_profile_auto_light_v1";
    private static final String KEY_CODEC_ASYNC_QUEUEING = "perf_codec_async_queueing";
    private static final String KEY_DYNAMIC_SCHEDULING = "perf_dynamic_scheduling";
    private static final String KEY_VIDEO_DURATION_PROGRESS = "perf_video_duration_progress";
    private static final String KEY_LATE_DROP_INPUT = "perf_late_drop_input";
    private static final String KEY_TRACK_LIMIT = "perf_track_limit";
    private static final String KEY_ADAPTIVE_DOWNGRADE = "perf_adaptive_downgrade";
    private static final String KEY_LOAD_ONLY_SELECTED_TRACKS = "perf_load_only_selected_tracks";
    private static final String KEY_SURFACE_FIXED_SIZE = "perf_surface_fixed_size";
    private static final String KEY_DECODER_FALLBACK = "perf_decoder_fallback";
    private static final String KEY_SOFT_VIDEO_TUNE = "perf_soft_video_tune";
    private static final String KEY_HIGH_BUFFER = "perf_high_buffer";
    private static final String KEY_BANDWIDTH_METER = "perf_bandwidth_meter";

    public static void ensureInitialized() {
        PlaybackExperimentSetting.ensureInitialized();
        if (!Prefers.getPrefers().contains(KEY_INITIALIZED)) {
            applyAutoValues();
            Prefers.put(KEY_INITIALIZED, true);
        }
        migrateProfiles();
        migrateBufferWatermarks();
        migrateExoSizePriority();
        migratePreloadDefaults();
        migrateExoLoadControl();
        migrateExoBackBuffer();
        migrateExoRebuffer();
        migrateMpvRebuffer();
        migrateMpvAutoBaseline();
    }

    public static int getProfile() {
        return getProfile(PlayerSetting.getPlayer());
    }

    public static int getProfile(int kernel) {
        ensureInitialized();
        return PlaybackProfileMergePolicy.effectiveProfile(
                rawProfile(kernel),
                profileMergeResolution().mergeEnabled());
    }

    public static void applyAuto() {
        int kernel = PlayerSetting.getPlayer();
        applyAutoProfile(kernel);
        putCurrentProfile(PROFILE_AUTO);
    }

    public static void applyRecommended() {
        int kernel = PlayerSetting.getPlayer();
        applyRecommendedProfile(kernel);
        putCurrentProfile(PROFILE_RECOMMENDED);
    }

    private static void applyRecommendedProfile(int kernel) {
        KernelPerformanceSetting.applyPreset(kernel, PROFILE_RECOMMENDED);
        if (kernel == PlayerSetting.EXO) {
            putRecommendedFlags();
            ExoPerformanceSetting.applyRecommended();
            Prefers.put("render", PlayerSetting.RENDER_SURFACE);
            Prefers.put("tunnel", false);
            Prefers.put("exo_4k_compat", true);
        } else if (kernel == PlayerSetting.MPV) {
            MpvPerformanceSetting.applyRecommended();
        } else {
            IjkPerformanceSetting.applyRecommended();
        }
    }

    private static void applyAutoProfile(int kernel) {
        KernelPerformanceSetting.applyPreset(kernel, PROFILE_AUTO);
        if (kernel == PlayerSetting.EXO) {
            putRecommendedFlags();
            ExoPerformanceSetting.applyAuto();
            Prefers.put("render", PlayerSetting.RENDER_SURFACE);
            Prefers.put("tunnel", false);
            Prefers.put("exo_4k_compat", true);
        } else if (kernel == PlayerSetting.MPV) {
            MpvPerformanceSetting.applyAuto();
        } else {
            IjkPerformanceSetting.applyRecommended();
        }
    }

    private static void applyAutoValues() {
        for (int kernel : new int[]{PlayerSetting.EXO, PlayerSetting.MPV, PlayerSetting.IJK}) {
            applyAutoProfile(kernel);
            Prefers.put(profileKey(kernel), PROFILE_AUTO);
        }
        Prefers.put(KEY_PROFILE, PROFILE_AUTO);
    }

    public static void applyCompatible() {
        int kernel = PlayerSetting.getPlayer();
        KernelPerformanceSetting.applyPreset(kernel, PROFILE_COMPATIBLE);
        applyKernelSpecificPreset(kernel, PROFILE_COMPATIBLE);
        if (kernel == PlayerSetting.EXO) {
            put(KEY_CODEC_ASYNC_QUEUEING, true);
            put(KEY_DYNAMIC_SCHEDULING, false);
            put(KEY_VIDEO_DURATION_PROGRESS, false);
            put(KEY_LATE_DROP_INPUT, false);
            put(KEY_TRACK_LIMIT, true);
            put(KEY_ADAPTIVE_DOWNGRADE, true);
            put(KEY_LOAD_ONLY_SELECTED_TRACKS, false);
            put(KEY_SURFACE_FIXED_SIZE, false);
            put(KEY_DECODER_FALLBACK, true);
            put(KEY_SOFT_VIDEO_TUNE, true);
            put(KEY_HIGH_BUFFER, true);
            put(KEY_BANDWIDTH_METER, false);
            Prefers.put("render", PlayerSetting.RENDER_SURFACE);
            Prefers.put("tunnel", false);
            Prefers.put("exo_4k_compat", false);
        }
        putCurrentProfile(PROFILE_COMPATIBLE);
    }

    public static void applyLightweight() {
        int kernel = PlayerSetting.getPlayer();
        KernelPerformanceSetting.applyPreset(kernel, PROFILE_LIGHTWEIGHT);
        applyKernelSpecificPreset(kernel, PROFILE_LIGHTWEIGHT);
        if (kernel == PlayerSetting.EXO) {
            put(KEY_CODEC_ASYNC_QUEUEING, true);
            put(KEY_DYNAMIC_SCHEDULING, false);
            put(KEY_VIDEO_DURATION_PROGRESS, false);
            put(KEY_LATE_DROP_INPUT, false);
            put(KEY_TRACK_LIMIT, true);
            put(KEY_ADAPTIVE_DOWNGRADE, true);
            put(KEY_LOAD_ONLY_SELECTED_TRACKS, true);
            put(KEY_SURFACE_FIXED_SIZE, false);
            put(KEY_DECODER_FALLBACK, true);
            put(KEY_SOFT_VIDEO_TUNE, true);
            put(KEY_HIGH_BUFFER, true);
            put(KEY_BANDWIDTH_METER, false);
            Prefers.put("render", PlayerSetting.RENDER_SURFACE);
            Prefers.put("tunnel", false);
            Prefers.put("exo_4k_compat", false);
        }
        putCurrentProfile(PROFILE_LIGHTWEIGHT);
    }

    private static void applyLightweightProfile(int kernel) {
        KernelPerformanceSetting.applyPreset(kernel, PROFILE_LIGHTWEIGHT);
        if (kernel == PlayerSetting.EXO) {
            putRecommendedFlags();
            ExoPerformanceSetting.applyLightweight();
            Prefers.put("render", PlayerSetting.RENDER_SURFACE);
            Prefers.put("tunnel", false);
            Prefers.put("exo_4k_compat", true);
        } else if (kernel == PlayerSetting.MPV) {
            MpvPerformanceSetting.applyLightweight();
        } else {
            IjkPerformanceSetting.applyLightweight();
        }
    }

    public static void applyOriginal() {
        KernelPerformanceSetting.applyOriginal(PlayerSetting.getPlayer());
        put(KEY_CODEC_ASYNC_QUEUEING, false);
        put(KEY_DYNAMIC_SCHEDULING, false);
        put(KEY_VIDEO_DURATION_PROGRESS, false);
        put(KEY_LATE_DROP_INPUT, false);
        put(KEY_TRACK_LIMIT, false);
        put(KEY_ADAPTIVE_DOWNGRADE, false);
        put(KEY_LOAD_ONLY_SELECTED_TRACKS, false);
        put(KEY_SURFACE_FIXED_SIZE, false);
        put(KEY_DECODER_FALLBACK, true);
        put(KEY_SOFT_VIDEO_TUNE, true);
        put(KEY_HIGH_BUFFER, false);
        put(KEY_BANDWIDTH_METER, false);
        Prefers.put("render", PlayerSetting.RENDER_SURFACE);
        Prefers.put("tunnel", false);
        Prefers.put("exo_4k_compat", false);
        putCurrentProfile(PROFILE_ORIGINAL);
    }

    public static void markCustom() {
        ensureInitialized();
        putCurrentProfile(PROFILE_CUSTOM);
    }

    public static String getProfileName() {
        return switch (getProfile()) {
            case PROFILE_AUTO -> "自动";
            case PROFILE_COMPATIBLE -> "兼容";
            case PROFILE_LIGHTWEIGHT -> "轻量";
            case PROFILE_CUSTOM -> "自定义";
            case PROFILE_ORIGINAL -> "原版";
            default -> "均衡";
        };
    }

    public static boolean isRecommended() {
        return getProfile() == PROFILE_RECOMMENDED;
    }

    public static boolean isRecommendedMerged() {
        return false;
    }

    public static boolean canChangeRecommendedMerge() {
        return false;
    }

    public static synchronized boolean rollbackRecommendedMerge() {
        return false;
    }

    public static synchronized boolean enableRecommendedMerge() {
        return true;
    }

    public static boolean isAuto() {
        return isAuto(PlayerSetting.getPlayer());
    }

    public static boolean isAuto(int kernel) {
        return getProfile(kernel) == PROFILE_AUTO;
    }

    public static boolean isCompatible() {
        return getProfile() == PROFILE_COMPATIBLE;
    }

    public static boolean isLightweight() {
        return getProfile() == PROFILE_LIGHTWEIGHT;
    }

    public static boolean isHighBufferEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_HIGH_BUFFER, true);
    }

    public static void putHighBufferEnabled(boolean value) {
        putCustom(KEY_HIGH_BUFFER, value);
    }

    public static boolean isBandwidthMeterEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_BANDWIDTH_METER, true);
    }

    public static void putBandwidthMeterEnabled(boolean value) {
        putCustom(KEY_BANDWIDTH_METER, value);
    }

    public static boolean isDynamicSchedulingEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_DYNAMIC_SCHEDULING, true);
    }

    public static void putDynamicSchedulingEnabled(boolean value) {
        putCustom(KEY_DYNAMIC_SCHEDULING, value);
    }

    public static boolean isCodecAsyncQueueingEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_CODEC_ASYNC_QUEUEING, true);
    }

    public static void putCodecAsyncQueueingEnabled(boolean value) {
        putCustom(KEY_CODEC_ASYNC_QUEUEING, value);
    }

    public static boolean isVideoDurationProgressEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_VIDEO_DURATION_PROGRESS, true);
    }

    public static void putVideoDurationProgressEnabled(boolean value) {
        putCustom(KEY_VIDEO_DURATION_PROGRESS, value);
    }

    public static boolean isLateDropInputEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_LATE_DROP_INPUT, true);
    }

    public static void putLateDropInputEnabled(boolean value) {
        putCustom(KEY_LATE_DROP_INPUT, value);
    }

    public static boolean isTrackLimitEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_TRACK_LIMIT, true);
    }

    public static void putTrackLimitEnabled(boolean value) {
        putCustom(KEY_TRACK_LIMIT, value);
    }

    public static boolean isAdaptiveDowngradeEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_ADAPTIVE_DOWNGRADE, true);
    }

    public static void putAdaptiveDowngradeEnabled(boolean value) {
        putCustom(KEY_ADAPTIVE_DOWNGRADE, value);
    }

    public static boolean isLoadOnlySelectedTracksEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_LOAD_ONLY_SELECTED_TRACKS, true);
    }

    public static void putLoadOnlySelectedTracksEnabled(boolean value) {
        putCustom(KEY_LOAD_ONLY_SELECTED_TRACKS, value);
    }

    public static boolean isSurfaceFixedSizeEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_SURFACE_FIXED_SIZE, true);
    }

    public static void putSurfaceFixedSizeEnabled(boolean value) {
        putCustom(KEY_SURFACE_FIXED_SIZE, value);
    }

    public static boolean isDecoderFallbackEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_DECODER_FALLBACK, true);
    }

    public static void putDecoderFallbackEnabled(boolean value) {
        putCustom(KEY_DECODER_FALLBACK, value);
    }

    public static boolean isSoftVideoTuneEnabled() {
        ensureInitialized();
        return Prefers.getBoolean(KEY_SOFT_VIDEO_TUNE, true);
    }

    public static void putSoftVideoTuneEnabled(boolean value) {
        putCustom(KEY_SOFT_VIDEO_TUNE, value);
    }

    public static String getSummary() {
        ensureInitialized();
        return getProfileName();
    }

    public static String getForwardBufferText() {
        ensureInitialized();
        return forwardBufferText(
                PlayerSetting.getPlayer(),
                getProfile(),
                PlayerSetting.getBuffer());
    }

    public static String getMemoryBufferText() {
        ensureInitialized();
        if (PlayerSetting.getPlayer() == PlayerSetting.IJK) {
            return ijkMemoryBufferText(
                    getProfile(),
                    IjkPerformanceSetting.getBufferMb());
        }
        return memoryBufferText(
                PlayerSetting.getPlayer(),
                getProfile(),
                PlayerSetting.getBufferBytesOption());
    }

    public static String getPlayedDataRetentionText() {
        ensureInitialized();
        return playedDataRetentionText(
                PlayerSetting.getPlayer(),
                getProfile(),
                PlayerSetting.getBackBufferOption());
    }

    public static String getPlaybackDiskCacheText() {
        ensureInitialized();
        return playbackDiskCacheText(PlayerSetting.getPlayCacheOption());
    }

    public static String getExoStartBufferText() {
        ensureInitialized();
        return isAuto(PlayerSetting.EXO)
                ? "自动 · 0.5～8秒"
                : secondsText(ExoPerformanceSetting.getStartBufferMs());
    }

    public static String getExoRebufferText() {
        ensureInitialized();
        return isAuto(PlayerSetting.EXO)
                ? "自动 · 1～15秒"
                : secondsText(ExoPerformanceSetting.getRebufferMs());
    }

    public static String getExoPrioritizeTimeText() {
        ensureInitialized();
        return isAuto(PlayerSetting.EXO)
                ? "自动 · 按资源"
                : onOff(ExoPerformanceSetting.isPrioritizeTime());
    }

    public static String getDetail() {
        ensureInitialized();
        return "配置：" + getProfileName()
                + "\n渲染：" + (PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE ? "SurfaceView" : "TextureView")
                + "\n轨道限制：" + onOff(isTrackLimitEnabled()) + "，自适应降级：" + onOff(isAdaptiveDowngradeEnabled())
                + "\n前向缓冲目标：" + getForwardBufferText() + "，内存缓冲上限：" + getMemoryBufferText() + "，已播放数据保留：" + getPlayedDataRetentionText()
                + bufferWatermarksText()
                + playbackDiskCacheDetailText()
                + preloadDetailText()
                + "\nMediaCodec异步：" + onOff(isCodecAsyncQueueingEnabled()) + "，动态调度：" + onOff(isDynamicSchedulingEnabled())
                + "\n解码耗时推进：" + onOff(isVideoDurationProgressEnabled()) + "，输入丢帧阈值：" + onOff(isLateDropInputEnabled())
                + "\n只加载选中轨道：" + onOff(isLoadOnlySelectedTracksEnabled()) + "，Surface固定尺寸：" + onOff(isSurfaceFixedSizeEnabled())
                + "\n动态网络保护：" + ExoPerformanceSetting.getNetworkProtectionText()
                + "\n音频直通：" + onOff(PlayerSetting.isAudioPassThrough()) + "，AAC优先：" + onOff(PlayerSetting.isPreferAAC())
                + "\n视频软解优先：" + onOff(PlayerSetting.isVideoPrefer()) + "，音频软解优先：" + onOff(PlayerSetting.isAudioPrefer())
                + "\n软解降负载：" + onOff(isSoftVideoTuneEnabled());
    }

    private static void putRecommendedFlags() {
        put(KEY_CODEC_ASYNC_QUEUEING, true);
        put(KEY_DYNAMIC_SCHEDULING, true);
        put(KEY_VIDEO_DURATION_PROGRESS, true);
        put(KEY_LATE_DROP_INPUT, true);
        put(KEY_TRACK_LIMIT, true);
        put(KEY_ADAPTIVE_DOWNGRADE, true);
        put(KEY_LOAD_ONLY_SELECTED_TRACKS, true);
        put(KEY_SURFACE_FIXED_SIZE, true);
        put(KEY_DECODER_FALLBACK, true);
        put(KEY_SOFT_VIDEO_TUNE, true);
        put(KEY_HIGH_BUFFER, true);
        put(KEY_BANDWIDTH_METER, true);
    }

    private static int clampProfile(int profile) {
        return profile == PROFILE_COMPATIBLE || profile == PROFILE_CUSTOM || profile == PROFILE_LIGHTWEIGHT || profile == PROFILE_ORIGINAL || profile == PROFILE_AUTO ? profile : PROFILE_RECOMMENDED;
    }

    private static void put(String key, boolean value) {
        Prefers.put(key, value);
    }

    private static void putCustom(String key, boolean value) {
        ensureInitialized();
        Prefers.put(key, value);
        markCustom();
    }

    private static void migrateProfiles() {
        if (Prefers.getBoolean(KEY_PROFILE_MIGRATED)) return;
        int oldProfile = clampProfile(Prefers.getInt(KEY_PROFILE, PROFILE_RECOMMENDED));
        Prefers.put(KEY_PROFILE_EXO, oldProfile);
        Prefers.put(KEY_PROFILE_MPV, oldProfile);
        Prefers.put(KEY_PROFILE_IJK, oldProfile);
        applyKernelSpecificPreset(PlayerSetting.EXO, oldProfile);
        applyKernelSpecificPreset(PlayerSetting.MPV, oldProfile);
        applyKernelSpecificPreset(PlayerSetting.IJK, oldProfile);
        Prefers.put(KEY_PROFILE_MIGRATED, true);
    }

    private static void migrateBufferWatermarks() {
        if (Prefers.getBoolean(KEY_BUFFER_WATERMARKS_MIGRATED)) return;
        int exoProfile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.EXO), PROFILE_RECOMMENDED));
        int mpvProfile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.MPV), PROFILE_RECOMMENDED));
        if (exoProfile != PROFILE_CUSTOM) ExoPerformanceSetting.applyRebufferPreset(exoProfile);
        if (mpvProfile != PROFILE_CUSTOM) MpvPerformanceSetting.applyRebufferPreset(mpvProfile);
        Prefers.put(KEY_BUFFER_WATERMARKS_MIGRATED, true);
    }

    private static void migrateExoSizePriority() {
        if (Prefers.getBoolean(KEY_EXO_SIZE_PRIORITY_MIGRATED)) return;
        int exoProfile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.EXO), PROFILE_RECOMMENDED));
        if (shouldMigrateExoSizePriority(exoProfile)) ExoPerformanceSetting.applyPrioritizeTimePreset(exoProfile);
        Prefers.put(KEY_EXO_SIZE_PRIORITY_MIGRATED, true);
    }

    static boolean shouldMigrateExoSizePriority(int profile) {
        return clampProfile(profile) != PROFILE_CUSTOM;
    }

    private static void migratePreloadDefaults() {
        if (Prefers.getBoolean(KEY_PRELOAD_DEFAULTS_MIGRATED)) return;
        for (int kernel : new int[]{PlayerSetting.EXO, PlayerSetting.MPV, PlayerSetting.IJK}) {
            int profile = clampProfile(Prefers.getInt(profileKey(kernel), PROFILE_RECOMMENDED));
            if (shouldMigratePreloadDefaults(profile)) KernelPerformanceSetting.applyPreloadPreset(kernel, profile);
        }
        Prefers.put(KEY_PRELOAD_DEFAULTS_MIGRATED, true);
    }

    static boolean shouldMigratePreloadDefaults(int profile) {
        return clampProfile(profile) != PROFILE_CUSTOM;
    }

    private static void migrateExoLoadControl() {
        if (Prefers.getBoolean(KEY_EXO_LOAD_CONTROL_MIGRATED)) return;
        int profile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.EXO), PROFILE_RECOMMENDED));
        if (shouldMigrateExoLoadControl(profile)) {
            KernelPerformanceSetting.applyExoLoadControlPreset(profile);
            ExoPerformanceSetting.applyPrioritizeTimePreset(profile);
        }
        Prefers.put(KEY_EXO_LOAD_CONTROL_MIGRATED, true);
    }

    static boolean shouldMigrateExoLoadControl(int profile) {
        return clampProfile(profile) != PROFILE_CUSTOM;
    }

    private static void migrateExoBackBuffer() {
        if (Prefers.getBoolean(KEY_EXO_BACK_BUFFER_MIGRATED)) return;
        int profile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.EXO), PROFILE_RECOMMENDED));
        if (shouldMigrateExoBackBuffer(profile)) KernelPerformanceSetting.applyExoBackBufferPreset(profile);
        Prefers.put(KEY_EXO_BACK_BUFFER_MIGRATED, true);
    }

    static boolean shouldMigrateExoBackBuffer(int profile) {
        return clampProfile(profile) != PROFILE_CUSTOM;
    }

    private static void migrateExoRebuffer() {
        if (Prefers.getBoolean(KEY_EXO_REBUFFER_MIGRATED)) return;
        int profile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.EXO), PROFILE_RECOMMENDED));
        if (shouldMigrateExoRebuffer(profile)) ExoPerformanceSetting.applyRebufferPreset(profile);
        Prefers.put(KEY_EXO_REBUFFER_MIGRATED, true);
    }

    static boolean shouldMigrateExoRebuffer(int profile) {
        return clampProfile(profile) != PROFILE_CUSTOM;
    }

    private static void migrateMpvRebuffer() {
        if (Prefers.getBoolean(KEY_MPV_REBUFFER_MIGRATED)) return;
        int profile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.MPV), PROFILE_RECOMMENDED));
        if (shouldMigrateMpvRebuffer(profile)) MpvPerformanceSetting.applyRebufferPreset(profile);
        Prefers.put(KEY_MPV_REBUFFER_MIGRATED, true);
    }

    static boolean shouldMigrateMpvRebuffer(int profile) {
        return clampProfile(profile) != PROFILE_CUSTOM;
    }

    private static void migrateMpvAutoBaseline() {
        if (Prefers.getBoolean(KEY_MPV_AUTO_BASELINE_MIGRATED)) return;
        int profile = clampProfile(Prefers.getInt(profileKey(PlayerSetting.MPV), PROFILE_RECOMMENDED));
        if (shouldMigrateMpvAutoBaseline(profile)) {
            KernelPerformanceSetting.applyMpvAutoBaselinePreset();
        }
        Prefers.put(KEY_MPV_AUTO_BASELINE_MIGRATED, true);
    }

    static boolean shouldMigrateMpvAutoBaseline(int profile) {
        return clampProfile(profile) == PROFILE_AUTO;
    }

    private static synchronized void migrateRecommendedProfileMerge() {
        PlaybackProfileMergePolicy.Resolution resolution =
                profileMergeResolution();
        PlaybackProfileMergePolicy.State state = resolution.state();
        int[] kernels = {
                PlayerSetting.EXO, PlayerSetting.MPV, PlayerSetting.IJK};
        boolean[] migrate = new boolean[kernels.length];
        boolean profileChanged = false;
        if (resolution.mergeEnabled()) {
            for (int index = 0; index < kernels.length; index++) {
                int rawProfile = rawProfile(kernels[index]);
                migrate[index] = PlaybackProfileMergePolicy.shouldMigrate(
                        rawProfile, true);
                if (!migrate[index]) continue;
                state = state.withMigrated(mergeSlot(kernels[index]));
                profileChanged = true;
            }
        }
        int globalProfile = rawGlobalProfile();
        boolean migrateGlobal = resolution.mergeEnabled()
                && PlaybackProfileMergePolicy.shouldMigrate(
                globalProfile, true);
        if ((resolution.writeBack() || profileChanged)
                && !writeProfileMergeState(state)) {
            return;
        }
        if (!resolution.mergeEnabled()) {
            if (resolution.sourceValid()
                    && state.rolledBack()
                    && state.migratedMask() != 0) {
                completeRecommendedProfileRollback(state);
            }
            return;
        }
        for (int index = 0; index < kernels.length; index++) {
            if (!migrate[index]) continue;
            try {
                applyAutoProfile(kernels[index]);
                Prefers.put(profileKey(kernels[index]), PROFILE_AUTO);
            } catch (Throwable ignored) {
            }
        }
        if (migrateGlobal) {
            try {
                Prefers.put(KEY_PROFILE, PROFILE_AUTO);
            } catch (Throwable ignored) {
            }
        }
        if (PlaybackProfileAbSetting.isEnrolled()) {
            PlaybackProfileAbSetting.putEnrolled(false);
        }
    }

    private static synchronized void migrateAutoLightProfiles() {
        if (Prefers.getBoolean(KEY_PROFILE_AUTO_LIGHT_MIGRATED)) return;
        try {
            for (int kernel : new int[]{
                    PlayerSetting.EXO, PlayerSetting.MPV, PlayerSetting.IJK}) {
                int rawProfile = rawProfile(kernel);
                int targetProfile = PlaybackProfileMergePolicy.effectiveProfile(
                        rawProfile, true);
                switch (PlaybackProfileMergePolicy.consolidationAction(
                        rawProfile)) {
                    case APPLY_AUTO -> applyAutoProfile(kernel);
                    case APPLY_LIGHTWEIGHT -> applyLightweightProfile(kernel);
                    case KEEP -> {
                    }
                }
                Prefers.put(profileKey(kernel), targetProfile);
            }
            Prefers.put(KEY_PROFILE, rawProfile(PlayerSetting.getPlayer()));
            Prefers.put(KEY_PROFILE_AUTO_LIGHT_MIGRATED, true);
        } catch (Throwable ignored) {
            // Partial writes are safe: without the completion marker the
            // idempotent migration is retried on the next initialization.
        }
    }

    private static void completeRecommendedProfileRollback(
            PlaybackProfileMergePolicy.State state) {
        PlaybackProfileMergePolicy.State pending = state;
        for (int kernel : new int[]{
                PlayerSetting.EXO, PlayerSetting.MPV, PlayerSetting.IJK}) {
            PlaybackProfileMergePolicy.Slot slot = mergeSlot(kernel);
            if (!pending.wasMigrated(slot)) continue;
            int rawProfile = rawProfile(kernel);
            if (PlaybackProfileMergePolicy.shouldRestore(
                    pending, slot, rawProfile)) {
                try {
                    applyRecommendedProfile(kernel);
                    Prefers.put(profileKey(kernel), PROFILE_RECOMMENDED);
                    rawProfile = PROFILE_RECOMMENDED;
                } catch (Throwable ignored) {
                    continue;
                }
            }
            if (kernel == PlayerSetting.getPlayer()) {
                try {
                    Prefers.put(KEY_PROFILE, rawProfile);
                } catch (Throwable ignored) {
                    continue;
                }
            }
            PlaybackProfileMergePolicy.State completed =
                    pending.withoutMigrated(slot);
            if (!writeProfileMergeState(completed)) return;
            pending = completed;
        }
        try {
            Prefers.put(KEY_PROFILE, rawProfile(PlayerSetting.getPlayer()));
        } catch (Throwable ignored) {
        }
    }

    private static PlaybackProfileMergePolicy.Resolution
    profileMergeResolution() {
        Map<String, ?> values;
        try {
            values = Prefers.getPrefers().getAll();
        } catch (Throwable ignored) {
            return PlaybackProfileMergePolicy.resolve(
                    new PlaybackProfileMergePolicy.RawState(
                            "unavailable", null, null));
        }
        return PlaybackProfileMergePolicy.resolve(
                new PlaybackProfileMergePolicy.RawState(
                        values.get(KEY_PROFILE_MERGE_SCHEMA),
                        values.get(KEY_PROFILE_MERGE_ROLLED_BACK),
                        values.get(KEY_PROFILE_MERGE_MIGRATED_MASK)));
    }

    private static boolean writeProfileMergeState(
            PlaybackProfileMergePolicy.State state) {
        PlaybackProfileMergePolicy.State safe = state == null
                ? PlaybackProfileMergePolicy.State.legacyRollback() : state;
        try {
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            editor.putInt(KEY_PROFILE_MERGE_SCHEMA,
                    PlaybackProfileMergePolicy.CURRENT_SCHEMA_VERSION);
            editor.putBoolean(KEY_PROFILE_MERGE_ROLLED_BACK,
                    safe.rolledBack());
            editor.putInt(KEY_PROFILE_MERGE_MIGRATED_MASK,
                    safe.migratedMask());
            editor.apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int rawProfile(int kernel) {
        return rawProfileValue(
                profileKey(PlayerSetting.sanitizePlayer(kernel)),
                rawGlobalProfile());
    }

    private static int rawGlobalProfile() {
        return rawProfileValue(KEY_PROFILE, PROFILE_AUTO);
    }

    private static int rawProfileValue(String key, int fallback) {
        try {
            Object value = Prefers.getPrefers().getAll().get(key);
            return value instanceof Number
                    ? clampProfile(((Number) value).intValue())
                    : clampProfile(fallback);
        } catch (Throwable ignored) {
            return clampProfile(fallback);
        }
    }

    private static PlaybackProfileMergePolicy.Slot mergeSlot(int kernel) {
        return switch (PlayerSetting.sanitizePlayer(kernel)) {
            case PlayerSetting.MPV -> PlaybackProfileMergePolicy.Slot.MPV;
            case PlayerSetting.IJK -> PlaybackProfileMergePolicy.Slot.IJK;
            default -> PlaybackProfileMergePolicy.Slot.EXO;
        };
    }

    private static void applyKernelSpecificPreset(int kernel, int profile) {
        if (kernel == PlayerSetting.EXO) {
            if (profile == PROFILE_COMPATIBLE) ExoPerformanceSetting.applyCompatible();
            else if (profile == PROFILE_LIGHTWEIGHT) ExoPerformanceSetting.applyLightweight();
            else if (profile == PROFILE_AUTO) ExoPerformanceSetting.applyAuto();
            else ExoPerformanceSetting.applyRecommended();
        } else if (kernel == PlayerSetting.MPV) {
            if (profile == PROFILE_COMPATIBLE) MpvPerformanceSetting.applyCompatible();
            else if (profile == PROFILE_LIGHTWEIGHT) MpvPerformanceSetting.applyLightweight();
            else if (profile == PROFILE_AUTO) MpvPerformanceSetting.applyAuto();
            else MpvPerformanceSetting.applyRecommended();
        } else {
            if (profile == PROFILE_COMPATIBLE) IjkPerformanceSetting.applyCompatible();
            else if (profile == PROFILE_LIGHTWEIGHT) IjkPerformanceSetting.applyLightweight();
            else IjkPerformanceSetting.applyRecommended();
        }
    }

    private static void putCurrentProfile(int profile) {
        int value = clampProfile(profile);
        Prefers.put(profileKey(PlayerSetting.getPlayer()), value);
        Prefers.put(KEY_PROFILE, value);
    }

    private static String profileKey(int kernel) {
        return switch (kernel) {
            case PlayerSetting.IJK -> KEY_PROFILE_IJK;
            case PlayerSetting.MPV -> KEY_PROFILE_MPV;
            default -> KEY_PROFILE_EXO;
        };
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    static String forwardBufferText(int kernel, int profile, int level) {
        int normalized = Math.clamp(level, 1, 10);
        if (kernel == PlayerSetting.EXO) {
            if (profile == PROFILE_AUTO) return "自动 · 网络30～60秒";
            if (profile == PROFILE_LIGHTWEIGHT || profile == PROFILE_COMPATIBLE) return "15～30秒";
            if (profile == PROFILE_RECOMMENDED) return "30～60秒";
            int minBufferMs = 15_000 + (normalized - 1) * 15_000 / 9;
            return secondsRangeText(minBufferMs, minBufferMs * 2);
        }
        if (kernel == PlayerSetting.MPV) {
            int targetSeconds = Math.min(60, Math.max(15, normalized * 3));
            return (profile == PROFILE_AUTO ? "自动 · " : "") + "目标" + targetSeconds + "秒";
        }
        return "由读包内存和水位控制";
    }

    static String memoryBufferText(int kernel, int profile, int option) {
        if (profile == PROFILE_AUTO) {
            if (kernel == PlayerSetting.EXO) return "自动 · 16～192MB";
            if (kernel == PlayerSetting.MPV) return "自动 · 24～192MB";
        }
        return switch (Math.clamp(option, 0, 3)) {
            case 1 -> "64MB";
            case 2 -> "128MB";
            case 3 -> "256MB";
            default -> kernel == PlayerSetting.MPV
                    ? "默认64MB" : "设备自适应 · 最高256MB";
        };
    }

    static String ijkMemoryBufferText(int profile, int bufferMb) {
        return profile == PROFILE_AUTO
                ? "自动 · 读包4～15MB"
                : "读包" + Math.max(0, bufferMb) + "MB";
    }

    static String playedDataRetentionText(int kernel, int profile, int option) {
        if (kernel == PlayerSetting.IJK) return "无独立保留";
        if (kernel == PlayerSetting.MPV) {
            if (profile == PROFILE_AUTO) return "自动 · 0～64MB";
            return switch (Math.clamp(option, 0, 3)) {
                case 1 -> "少量 · 至少16MB";
                case 2 -> "中等 · 至少32MB";
                case 3 -> "与前向内存相同";
                default -> "关闭";
            };
        }
        return switch (Math.clamp(option, 0, 3)) {
            case 1 -> "15秒";
            case 2 -> "30秒";
            case 3 -> "60秒";
            default -> "关闭";
        };
    }

    static String playbackDiskCacheText(int option) {
        return switch (Math.clamp(option, 0, 4)) {
            case 1 -> "256MB";
            case 2 -> "512MB";
            case 3 -> "1GB";
            case 4 -> "2GB";
            default -> "128MB";
        };
    }

    private static String bufferWatermarksText() {
        return switch (PlayerSetting.getPlayer()) {
            case PlayerSetting.EXO -> "\n起播阈值：" + getExoStartBufferText() + "，重缓冲恢复：" + getExoRebufferText();
            case PlayerSetting.MPV -> "\n参数优先级：" + MpvPerformanceSetting.getOptionPriorityText() + "，重缓冲恢复：" + secondsText(MpvPerformanceSetting.getRebufferMs());
            default -> "";
        };
    }

    private static String playbackDiskCacheDetailText() {
        return PlayerSetting.getPlayer() == PlayerSetting.EXO
                ? "" : "\nHLS 磁盘缓存上限：" + getPlaybackDiskCacheText();
    }

    private static String preloadDetailText() {
        if (!isAuto()) {
            return "\n磁盘预载：" + onOff(PreloadSetting.isPreload()) + "，并发：" + PreloadSetting.getPreloadThreads() + "，磁盘配额：" + PreloadSetting.getPreloadSizeMb() + "MB，单次时长：" + PreloadSetting.getPreloadTimeSeconds() + "秒，向前目标：" + preloadAheadText();
        }
        return "\n磁盘预载：自动，并发：0～2，磁盘配额：" + PreloadSetting.getPreloadSizeMb() + "MB，单次时长：10～30秒，向前目标：" + preloadAheadText();
    }

    private static String preloadAheadText() {
        int seconds = PreloadSetting.getPreloadAheadSeconds();
        return seconds == PreloadSetting.WHOLE_MEDIA_AHEAD_SECONDS
                ? "整部" : seconds / 60 + "分钟";
    }

    private static String secondsText(int milliseconds) {
        return milliseconds % 1000 == 0 ? milliseconds / 1000 + "秒" : String.format(java.util.Locale.US, "%.1f秒", milliseconds / 1000f);
    }

    private static String secondsRangeText(int minimumMs, int maximumMs) {
        if (minimumMs % 1000 == 0 && maximumMs % 1000 == 0) {
            return minimumMs / 1000 + "～" + maximumMs / 1000 + "秒";
        }
        return secondsText(minimumMs) + "～" + secondsText(maximumMs);
    }
}
