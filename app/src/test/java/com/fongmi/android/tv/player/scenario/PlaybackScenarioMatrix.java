package com.fongmi.android.tv.player.scenario;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Shared, risk-driven test scenarios for cross-kernel playback policy checks. */
public final class PlaybackScenarioMatrix {

    public static final long NOW_MS = 10_000L;
    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;

    private static final long DEFAULT_DISK_CONFIGURED = 2L * GIB;
    private static final long DEFAULT_DISK_EXISTING = 512L * MIB;
    private static final long DEFAULT_DISK_AVAILABLE = 20L * GIB;
    private static final long DEFAULT_DISK_TOTAL = 64L * GIB;

    private static final List<Scenario> SCENARIOS = List.of(
            scenario(
                    Id.LOW_RAM_HLS_VOD,
                    axes(Axis.LOW_RAM, Axis.HLS),
                    PlaybackAutoContext.Protocol.HLS,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.SEGMENT,
                    hlsVodManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.WIFI,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(192, 48, true, 768, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 30f,
                    8_000_000L, 12_000_000L,
                    12_000, -1, 0,
                    30f, 30f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.ORDINARY_TV_PROGRESSIVE,
                    axes(Axis.ORDINARY_TV, Axis.PROGRESSIVE),
                    PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.CONTINUOUS,
                    PlaybackAutoContext.ManifestFacts.none(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(384, 192, false, 1_536, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 30f,
                    12_000_000L, 20_000_000L,
                    20_000, -1, 0,
                    30f, 30f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.FOUR_K_HDR_DASH,
                    axes(Axis.FOUR_K_HDR, Axis.DASH),
                    PlaybackAutoContext.Protocol.DASH,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.SEGMENT,
                    dashVodManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(1_024, 512, false, 3_072, 512, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.HDR10,
                    3840, 2160, 60f,
                    50_000_000L, 100_000_000L,
                    30_000, -1, 0,
                    60f, 60f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.REMUX_EXTERNAL_LOOPBACK,
                    axes(Axis.REMUX, Axis.PROGRESSIVE, Axis.LOOPBACK),
                    PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.CONTINUOUS,
                    PlaybackAutoContext.ManifestFacts.none(),
                    PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                    PlaybackAutoContext.PathKind.UNKNOWN,
                    PlaybackAutoContext.UpstreamState.OPAQUE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.HDR10,
                    3840, 2160, 24f,
                    80_000_000L, 120_000_000L,
                    20_000, -1, 0,
                    24f, 24f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.VPN_HLS_VOD,
                    axes(Axis.VPN, Axis.HLS),
                    PlaybackAutoContext.Protocol.HLS,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.SEGMENT,
                    hlsVodManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.VPN,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 60f,
                    15_000_000L, 25_000_000L,
                    16_000, -1, 0,
                    60f, 60f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.HLS_LIVE,
                    axes(Axis.HLS, Axis.LIVE),
                    PlaybackAutoContext.Protocol.HLS,
                    PlaybackAutoContext.StreamKind.LIVE,
                    PlaybackAutoContext.TransferUnit.SEGMENT,
                    hlsLiveManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.WIFI,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 50f,
                    10_000_000L, 18_000_000L,
                    10_000, 30_000, 1,
                    50f, 50f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.LL_HLS_LIVE,
                    axes(Axis.HLS, Axis.LIVE),
                    PlaybackAutoContext.Protocol.HLS,
                    PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE,
                    PlaybackAutoContext.TransferUnit.PART,
                    llHlsManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.WIFI,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 50f,
                    8_000_000L, 14_000_000L,
                    3_000, 5_000, 0,
                    50f, 50f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.RTSP_LIVE,
                    axes(Axis.RTSP, Axis.LIVE),
                    PlaybackAutoContext.Protocol.RTSP,
                    PlaybackAutoContext.StreamKind.LIVE,
                    PlaybackAutoContext.TransferUnit.CONTINUOUS,
                    PlaybackAutoContext.ManifestFacts.none(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 25f,
                    8_000_000L, 12_000_000L,
                    30_000, 30_000, 0,
                    25f, 25f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.SOFTWARE_DECODE_THERMAL,
                    axes(Axis.SOFTWARE_DECODE),
                    PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.CONTINUOUS,
                    PlaybackAutoContext.ManifestFacts.none(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.SEVERE,
                    PlaybackAutoContext.DecodeMode.SOFTWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 30f,
                    20_000_000L, 30_000_000L,
                    12_000, -1, 0,
                    18f, 16f,
                    false, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.SEEK_RANGE_VOD,
                    axes(Axis.SEEK, Axis.PROGRESSIVE),
                    PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.CONTINUOUS,
                    PlaybackAutoContext.ManifestFacts.none(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 30f,
                    30_000_000L, 50_000_000L,
                    20_000, -1, 0,
                    30f, 30f,
                    true, false,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL),
            scenario(
                    Id.NEAR_FULL_DISK_HLS,
                    axes(Axis.NEAR_FULL_DISK, Axis.HLS),
                    PlaybackAutoContext.Protocol.HLS,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.SEGMENT,
                    hlsVodManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.WIFI,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(512, 256, false, 2_048, 256, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.SDR,
                    1920, 1080, 30f,
                    12_000_000L, 20_000_000L,
                    15_000, -1, 0,
                    30f, 30f,
                    false, false,
                    2L * GIB, 1L * GIB, 512L * MIB, 10L * GIB),
            scenario(
                    Id.CODEC_FALSE_POSITIVE_DASH,
                    axes(Axis.CODEC_FALSE_POSITIVE, Axis.FOUR_K_HDR, Axis.DASH),
                    PlaybackAutoContext.Protocol.DASH,
                    PlaybackAutoContext.StreamKind.VOD,
                    PlaybackAutoContext.TransferUnit.SEGMENT,
                    dashVodManifest(),
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.PathKind.REMOTE,
                    PlaybackAutoContext.UpstreamState.VISIBLE,
                    PlaybackAutoContext.NetworkTransport.ETHERNET,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    memory(1_024, 512, false, 3_072, 512, false),
                    PlaybackAutoContext.ThermalState.NOMINAL,
                    PlaybackAutoContext.DecodeMode.HARDWARE,
                    PlaybackAutoContext.HdrType.DOLBY_VISION,
                    3840, 2160, 60f,
                    45_000_000L, 90_000_000L,
                    20_000, -1, 0,
                    60f, 0f,
                    false, true,
                    DEFAULT_DISK_CONFIGURED, DEFAULT_DISK_EXISTING,
                    DEFAULT_DISK_AVAILABLE, DEFAULT_DISK_TOTAL));

    private PlaybackScenarioMatrix() {
    }

    public static List<Scenario> all() {
        return SCENARIOS;
    }

    public static Scenario get(Id id) {
        return SCENARIOS.stream()
                .filter(scenario -> scenario.id() == id)
                .findFirst()
                .orElseThrow();
    }

    public static List<Scenario> covering(Axis axis) {
        return SCENARIOS.stream()
                .filter(scenario -> scenario.covers(axis))
                .toList();
    }

    private static Scenario scenario(
            Id id,
            Set<Axis> axes,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            PlaybackAutoContext.TransferUnit transferUnit,
            PlaybackAutoContext.ManifestFacts manifest,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState,
            PlaybackAutoContext.NetworkTransport networkTransport,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            PlaybackAutoContext.MemorySnapshot memorySnapshot,
            PlaybackAutoContext.ThermalState thermal,
            PlaybackAutoContext.DecodeMode decodeMode,
            PlaybackAutoContext.HdrType hdrType,
            int width,
            int height,
            float frameRate,
            long averageBitrate,
            long peakBitrate,
            long bufferedDurationMs,
            long liveLagMs,
            int rebufferCount,
            float decodeFps,
            float outputFps,
            boolean userSeeking,
            boolean codecFailure,
            long diskConfiguredBytes,
            long diskExistingBytes,
            long diskAvailableBytes,
            long diskTotalBytes) {
        return new Scenario(
                id, axes, protocol, streamKind, transferUnit, manifest,
                playerPath, upstreamPath, upstreamState, networkTransport,
                memoryPressure, memorySnapshot, thermal, decodeMode, hdrType,
                width, height, frameRate, averageBitrate, peakBitrate,
                bufferedDurationMs, liveLagMs, rebufferCount,
                decodeFps, outputFps, userSeeking, codecFailure,
                diskConfiguredBytes, diskExistingBytes,
                diskAvailableBytes, diskTotalBytes);
    }

    private static Set<Axis> axes(Axis first, Axis... remaining) {
        EnumSet<Axis> axes = EnumSet.of(first);
        axes.addAll(Arrays.asList(remaining));
        return axes;
    }

    private static PlaybackAutoContext.MemorySnapshot memory(
            long heapLimitMib,
            long heapHeadroomMib,
            boolean lowRam,
            long systemAvailableMib,
            long systemThresholdMib,
            boolean systemLow) {
        long heapLimit = heapLimitMib * MIB;
        long heapHeadroom = heapHeadroomMib * MIB;
        return new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                Math.max(0, heapLimit - heapHeadroom),
                heapLimit,
                heapHeadroom,
                lowRam,
                4L * GIB,
                systemAvailableMib * MIB,
                systemThresholdMib * MIB,
                systemLow,
                null,
                100,
                64L * MIB);
    }

    private static PlaybackAutoContext.ManifestFacts hlsVodManifest() {
        return new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MASTER,
                true, 6_000L, null, 18_000L, 4, false, false);
    }

    private static PlaybackAutoContext.ManifestFacts hlsLiveManifest() {
        return new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA,
                false, 6_000L, null, 18_000L, 3, false, false);
    }

    private static PlaybackAutoContext.ManifestFacts llHlsManifest() {
        return new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA,
                false, 1_000L, 333L, 1_000L, 3, false, true);
    }

    private static PlaybackAutoContext.ManifestFacts dashVodManifest() {
        return new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.DASH_STATIC,
                true, null, null, null, 4, false, false);
    }

    private static long saturatingTriple(long value) {
        return value > Long.MAX_VALUE / 3 ? Long.MAX_VALUE : value * 3;
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(T value) {
        return PlaybackAutoContext.Fact.forSession(
                value,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH,
                NOW_MS);
    }

    public enum Axis {
        LOW_RAM,
        ORDINARY_TV,
        FOUR_K_HDR,
        HLS,
        DASH,
        PROGRESSIVE,
        REMUX,
        LOOPBACK,
        VPN,
        LIVE,
        RTSP,
        SOFTWARE_DECODE,
        SEEK,
        NEAR_FULL_DISK,
        CODEC_FALSE_POSITIVE
    }

    public enum Id {
        LOW_RAM_HLS_VOD,
        ORDINARY_TV_PROGRESSIVE,
        FOUR_K_HDR_DASH,
        REMUX_EXTERNAL_LOOPBACK,
        VPN_HLS_VOD,
        HLS_LIVE,
        LL_HLS_LIVE,
        RTSP_LIVE,
        SOFTWARE_DECODE_THERMAL,
        SEEK_RANGE_VOD,
        NEAR_FULL_DISK_HLS,
        CODEC_FALSE_POSITIVE_DASH
    }

    public record Scenario(
            Id id,
            Set<Axis> axes,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            PlaybackAutoContext.TransferUnit transferUnit,
            PlaybackAutoContext.ManifestFacts manifest,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.PathKind upstreamPath,
            PlaybackAutoContext.UpstreamState upstreamState,
            PlaybackAutoContext.NetworkTransport networkTransport,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            PlaybackAutoContext.MemorySnapshot memorySnapshot,
            PlaybackAutoContext.ThermalState thermal,
            PlaybackAutoContext.DecodeMode decodeMode,
            PlaybackAutoContext.HdrType hdrType,
            int width,
            int height,
            float frameRate,
            long averageBitrate,
            long peakBitrate,
            long bufferedDurationMs,
            long liveLagMs,
            int rebufferCount,
            float decodeFps,
            float outputFps,
            boolean userSeeking,
            boolean codecFailure,
            long diskConfiguredBytes,
            long diskExistingBytes,
            long diskAvailableBytes,
            long diskTotalBytes) {

        public Scenario {
            axes = Set.copyOf(axes);
        }

        public boolean covers(Axis axis) {
            return axes.contains(axis);
        }

        public PlaybackAutoContext context(PlaybackAutoContext.Kernel kernel) {
            PlaybackAutoContext.SessionToken session =
                    new PlaybackAutoContext.SessionToken(
                            "p-v02-" + Integer.toString(id.ordinal() + 1, 36),
                            id.ordinal() + 1L);
            PlaybackAutoContext.DeviceFacts device =
                    new PlaybackAutoContext.DeviceFacts(
                            fact(memoryPressure),
                            fact(memorySnapshot),
                            PlaybackAutoContext.Fact.unknown(-1L),
                            fact(thermal),
                            fact(PlaybackAutoContext.PowerState.NORMAL),
                            fact(PlaybackAutoContext.NetworkCost.UNMETERED),
                            fact(new PlaybackAutoContext.NetworkSnapshot(
                                    true, true, false, false,
                                    networkTransport,
                                    PlaybackAutoContext.DataSaverState.DISABLED)));
            PlaybackAutoContext.ResourceFacts resource =
                    new PlaybackAutoContext.ResourceFacts(
                            fact(protocol),
                            fact(streamKind),
                            fact(PlaybackAutoContext.RangeSupport.SUPPORTED),
                            fact(transferUnit),
                            fact(manifest));
            PlaybackAutoContext.PathFacts path =
                    new PlaybackAutoContext.PathFacts(
                            PlaybackAutoContext.Fact.unknown(PlaybackRoute.OTHER),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackRoute.Owner.UNKNOWN),
                            PlaybackAutoContext.Fact.unknown(false),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackRouteCapabilities.ControlScope.NONE),
                            fact(playerPath),
                            upstreamPath == PlaybackAutoContext.PathKind.UNKNOWN
                                    ? PlaybackAutoContext.Fact.unknown(upstreamPath)
                                    : fact(upstreamPath),
                            fact(upstreamState));
            PlaybackAutoContext.RuntimeFacts runtime =
                    new PlaybackAutoContext.RuntimeFacts(
                            fact(PlaybackAutoContext.PlaybackPhase.READY),
                            fact(bufferedDurationMs),
                            fact(saturatingTriple(averageBitrate)),
                            fact(averageBitrate),
                            fact(outputFps > 0 ? outputFps : frameRate),
                            fact(0L),
                            fact(rebufferCount),
                            fact(rebufferCount * 1_000L),
                            fact(1_000L),
                            liveLagMs >= 0
                                    ? fact(liveLagMs)
                                    : PlaybackAutoContext.Fact.unknown(-1L),
                            fact(false),
                            fact(120_000L),
                            streamKind == PlaybackAutoContext.StreamKind.VOD
                                    ? fact(3_600_000L)
                                    : PlaybackAutoContext.Fact.unknown(-1L));
            PlaybackAutoContext.TrackFacts video =
                    new PlaybackAutoContext.TrackFacts(
                            fact(hdrType == PlaybackAutoContext.HdrType.DOLBY_VISION
                                    ? "video/dolby-vision"
                                    : width >= 3840 || hdrType != PlaybackAutoContext.HdrType.SDR
                                    ? "video/hevc" : "video/avc"),
                            fact(hdrType == PlaybackAutoContext.HdrType.DOLBY_VISION
                                    ? "dvhe.05.06" : width >= 3840 ? "hvc1.2.4.L153" : "avc1.640028"),
                            fact(1),
                            fact(1),
                            fact(width),
                            fact(height),
                            fact(frameRate),
                            fact(hdrType),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackAutoContext.ColorSnapshot.unknown()),
                            fact(averageBitrate),
                            fact(peakBitrate));
            PlaybackAutoContext.TrackFacts audio =
                    new PlaybackAutoContext.TrackFacts(
                            fact("audio/mp4a-latm"),
                            fact("mp4a.40.2"),
                            fact(2),
                            fact(0),
                            PlaybackAutoContext.Fact.unknown(-1),
                            PlaybackAutoContext.Fact.unknown(-1),
                            PlaybackAutoContext.Fact.unknown(-1f),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackAutoContext.HdrType.UNKNOWN),
                            PlaybackAutoContext.Fact.unknown(
                                    PlaybackAutoContext.ColorSnapshot.unknown()),
                            fact(256_000L),
                            fact(320_000L));
            PlaybackAutoContext.DecoderFacts decoder =
                    new PlaybackAutoContext.DecoderFacts(
                            1,
                            fact(decodeMode == PlaybackAutoContext.DecodeMode.SOFTWARE
                                    ? "codec-sw" : "codec-hw"),
                            PlaybackAutoContext.Fact.unknown(""),
                            fact(decodeMode),
                            fact(false));
            PlaybackAutoContext.MediaFacts media =
                    new PlaybackAutoContext.MediaFacts(
                            1,
                            video,
                            audio,
                            decoder,
                            PlaybackAutoContext.OutputFacts.unknown(),
                            PlaybackAutoContext.DisplayFacts.unknown());
            return new PlaybackAutoContext(
                    session,
                    0,
                    1,
                    NOW_MS,
                    fact(kernel),
                    fact(decodeMode),
                    device,
                    resource,
                    path,
                    runtime,
                    media);
        }
    }
}
