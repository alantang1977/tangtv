package com.fongmi.android.tv.player.ijk;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IjkRuntimeProfileKeyTest {

    @Test
    public void deviceAppMediaAndIjkVersionsChangeEnvironment() {
        IjkRuntimeProfileKey.Environment base = environment(
                "fingerprint-a", 1, "1.0", "media-a", "ijk-a");

        assertNotEquals(base, environment(
                "fingerprint-b", 1, "1.0", "media-a", "ijk-a"));
        assertNotEquals(base, environment(
                "fingerprint-a", 2, "1.0", "media-a", "ijk-a"));
        assertNotEquals(base, environment(
                "fingerprint-a", 1, "1.1", "media-a", "ijk-a"));
        assertNotEquals(base, environment(
                "fingerprint-a", 1, "1.0", "media-b", "ijk-a"));
        assertNotEquals(base, environment(
                "fingerprint-a", 1, "1.0", "media-a", "ijk-b"));
        assertTrue(base.valid());
        assertFalse(base.digest().contains("fingerprint"));
    }

    @Test
    public void exactProtocolFormatColorSecureAndOutputRemainIsolated() {
        IjkRuntimeProfileKey.Environment environment = environment(
                "fp", 1, "1", "media", "ijk");
        IjkRuntimeProfileKey.Evidence base = evidence();
        IjkRuntimeProfileKey.Key key = key(environment, base);

        assertNotEquals(key, key(environment, copy(base,
                PlaybackAutoContext.Protocol.DASH, base.streamKind(),
                base.width(), base.height(), base.frameRateMilli(),
                base.hdrType(), base.colorTransfer(), base.secureState(),
                base.renderTarget())));
        assertNotEquals(key, key(environment, copy(base,
                base.protocol(), PlaybackAutoContext.StreamKind.LIVE,
                base.width(), base.height(), base.frameRateMilli(),
                base.hdrType(), base.colorTransfer(), base.secureState(),
                base.renderTarget())));
        assertNotEquals(key, key(environment, copy(base,
                base.protocol(), base.streamKind(), 1_920, 1_080,
                base.frameRateMilli(), base.hdrType(), base.colorTransfer(),
                base.secureState(), base.renderTarget())));
        assertNotEquals(key, key(environment, copy(base,
                base.protocol(), base.streamKind(), base.width(), base.height(),
                30_000, base.hdrType(), base.colorTransfer(),
                base.secureState(), base.renderTarget())));
        assertNotEquals(key, key(environment, copy(base,
                base.protocol(), base.streamKind(), base.width(), base.height(),
                base.frameRateMilli(), PlaybackAutoContext.HdrType.SDR,
                C.COLOR_TRANSFER_SDR, base.secureState(),
                base.renderTarget())));
        assertNotEquals(key, key(environment, copy(base,
                base.protocol(), base.streamKind(), base.width(), base.height(),
                base.frameRateMilli(), base.hdrType(), base.colorTransfer(),
                1, base.renderTarget())));
        assertNotEquals(key, key(environment, copy(base,
                base.protocol(), base.streamKind(), base.width(), base.height(),
                base.frameRateMilli(), base.hdrType(), base.colorTransfer(),
                base.secureState(), PlaybackAutoContext.RenderTarget.TEXTURE_VIEW)));
    }

    @Test
    public void codecAndFingerprintAreStoredOnlyAsDigests() {
        String codec = "hvc1.2.4.L153.B0";
        IjkRuntimeProfileKey.Key key = key(
                environment("private-fingerprint", 1, "1", "media", "ijk"),
                evidence(codec));

        assertEquals(IjkRuntimeProfileKey.DIGEST_HEX_LENGTH,
                key.environmentDigest().length());
        assertEquals(IjkRuntimeProfileKey.DIGEST_HEX_LENGTH,
                key.codecDigest().length());
        assertFalse(key.canonical().contains("private-fingerprint"));
        assertFalse(key.canonical().contains(codec));
        assertEquals(12, IjkRuntimeProfileKey.shortId(key).length());
    }

    @Test
    public void profileOrLevelCanIdentifyCodecWhenCodecStringIsMissing() {
        IjkRuntimeProfileKey.Evidence base = evidence("");

        assertNotNull(IjkRuntimeProfileKey.from(
                environment("fp", 1, "1", "media", "ijk"), base));
    }

    @Test
    public void incompleteOrUnsafeEvidenceCannotCreateProfile() {
        IjkRuntimeProfileKey.Environment environment = environment(
                "fp", 1, "1", "media", "ijk");
        IjkRuntimeProfileKey.Evidence base = evidence();

        assertNull(IjkRuntimeProfileKey.from(environment, copy(base,
                PlaybackAutoContext.Protocol.UNKNOWN, base.streamKind(),
                base.width(), base.height(), base.frameRateMilli(),
                base.hdrType(), base.colorTransfer(), base.secureState(),
                base.renderTarget())));
        assertNull(IjkRuntimeProfileKey.from(environment, copy(base,
                base.protocol(), PlaybackAutoContext.StreamKind.UNKNOWN,
                base.width(), base.height(), base.frameRateMilli(),
                base.hdrType(), base.colorTransfer(), base.secureState(),
                base.renderTarget())));
        assertNull(IjkRuntimeProfileKey.from(environment,
                new IjkRuntimeProfileKey.Evidence(
                        base.protocol(), base.streamKind(), MimeTypes.AUDIO_AAC,
                        base.codecs(), base.profile(), base.level(), base.width(),
                        base.height(), base.frameRateMilli(), base.hdrType(),
                        base.colorSpace(), base.colorRange(), base.colorTransfer(),
                        base.hdrStaticInfo(), base.secureState(), base.renderTarget())));
        assertNull(IjkRuntimeProfileKey.from(environment, copy(base,
                base.protocol(), base.streamKind(), 0, base.height(),
                base.frameRateMilli(), base.hdrType(), base.colorTransfer(),
                base.secureState(), base.renderTarget())));
        assertNull(IjkRuntimeProfileKey.from(environment, copy(base,
                base.protocol(), base.streamKind(), base.width(), base.height(),
                0, base.hdrType(), base.colorTransfer(), base.secureState(),
                base.renderTarget())));
        assertNull(IjkRuntimeProfileKey.from(environment, copy(base,
                base.protocol(), base.streamKind(), base.width(), base.height(),
                base.frameRateMilli(), base.hdrType(), base.colorTransfer(),
                base.secureState(), PlaybackAutoContext.RenderTarget.UNKNOWN)));
        assertNull(IjkRuntimeProfileKey.from(environment,
                new IjkRuntimeProfileKey.Evidence(
                        base.protocol(), base.streamKind(), "video/hevc|secret",
                        "", 0, 0, base.width(), base.height(),
                        base.frameRateMilli(), base.hdrType(), base.colorSpace(),
                        base.colorRange(), base.colorTransfer(),
                        base.hdrStaticInfo(), base.secureState(),
                        base.renderTarget())));
    }

    private static IjkRuntimeProfileKey.Environment environment(
            String fingerprint,
            int appVersion,
            String appVersionName,
            String media3,
            String ijk) {
        return IjkRuntimeProfileKey.environment(
                fingerprint, appVersion, appVersionName, media3, ijk);
    }

    private static IjkRuntimeProfileKey.Key key(
            IjkRuntimeProfileKey.Environment environment,
            IjkRuntimeProfileKey.Evidence evidence) {
        IjkRuntimeProfileKey.Key key = IjkRuntimeProfileKey.from(
                environment, evidence);
        assertNotNull(key);
        return key;
    }

    private static IjkRuntimeProfileKey.Evidence evidence() {
        return evidence("hvc1.2.4.L153.B0");
    }

    private static IjkRuntimeProfileKey.Evidence evidence(String codec) {
        return new IjkRuntimeProfileKey.Evidence(
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD,
                MimeTypes.VIDEO_H265,
                codec,
                2,
                153,
                3_840,
                2_160,
                60_000,
                PlaybackAutoContext.HdrType.HDR10,
                C.COLOR_SPACE_BT2020,
                C.COLOR_RANGE_LIMITED,
                C.COLOR_TRANSFER_ST2084,
                true,
                0,
                PlaybackAutoContext.RenderTarget.SURFACE_VIEW);
    }

    private static IjkRuntimeProfileKey.Evidence copy(
            IjkRuntimeProfileKey.Evidence source,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            int width,
            int height,
            int frameRateMilli,
            PlaybackAutoContext.HdrType hdrType,
            int colorTransfer,
            int secureState,
            PlaybackAutoContext.RenderTarget renderTarget) {
        return new IjkRuntimeProfileKey.Evidence(
                protocol,
                streamKind,
                source.mimeType(),
                source.codecs(),
                source.profile(),
                source.level(),
                width,
                height,
                frameRateMilli,
                hdrType,
                source.colorSpace(),
                source.colorRange(),
                colorTransfer,
                source.hdrStaticInfo(),
                secureState,
                renderTarget);
    }
}
