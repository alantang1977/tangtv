package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;

import java.util.Locale;

/** Pure admission, enrollment and privacy-safe grouping policy for V-05A. */
public final class PlaybackProfileAbPolicy {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final long MIN_SUCCESSFUL_ACTIVE_PLAYBACK_MS = 30_000L;

    private PlaybackProfileAbPolicy() {
    }

    public static EnrollmentResolution resolveEnrollment(
            RawEnrollment rawEnrollment,
            String currentDeviceDigest) {
        RawEnrollment raw = rawEnrollment == null
                ? RawEnrollment.missing() : rawEnrollment;
        if (raw.schemaVersion() == null
                && raw.deviceDigest() == null
                && raw.enabled() == null) {
            return EnrollmentResolution.notEnrolled();
        }
        Integer version = integer(raw.schemaVersion());
        if (version == null || version < 0) {
            return EnrollmentResolution.invalid(
                    EnrollmentStatus.CORRUPT);
        }
        if (version > CURRENT_SCHEMA_VERSION) {
            return EnrollmentResolution.invalid(
                    EnrollmentStatus.FUTURE_SCHEMA);
        }
        if (version != CURRENT_SCHEMA_VERSION
                || !(raw.deviceDigest() instanceof String deviceDigest)
                || !(raw.enabled() instanceof Boolean enabled)
                || !PlaybackProfileAbIdentity.validDigest(deviceDigest)) {
            return EnrollmentResolution.invalid(
                    EnrollmentStatus.CORRUPT);
        }
        if (!PlaybackProfileAbIdentity.validDigest(currentDeviceDigest)
                || !deviceDigest.equals(currentDeviceDigest)) {
            return new EnrollmentResolution(
                    Enrollment.none(),
                    EnrollmentStatus.STALE_DEVICE,
                    false);
        }
        return new EnrollmentResolution(
                new Enrollment(deviceDigest, enabled),
                EnrollmentStatus.CURRENT,
                true);
    }

    public static Arm armForProfile(int profile) {
        if (profile == PlaybackPerformanceSetting.PROFILE_AUTO) {
            return Arm.AUTO;
        }
        if (profile == PlaybackPerformanceSetting.PROFILE_RECOMMENDED) {
            return Arm.RECOMMENDED;
        }
        return null;
    }

    public static PlaybackExperimentPolicy.Domain domainForKernel(
            PlaybackAutoContext.Kernel kernel) {
        if (kernel == null) return null;
        return switch (kernel) {
            case EXO -> PlaybackExperimentPolicy.Domain.EXO;
            case MPV -> PlaybackExperimentPolicy.Domain.MPV;
            case IJK -> PlaybackExperimentPolicy.Domain.IJK;
            case UNKNOWN -> null;
        };
    }

    public static boolean gateAllows(
            PlaybackExperimentPolicy.State state,
            PlaybackAutoContext.Kernel kernel) {
        PlaybackExperimentPolicy.State safe = state == null
                ? PlaybackExperimentPolicy.State.stable() : state;
        PlaybackExperimentPolicy.Domain domain = domainForKernel(kernel);
        return domain != null
                && safe.allows(PlaybackExperimentPolicy.Action
                .SHARED_PROFILE_AB_VALIDATION)
                && safe.domainEnabled(domain);
    }

    public static GroupResolution resolveGroup(
            PlaybackAutoContext context,
            String deviceDigest,
            long elapsedRealtimeMs) {
        if (context == null || !context.active()) {
            return GroupResolution.invalid(GroupReason.NO_SESSION);
        }
        if (!PlaybackProfileAbIdentity.validDigest(deviceDigest)) {
            return GroupResolution.invalid(GroupReason.INVALID_DEVICE);
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernelFact =
                context.kernel();
        if (!usable(kernelFact, elapsedRealtimeMs)
                || kernelFact.value() == PlaybackAutoContext.Kernel.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.KERNEL_UNKNOWN);
        }
        PlaybackAutoContext.MediaFacts media = context.media();
        PlaybackAutoContext.DecoderFacts decoder = media.decoder();
        if (decoder.trackSequence() != media.trackSequence()) {
            return GroupResolution.invalid(GroupReason.DECODER_STALE);
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode>
                decodeModeFact = decoder.videoDecodeMode();
        if (!usable(decodeModeFact, elapsedRealtimeMs)
                || decodeModeFact.value()
                == PlaybackAutoContext.DecodeMode.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.DECODE_MODE_UNKNOWN);
        }
        PlaybackAutoContext.Fact<String> decoderNameFact =
                decoder.videoDecoderName();
        if (!usable(decoderNameFact, elapsedRealtimeMs)) {
            return GroupResolution.invalid(GroupReason.DECODER_UNKNOWN);
        }
        String decoderName = decoderNameFact.value() == null
                ? "" : decoderNameFact.value().trim();
        if (decoderName.isEmpty()
                || "unknown".equalsIgnoreCase(decoderName)) {
            return GroupResolution.invalid(GroupReason.DECODER_UNKNOWN);
        }
        String decoderDigest =
                PlaybackProfileAbIdentity.decoderDigest(decoderName);
        if (!PlaybackProfileAbIdentity.validDigest(decoderDigest)) {
            return GroupResolution.invalid(GroupReason.DECODER_UNKNOWN);
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                context.resource().protocol();
        if (!usable(protocolFact, elapsedRealtimeMs)
                || protocolFact.value()
                == PlaybackAutoContext.Protocol.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.PROTOCOL_UNKNOWN);
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> streamFact =
                context.resource().streamKind();
        if (!usable(streamFact, elapsedRealtimeMs)
                || streamFact.value()
                == PlaybackAutoContext.StreamKind.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.STREAM_KIND_UNKNOWN);
        }
        PlaybackAutoContext.TrackFacts video = media.videoTrack();
        PlaybackAutoContext.Fact<String> mimeFact = video.mimeType();
        if (!usable(mimeFact, elapsedRealtimeMs)) {
            return GroupResolution.invalid(GroupReason.VIDEO_MIME_UNKNOWN);
        }
        VideoMimeClass videoMime = VideoMimeClass.fromMime(mimeFact.value());
        if (videoMime == VideoMimeClass.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.VIDEO_MIME_UNKNOWN);
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.HdrType> hdrFact =
                video.hdrType();
        if (!usable(hdrFact, elapsedRealtimeMs)
                || hdrFact.value() == PlaybackAutoContext.HdrType.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.HDR_UNKNOWN);
        }
        PlaybackAutoContext.PathKind pathKind =
                effectivePath(context.path(), elapsedRealtimeMs);
        if (pathKind == PlaybackAutoContext.PathKind.UNKNOWN) {
            return GroupResolution.invalid(GroupReason.PATH_UNKNOWN);
        }
        GroupKey key = new GroupKey(
                deviceDigest,
                kernelFact.value(),
                decodeModeFact.value(),
                decoderDigest,
                protocolFact.value(),
                streamFact.value(),
                videoMime,
                hdrFact.value(),
                pathKind);
        return key.valid()
                ? new GroupResolution(key, GroupReason.READY, true)
                : GroupResolution.invalid(GroupReason.INVALID_GROUP);
    }

    private static PlaybackAutoContext.PathKind effectivePath(
            PlaybackAutoContext.PathFacts path,
            long elapsedRealtimeMs) {
        if (path == null) return PlaybackAutoContext.PathKind.UNKNOWN;
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstream =
                path.upstreamPath();
        if (usable(upstream, elapsedRealtimeMs)
                && upstream.value()
                != PlaybackAutoContext.PathKind.UNKNOWN) {
            return upstream.value();
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> player =
                path.playerPath();
        if (usable(player, elapsedRealtimeMs)
                && player.value()
                != PlaybackAutoContext.PathKind.UNKNOWN) {
            return player.value();
        }
        return PlaybackAutoContext.PathKind.UNKNOWN;
    }

    private static boolean usable(
            PlaybackAutoContext.Fact<?> fact,
            long elapsedRealtimeMs) {
        return fact != null && fact.isUsable(elapsedRealtimeMs);
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) return null;
        long result = ((Number) value).longValue();
        return result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                ? null : (int) result;
    }

    public enum Arm {
        AUTO("auto"),
        RECOMMENDED("recommended"),
        LIGHTWEIGHT("lightweight");

        private final String label;

        Arm(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum EnrollmentStatus {
        NOT_ENROLLED,
        CURRENT,
        CORRUPT,
        FUTURE_SCHEMA,
        STALE_DEVICE
    }

    public enum GroupReason {
        READY,
        NO_SESSION,
        INVALID_DEVICE,
        KERNEL_UNKNOWN,
        DECODE_MODE_UNKNOWN,
        DECODER_UNKNOWN,
        DECODER_STALE,
        PROTOCOL_UNKNOWN,
        STREAM_KIND_UNKNOWN,
        VIDEO_MIME_UNKNOWN,
        HDR_UNKNOWN,
        PATH_UNKNOWN,
        MEMORY_CLASS_UNKNOWN,
        NOT_LIGHTWEIGHT_SCENARIO,
        INVALID_GROUP
    }

    public enum VideoMimeClass {
        AVC("avc"),
        HEVC("hevc"),
        VP9("vp9"),
        AV1("av1"),
        DOLBY_VISION("dolby-vision"),
        OTHER_VIDEO("other-video"),
        UNKNOWN("unknown");

        private final String label;

        VideoMimeClass(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static VideoMimeClass fromMime(String mimeType) {
            String mime = mimeType == null
                    ? "" : mimeType.trim().toLowerCase(Locale.US);
            if (!mime.startsWith("video/")) return UNKNOWN;
            if (mime.contains("dolby-vision")) return DOLBY_VISION;
            if (mime.contains("avc") || mime.contains("h264")) return AVC;
            if (mime.contains("hevc") || mime.contains("h265")) return HEVC;
            if (mime.contains("vp9") || mime.contains("x-vnd.on2.vp9")) {
                return VP9;
            }
            if (mime.contains("av01") || mime.contains("av1")) return AV1;
            return OTHER_VIDEO;
        }
    }

    public record RawEnrollment(
            Object schemaVersion,
            Object deviceDigest,
            Object enabled) {

        public static RawEnrollment missing() {
            return new RawEnrollment(null, null, null);
        }
    }

    public record Enrollment(String deviceDigest, boolean enabled) {

        public Enrollment {
            deviceDigest = PlaybackProfileAbIdentity.validDigest(deviceDigest)
                    ? deviceDigest : "";
        }

        public static Enrollment none() {
            return new Enrollment("", false);
        }

        public boolean active() {
            return enabled
                    && PlaybackProfileAbIdentity.validDigest(deviceDigest);
        }
    }

    public record EnrollmentResolution(
            Enrollment enrollment,
            EnrollmentStatus status,
            boolean sourceValid) {

        public EnrollmentResolution {
            enrollment = enrollment == null
                    ? Enrollment.none() : enrollment;
            status = status == null
                    ? EnrollmentStatus.CORRUPT : status;
        }

        public static EnrollmentResolution notEnrolled() {
            return new EnrollmentResolution(
                    Enrollment.none(),
                    EnrollmentStatus.NOT_ENROLLED,
                    true);
        }

        private static EnrollmentResolution invalid(
                EnrollmentStatus status) {
            return new EnrollmentResolution(
                    Enrollment.none(), status, false);
        }

        public boolean active() {
            return sourceValid
                    && status == EnrollmentStatus.CURRENT
                    && enrollment.active();
        }

        public boolean failClosed() {
            return !sourceValid
                    || status == EnrollmentStatus.FUTURE_SCHEMA;
        }
    }

    public record GroupKey(
            String deviceDigest,
            PlaybackAutoContext.Kernel kernel,
            PlaybackAutoContext.DecodeMode decodeMode,
            String decoderDigest,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            VideoMimeClass videoMime,
            PlaybackAutoContext.HdrType hdrType,
            PlaybackAutoContext.PathKind pathKind) {

        public GroupKey {
            deviceDigest = PlaybackProfileAbIdentity.validDigest(deviceDigest)
                    ? deviceDigest : "";
            kernel = kernel == null
                    ? PlaybackAutoContext.Kernel.UNKNOWN : kernel;
            decodeMode = decodeMode == null
                    ? PlaybackAutoContext.DecodeMode.UNKNOWN : decodeMode;
            decoderDigest = PlaybackProfileAbIdentity.validDigest(
                    decoderDigest) ? decoderDigest : "";
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            videoMime = videoMime == null
                    ? VideoMimeClass.UNKNOWN : videoMime;
            hdrType = hdrType == null
                    ? PlaybackAutoContext.HdrType.UNKNOWN : hdrType;
            pathKind = pathKind == null
                    ? PlaybackAutoContext.PathKind.UNKNOWN : pathKind;
        }

        public boolean valid() {
            return PlaybackProfileAbIdentity.validDigest(deviceDigest)
                    && kernel != PlaybackAutoContext.Kernel.UNKNOWN
                    && decodeMode != PlaybackAutoContext.DecodeMode.UNKNOWN
                    && PlaybackProfileAbIdentity.validDigest(decoderDigest)
                    && protocol != PlaybackAutoContext.Protocol.UNKNOWN
                    && streamKind != PlaybackAutoContext.StreamKind.UNKNOWN
                    && videoMime != VideoMimeClass.UNKNOWN
                    && hdrType != PlaybackAutoContext.HdrType.UNKNOWN
                    && pathKind != PlaybackAutoContext.PathKind.UNKNOWN;
        }

        public boolean live() {
            return streamKind == PlaybackAutoContext.StreamKind.LIVE
                    || streamKind
                    == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
        }

        String canonical() {
            return deviceDigest + '|'
                    + kernel.name() + '|'
                    + decodeMode.name() + '|'
                    + decoderDigest + '|'
                    + protocol.name() + '|'
                    + streamKind.name() + '|'
                    + videoMime.name() + '|'
                    + hdrType.name() + '|'
                    + pathKind.name();
        }
    }

    public record GroupResolution(
            GroupKey key,
            GroupReason reason,
            boolean ready) {

        public GroupResolution {
            reason = reason == null ? GroupReason.INVALID_GROUP : reason;
            if (!ready || key == null || !key.valid()) {
                key = null;
                ready = false;
            }
        }

        static GroupResolution invalid(GroupReason reason) {
            return new GroupResolution(null, reason, false);
        }
    }
}
