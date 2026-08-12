package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExoDecoderRuntimeKeyTest {

    private static final ExoDecoderRuntimeSession.OutputConfig SURFACE =
            new ExoDecoderRuntimeSession.OutputConfig(
                    ExoDecoderRuntimeSession.OutputTarget.SURFACE, false);

    @Test
    public void appMediaAndSystemVersionsChangeEnvironment() {
        ExoDecoderRuntimeKey.Environment base = environment("fingerprint-a", 1, "1.0", "media-a");

        assertNotEquals(base, environment("fingerprint-b", 1, "1.0", "media-a"));
        assertNotEquals(base, environment("fingerprint-a", 2, "1.0", "media-a"));
        assertNotEquals(base, environment("fingerprint-a", 1, "1.1", "media-a"));
        assertNotEquals(base, environment("fingerprint-a", 1, "1.0", "media-b"));
        assertTrue(base.valid());
        assertFalse(base.digest().contains("fingerprint"));
    }

    @Test
    public void exactRuntimeDimensionsRemainIsolated() {
        ExoDecoderRuntimeKey.Environment environment = environment("fp", 1, "1", "m");
        Format base = format("hvc1.2.4.L153.B0", 3840, 2160, 60f);
        ExoDecoderRuntimeKey.Key key = key(environment, "c2.vendor.hevc.decoder", base, false, SURFACE);

        assertNotEquals(key, key(environment, "c2.vendor.other.decoder", base, false, SURFACE));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", format("hvc1.2.4.L153.B0", 1920, 1080, 60f), false, SURFACE));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", format("hvc1.2.4.L153.B0", 3840, 2160, 30f), false, SURFACE));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", format("hvc1.1.6.L120.B0", 3840, 2160, 60f), false, SURFACE));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", hdrFormat(C.COLOR_TRANSFER_ST2084), false, SURFACE));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", base, true, SURFACE));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", base, false,
                new ExoDecoderRuntimeSession.OutputConfig(ExoDecoderRuntimeSession.OutputTarget.TEXTURE, false)));
        assertNotEquals(key, key(environment, "c2.vendor.hevc.decoder", base, false,
                new ExoDecoderRuntimeSession.OutputConfig(ExoDecoderRuntimeSession.OutputTarget.SURFACE, true)));
    }

    @Test
    public void decoderAndCodecStringsArePersistedAsDigests() {
        String decoder = "c2.vendor.hevc.decoder";
        String codecs = "hvc1.2.4.L153.B0";
        ExoDecoderRuntimeKey.Key key = key(
                environment("fp", 1, "1", "m"),
                decoder,
                format(codecs, 3840, 2160, 60f),
                false,
                SURFACE);

        assertEquals(ExoDecoderRuntimeKey.DIGEST_HEX_LENGTH, key.decoderDigest().length());
        assertEquals(ExoDecoderRuntimeKey.DIGEST_HEX_LENGTH, key.codecDigest().length());
        assertFalse(key.canonical().contains(decoder));
        assertFalse(key.canonical().contains(codecs));
        assertEquals(12, ExoDecoderRuntimeKey.shortId(key).length());
    }

    @Test
    public void incompleteDecoderOrFormatCannotCreateProfile() {
        ExoDecoderRuntimeKey.Environment environment = environment("fp", 1, "1", "m");
        Format audio = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
        Format unknownSize = new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs("hvc1.2.4.L153.B0")
                .build();
        Format unknownCodec = new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .build();

        assertNull(ExoDecoderRuntimeKey.from(environment, "", format(null, 1920, 1080, 30), false, SURFACE));
        assertNull(ExoDecoderRuntimeKey.from(environment, "decoder", audio, false, SURFACE));
        assertNull(ExoDecoderRuntimeKey.from(environment, "decoder", unknownSize, false, SURFACE));
        assertNull(ExoDecoderRuntimeKey.from(environment, "decoder", unknownCodec, false, SURFACE));
        assertNull(ExoDecoderRuntimeKey.from(environment, "decoder", null, false, SURFACE));
    }

    private static ExoDecoderRuntimeKey.Environment environment(
            String fingerprint,
            int appVersion,
            String versionName,
            String media3) {
        return ExoDecoderRuntimeKey.environment(fingerprint, appVersion, versionName, media3);
    }

    private static ExoDecoderRuntimeKey.Key key(
            ExoDecoderRuntimeKey.Environment environment,
            String decoder,
            Format format,
            boolean secure,
            ExoDecoderRuntimeSession.OutputConfig output) {
        ExoDecoderRuntimeKey.Key key = ExoDecoderRuntimeKey.from(
                environment, decoder, format, secure, output);
        assertNotNull(key);
        return key;
    }

    private static Format format(String codecs, int width, int height, float frameRate) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(codecs)
                .setWidth(width)
                .setHeight(height)
                .setFrameRate(frameRate)
                .build();
    }

    private static Format hdrFormat(int colorTransfer) {
        return format("hvc1.2.4.L153.B0", 3840, 2160, 60f)
                .buildUpon()
                .setColorInfo(new ColorInfo.Builder()
                        .setColorSpace(C.COLOR_SPACE_BT2020)
                        .setColorRange(C.COLOR_RANGE_LIMITED)
                        .setColorTransfer(colorTransfer)
                        .build())
                .build();
    }
}
