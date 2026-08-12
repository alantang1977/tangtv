package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvCacheTimePolicyTest {

    @Test
    public void cacheIsTheSingleTimeMasterAndReadaheadUsesNativeFallback() {
        MpvCacheTimePolicy.Decision decision = resolve(
                false, 30, 2_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.REMOTE);

        assertEquals(MpvCacheTimePolicy.Master.CACHE_SECONDS, decision.master());
        assertEquals(30, decision.cacheSeconds());
        assertEquals(MpvCacheTimePolicy.NATIVE_DEMUXER_READAHEAD_SECONDS,
                decision.demuxerReadaheadSeconds());
        assertEquals(0, decision.hysteresisSeconds());
        assertEquals(1, MpvPlayerConfig.DEFAULT_DEMUXER_READAHEAD_SECONDS);
    }

    @Test
    public void automaticRemoteProgressiveUsesFiveSecondExperimentAtDefaultTarget() {
        MpvCacheTimePolicy.Decision decision = resolve(
                true, 30, 2_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.REMOTE);

        assertEquals(5, decision.hysteresisSeconds());
        assertEquals(MpvCacheTimePolicy.Reason.DIRECT_REMOTE_EXPERIMENT,
                decision.reason());
    }

    @Test
    public void experimentalHysteresisIsBoundedToFourFiveAndSixSeconds() {
        assertEquals(4, MpvCacheTimePolicy.experimentalHysteresisSeconds(15, 2));
        assertEquals(5, MpvCacheTimePolicy.experimentalHysteresisSeconds(30, 2));
        assertEquals(6, MpvCacheTimePolicy.experimentalHysteresisSeconds(60, 2));
    }

    @Test
    public void highRebufferWatermarkDisablesHysteresisExperiment() {
        MpvCacheTimePolicy.Decision decision = resolve(
                true, 30, 5_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.REMOTE);

        assertEquals(0, decision.hysteresisSeconds());
        assertEquals(MpvCacheTimePolicy.Reason.REBUFFER_GUARD, decision.reason());
    }

    @Test
    public void localPlaybackNeverUsesNetworkHysteresisExperiment() {
        MpvCacheTimePolicy.Decision decision = resolve(
                true, 30, 2_000,
                PlaybackAutoContext.Protocol.LOCAL,
                PlaybackAutoContext.PathKind.LOCAL);

        assertEquals(0, decision.hysteresisSeconds());
        assertEquals(MpvCacheTimePolicy.Reason.PROTOCOL_GUARD, decision.reason());
    }

    @Test
    public void loopbackAndAppInternalPathsKeepHysteresisDisabled() {
        for (PlaybackAutoContext.PathKind path : new PlaybackAutoContext.PathKind[]{
                PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE}) {
            MpvCacheTimePolicy.Decision decision = resolve(
                    true, 30, 2_000,
                    PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                    path);
            assertEquals(0, decision.hysteresisSeconds());
            assertEquals(MpvCacheTimePolicy.Reason.PATH_GUARD, decision.reason());
        }
    }

    @Test
    public void segmentedAndRealtimeProtocolsNeverUseInitialHysteresisExperiment() {
        for (PlaybackAutoContext.Protocol protocol : new PlaybackAutoContext.Protocol[]{
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.Protocol.DASH,
                PlaybackAutoContext.Protocol.RTSP,
                PlaybackAutoContext.Protocol.RTMP}) {
            MpvCacheTimePolicy.Decision decision = resolve(
                    true, 30, 2_000, protocol,
                    PlaybackAutoContext.PathKind.REMOTE);
            assertEquals(0, decision.hysteresisSeconds());
            assertEquals(MpvCacheTimePolicy.Reason.PROTOCOL_GUARD, decision.reason());
        }
    }

    @Test
    public void unknownSourceKeepsContinuousPrefetching() {
        MpvCacheTimePolicy.Decision decision = resolve(
                true, 30, 2_000,
                PlaybackAutoContext.Protocol.UNKNOWN,
                PlaybackAutoContext.PathKind.UNKNOWN);

        assertEquals(0, decision.hysteresisSeconds());
        assertEquals(MpvCacheTimePolicy.Reason.UNKNOWN_SOURCE, decision.reason());
    }

    @Test
    public void configPriorityLeavesAllRuntimeTimeOptionsToMpvConf() {
        MpvCacheTimePolicy.Decision decision = MpvCacheTimePolicy.resolve(
                false,
                true,
                true,
                30,
                MpvCacheTimePolicy.NATIVE_DEMUXER_READAHEAD_SECONDS,
                2_000,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.PathKind.REMOTE);

        assertFalse(decision.runtimeManaged());
        assertEquals(MpvCacheTimePolicy.Master.MPV_CONF, decision.master());
        assertEquals(MpvCacheTimePolicy.Reason.CONFIG_PRIORITY, decision.reason());
        assertTrue(decision.runtimeOptions().isEmpty());
    }

    @Test
    public void disabledCacheUsesDemuxerReadaheadAsTheOnlyMaster() {
        MpvCacheTimePolicy.Decision decision = MpvCacheTimePolicy.resolve(
                true,
                true,
                false,
                30,
                1,
                2_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.REMOTE);

        assertEquals(MpvCacheTimePolicy.Master.DEMUXER_READAHEAD, decision.master());
        assertEquals(MpvCacheTimePolicy.Reason.CACHE_DISABLED, decision.reason());
        assertEquals(0, decision.hysteresisSeconds());
    }

    @Test
    public void nonAutomaticProfilesRemoveDuplicateTimeTargetsWithoutExperimenting() {
        MpvCacheTimePolicy.Decision decision = resolve(
                false, 60, 2_000,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.PathKind.REMOTE);

        assertTrue(decision.runtimeManaged());
        assertEquals(MpvCacheTimePolicy.Reason.STATIC_TARGET, decision.reason());
        assertEquals(60, decision.cacheSeconds());
        assertEquals(1, decision.demuxerReadaheadSeconds());
        assertEquals(0, decision.hysteresisSeconds());
    }

    @Test
    public void runtimeOptionMappingHasStableOrderAndNoDuplicateTarget() {
        MpvCacheTimePolicy.Decision decision = resolve(
                true, 30, 2_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.LAN_PRIVATE);

        Map<String, String> options = decision.runtimeOptions();
        assertEquals(List.of(
                        "cache-secs",
                        "demuxer-readahead-secs",
                        "demuxer-hysteresis-secs"),
                new ArrayList<>(options.keySet()));
        assertEquals("30", options.get("cache-secs"));
        assertEquals("1", options.get("demuxer-readahead-secs"));
        assertEquals("5", options.get("demuxer-hysteresis-secs"));
    }

    private static MpvCacheTimePolicy.Decision resolve(
            boolean automatic,
            int cacheSeconds,
            int rebufferMs,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.PathKind path) {
        return MpvCacheTimePolicy.resolve(
                true,
                automatic,
                true,
                cacheSeconds,
                MpvCacheTimePolicy.NATIVE_DEMUXER_READAHEAD_SECONDS,
                rebufferMs,
                protocol,
                path);
    }
}
