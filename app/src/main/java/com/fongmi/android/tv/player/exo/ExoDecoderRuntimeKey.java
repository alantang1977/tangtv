package com.fongmi.android.tv.player.exo;

import android.os.Build;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;

import com.fongmi.android.tv.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Privacy-safe identity for an observed EXO video decoder and format combination. */
final class ExoDecoderRuntimeKey {

    static final int DIGEST_HEX_LENGTH = 24;
    private static final String ENVIRONMENT_NAMESPACE = "webhtv-exo-decoder-environment-v1:";
    private static final String DECODER_NAMESPACE = "webhtv-exo-decoder-name-v1:";
    private static final String CODEC_NAMESPACE = "webhtv-exo-codec-string-v1:";
    private static final String KEY_NAMESPACE = "webhtv-exo-decoder-key-v1:";

    private ExoDecoderRuntimeKey() {
    }

    static Environment currentEnvironment() {
        return environment(
                Build.FINGERPRINT,
                BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_NAME,
                BuildConfig.MEDIA3_VERSION);
    }

    static Environment environment(
            String fingerprint,
            int appVersionCode,
            String appVersionName,
            String media3Version) {
        String canonical = safe(fingerprint) + '\n'
                + Math.max(0, appVersionCode) + '\n'
                + safe(appVersionName) + '\n'
                + safe(media3Version);
        return new Environment(digest(ENVIRONMENT_NAMESPACE, canonical));
    }

    @Nullable
    static Key from(
            Environment environment,
            String decoderName,
            @Nullable Format format,
            boolean secure,
            ExoDecoderRuntimeSession.OutputConfig output) {
        if (environment == null || !environment.valid() || format == null || output == null) return null;
        String decoderDigest = digest(DECODER_NAMESPACE, normalizeDecoder(decoderName));
        String mimeType = normalizeMime(format.sampleMimeType);
        if (!validDigest(decoderDigest) || mimeType.isEmpty() || !MimeTypes.isVideo(mimeType)) return null;
        Pair<Integer, Integer> profileAndLevel = null;
        try {
            profileAndLevel = CodecSpecificDataUtil.getCodecProfileAndLevel(format);
        } catch (Throwable ignored) {
        }
        int profile = profileAndLevel == null ? 0 : positive(profileAndLevel.first);
        int level = profileAndLevel == null ? 0 : positive(profileAndLevel.second);
        int width = positive(format.width);
        int height = positive(format.height);
        int frameRateMilli = positiveFinite(format.frameRate)
                ? Math.min(1_000_000, Math.round(format.frameRate * 1_000f)) : 0;
        String codecDigest = format.codecs == null || format.codecs.isBlank()
                ? "" : digest(CODEC_NAMESPACE, format.codecs.trim().toLowerCase(Locale.US));
        ColorInfo colorInfo = format.colorInfo;
        Key key = new Key(
                environment.digest(),
                decoderDigest,
                mimeType,
                codecDigest,
                profile,
                level,
                width,
                height,
                frameRateMilli,
                colorInfo == null ? 0 : positive(colorInfo.colorSpace),
                colorInfo == null ? 0 : positive(colorInfo.colorRange),
                colorInfo == null ? 0 : positive(colorInfo.colorTransfer),
                colorInfo != null
                        && colorInfo.hdrStaticInfo != null
                        && colorInfo.hdrStaticInfo.length > 0,
                secure,
                output.target(),
                output.tunneling());
        return key.valid() ? key : null;
    }

    static String shortId(Key key) {
        if (key == null || !key.valid()) return "none";
        String value = digest(KEY_NAMESPACE, key.canonical());
        return validDigest(value) ? value.substring(0, 12) : "none";
    }

    static boolean validDigest(String value) {
        if (value == null || value.length() != DIGEST_HEX_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') continue;
            if (character >= 'a' && character <= 'f') continue;
            return false;
        }
        return true;
    }

    private static String normalizeDecoder(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String normalizeMime(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.length() > 96 || normalized.indexOf('|') >= 0 || normalized.indexOf('\n') >= 0) return "";
        return normalized;
    }

    private static int positive(@Nullable Integer value) {
        return value == null ? 0 : positive(value.intValue());
    }

    private static int positive(int value) {
        return Math.max(0, value);
    }

    private static boolean positiveFinite(float value) {
        return value > 0 && Float.isFinite(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String digest(String namespace, String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(DIGEST_HEX_LENGTH);
            for (int index = 0; index < DIGEST_HEX_LENGTH / 2; index++) {
                result.append(String.format(Locale.US, "%02x", bytes[index] & 0xff));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    record Environment(String digest) {

        Environment {
            digest = digest == null ? "" : digest;
        }

        boolean valid() {
            return validDigest(digest);
        }
    }

    record Key(
            String environmentDigest,
            String decoderDigest,
            String mimeType,
            String codecDigest,
            int profile,
            int level,
            int width,
            int height,
            int frameRateMilli,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            boolean hdrStaticInfo,
            boolean secure,
            ExoDecoderRuntimeSession.OutputTarget outputTarget,
            boolean tunneling) {

        Key {
            environmentDigest = environmentDigest == null ? "" : environmentDigest;
            decoderDigest = decoderDigest == null ? "" : decoderDigest;
            mimeType = mimeType == null ? "" : mimeType;
            codecDigest = codecDigest == null ? "" : codecDigest;
            profile = Math.max(0, profile);
            level = Math.max(0, level);
            width = Math.max(0, width);
            height = Math.max(0, height);
            frameRateMilli = Math.max(0, frameRateMilli);
            colorSpace = Math.max(0, colorSpace);
            colorRange = Math.max(0, colorRange);
            colorTransfer = Math.max(0, colorTransfer);
            outputTarget = outputTarget == null
                    ? ExoDecoderRuntimeSession.OutputTarget.UNKNOWN : outputTarget;
        }

        boolean valid() {
            return validDigest(environmentDigest)
                    && validDigest(decoderDigest)
                    && !mimeType.isBlank()
                    && mimeType.indexOf('|') < 0
                    && (codecDigest.isEmpty() || validDigest(codecDigest))
                    && (profile > 0 || level > 0 || !codecDigest.isEmpty())
                    && width > 0
                    && height > 0
                    && outputTarget != ExoDecoderRuntimeSession.OutputTarget.UNKNOWN;
        }

        String canonical() {
            return environmentDigest + '|'
                    + decoderDigest + '|'
                    + mimeType + '|'
                    + codecDigest + '|'
                    + profile + '|'
                    + level + '|'
                    + width + '|'
                    + height + '|'
                    + frameRateMilli + '|'
                    + colorSpace + '|'
                    + colorRange + '|'
                    + colorTransfer + '|'
                    + (hdrStaticInfo ? 1 : 0) + '|'
                    + (secure ? 1 : 0) + '|'
                    + outputTarget.name() + '|'
                    + (tunneling ? 1 : 0);
        }
    }
}
