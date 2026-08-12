package com.fongmi.android.tv.player.ijk;

import android.os.Build;

import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Privacy-safe identity for an IJK device, format and output combination. */
final class IjkRuntimeProfileKey {

    static final String IJK_SOURCE_REVISION =
            "be89479c77c52acfb023d3b3acefccc5d8b9a101";
    static final int DIGEST_HEX_LENGTH = 24;
    private static final String ENVIRONMENT_NAMESPACE =
            "webhtv-ijk-runtime-environment-v1:";
    private static final String CODEC_NAMESPACE =
            "webhtv-ijk-runtime-codec-v1:";
    private static final String KEY_NAMESPACE =
            "webhtv-ijk-runtime-key-v1:";

    private IjkRuntimeProfileKey() {
    }

    static Environment currentEnvironment() {
        return environment(
                Build.FINGERPRINT,
                BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_NAME,
                BuildConfig.MEDIA3_VERSION,
                IJK_SOURCE_REVISION);
    }

    static Environment environment(
            String fingerprint,
            int appVersionCode,
            String appVersionName,
            String media3Version,
            String ijkRevision) {
        String canonical = safe(fingerprint) + '\n'
                + Math.max(0, appVersionCode) + '\n'
                + safe(appVersionName) + '\n'
                + safe(media3Version) + '\n'
                + safe(ijkRevision);
        return new Environment(digest(ENVIRONMENT_NAMESPACE, canonical));
    }

    static Key from(Environment environment, Evidence evidence) {
        if (environment == null || !environment.valid() || evidence == null) {
            return null;
        }
        String mimeType = normalizeMime(evidence.mimeType());
        String codecDigest = evidence.codecs() == null
                || evidence.codecs().isBlank()
                ? "" : digest(
                CODEC_NAMESPACE,
                evidence.codecs().trim().toLowerCase(Locale.US));
        Key key = new Key(
                environment.digest(),
                evidence.protocol(),
                evidence.streamKind(),
                mimeType,
                codecDigest,
                evidence.profile(),
                evidence.level(),
                evidence.width(),
                evidence.height(),
                evidence.frameRateMilli(),
                evidence.hdrType(),
                evidence.colorSpace(),
                evidence.colorRange(),
                evidence.colorTransfer(),
                evidence.hdrStaticInfo(),
                evidence.secureState(),
                evidence.renderTarget());
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

    private static String normalizeMime(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.length() > 96
                || normalized.indexOf('|') >= 0
                || normalized.indexOf('\n') >= 0) return "";
        return normalized;
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
                result.append(String.format(
                        Locale.US, "%02x", bytes[index] & 0xff));
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

    record Evidence(
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            String mimeType,
            String codecs,
            int profile,
            int level,
            int width,
            int height,
            int frameRateMilli,
            PlaybackAutoContext.HdrType hdrType,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            boolean hdrStaticInfo,
            int secureState,
            PlaybackAutoContext.RenderTarget renderTarget) {

        Evidence {
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            mimeType = mimeType == null ? "" : mimeType;
            codecs = codecs == null ? "" : codecs;
            profile = Math.max(0, profile);
            level = Math.max(0, level);
            width = Math.max(0, width);
            height = Math.max(0, height);
            frameRateMilli = Math.max(0, frameRateMilli);
            hdrType = hdrType == null
                    ? PlaybackAutoContext.HdrType.UNKNOWN : hdrType;
            secureState = Math.max(-1, Math.min(1, secureState));
            renderTarget = renderTarget == null
                    ? PlaybackAutoContext.RenderTarget.UNKNOWN : renderTarget;
        }
    }

    record Key(
            String environmentDigest,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            String mimeType,
            String codecDigest,
            int profile,
            int level,
            int width,
            int height,
            int frameRateMilli,
            PlaybackAutoContext.HdrType hdrType,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            boolean hdrStaticInfo,
            int secureState,
            PlaybackAutoContext.RenderTarget renderTarget) {

        Key {
            environmentDigest = environmentDigest == null ? "" : environmentDigest;
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            mimeType = mimeType == null ? "" : mimeType;
            codecDigest = codecDigest == null ? "" : codecDigest;
            profile = Math.max(0, profile);
            level = Math.max(0, level);
            width = Math.max(0, width);
            height = Math.max(0, height);
            frameRateMilli = Math.max(0, frameRateMilli);
            hdrType = hdrType == null
                    ? PlaybackAutoContext.HdrType.UNKNOWN : hdrType;
            secureState = Math.max(-1, Math.min(1, secureState));
            renderTarget = renderTarget == null
                    ? PlaybackAutoContext.RenderTarget.UNKNOWN : renderTarget;
        }

        boolean valid() {
            return validDigest(environmentDigest)
                    && protocol != PlaybackAutoContext.Protocol.UNKNOWN
                    && streamKind != PlaybackAutoContext.StreamKind.UNKNOWN
                    && !mimeType.isBlank()
                    && MimeTypes.isVideo(mimeType)
                    && (codecDigest.isEmpty() || validDigest(codecDigest))
                    && (!codecDigest.isEmpty() || profile > 0 || level > 0)
                    && width > 0
                    && height > 0
                    && frameRateMilli > 0
                    && renderTarget != PlaybackAutoContext.RenderTarget.UNKNOWN;
        }

        String canonical() {
            return environmentDigest + '|'
                    + protocol.name() + '|'
                    + streamKind.name() + '|'
                    + mimeType + '|'
                    + codecDigest + '|'
                    + profile + '|'
                    + level + '|'
                    + width + '|'
                    + height + '|'
                    + frameRateMilli + '|'
                    + hdrType.name() + '|'
                    + colorSpace + '|'
                    + colorRange + '|'
                    + colorTransfer + '|'
                    + (hdrStaticInfo ? 1 : 0) + '|'
                    + secureState + '|'
                    + renderTarget.name();
        }
    }
}
