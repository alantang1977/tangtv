package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackNetworkIdentityPolicy;

/** Privacy-safe dimensions for persistent EXO rebuffer learning. */
final class ExoRebufferLearningKey {

    private ExoRebufferLearningKey() {
    }

    static Key resolve(
            PlaybackAutoContext context,
            String currentNetworkDigest,
            long nowElapsedMs) {
        if (context == null || !context.active()) return null;
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel = context.kernel();
        if (kernel.isUsable(nowElapsedMs)
                && kernel.value() != PlaybackAutoContext.Kernel.EXO) {
            return null;
        }

        PlaybackAutoContext.Protocol protocol = usableValue(
                context.resource().protocol(), PlaybackAutoContext.Protocol.UNKNOWN, nowElapsedMs);
        PlaybackAutoContext.StreamKind streamKind = usableValue(
                context.resource().streamKind(), PlaybackAutoContext.StreamKind.UNKNOWN, nowElapsedMs);
        PlaybackAutoContext.PathKind pathKind = effectivePath(context.path(), nowElapsedMs);
        String networkDigest = networkDigest(
                context.device().networkSnapshot(), currentNetworkDigest, nowElapsedMs);
        Key key = new Key(networkDigest, pathKind, protocol, streamKind);
        return key.valid() ? key : null;
    }

    private static PlaybackAutoContext.PathKind effectivePath(
            PlaybackAutoContext.PathFacts path,
            long nowElapsedMs) {
        if (path == null) return PlaybackAutoContext.PathKind.UNKNOWN;
        PlaybackAutoContext.PathKind playerPath = usableValue(
                path.playerPath(), PlaybackAutoContext.PathKind.UNKNOWN, nowElapsedMs);
        if (playerPath != PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE) {
            return playerPath;
        }
        PlaybackAutoContext.UpstreamState upstreamState = usableValue(
                path.upstreamState(), PlaybackAutoContext.UpstreamState.UNKNOWN, nowElapsedMs);
        PlaybackAutoContext.PathKind upstreamPath = usableValue(
                path.upstreamPath(), PlaybackAutoContext.PathKind.UNKNOWN, nowElapsedMs);
        return upstreamState == PlaybackAutoContext.UpstreamState.VISIBLE
                && upstreamPath != PlaybackAutoContext.PathKind.UNKNOWN
                ? upstreamPath : PlaybackAutoContext.PathKind.UNKNOWN;
    }

    private static String networkDigest(
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> networkFact,
            String currentNetworkDigest,
            long nowElapsedMs) {
        if (PlaybackNetworkIdentityPolicy.isValidDigest(currentNetworkDigest)) {
            return currentNetworkDigest;
        }
        if (networkFact != null && networkFact.isUsable(nowElapsedMs)) {
            PlaybackAutoContext.NetworkSnapshot snapshot = networkFact.value();
            if (snapshot != null
                    && Boolean.TRUE.equals(snapshot.available())
                    && PlaybackNetworkIdentityPolicy.isValidDigest(snapshot.identityDigest())) {
                return snapshot.identityDigest();
            }
        }
        return "";
    }

    private static <T> T usableValue(
            PlaybackAutoContext.Fact<T> fact,
            T fallback,
            long nowElapsedMs) {
        return fact != null && fact.isUsable(nowElapsedMs) ? fact.value() : fallback;
    }

    record Key(
            String networkDigest,
            PlaybackAutoContext.PathKind pathKind,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind) {

        Key {
            networkDigest = networkDigest == null ? "" : networkDigest;
            pathKind = pathKind == null
                    ? PlaybackAutoContext.PathKind.UNKNOWN : pathKind;
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
        }

        boolean valid() {
            return PlaybackNetworkIdentityPolicy.isValidDigest(networkDigest)
                    && (pathKind == PlaybackAutoContext.PathKind.LAN_PRIVATE
                    || pathKind == PlaybackAutoContext.PathKind.REMOTE)
                    && protocol != PlaybackAutoContext.Protocol.UNKNOWN
                    && protocol != PlaybackAutoContext.Protocol.OTHER
                    && protocol != PlaybackAutoContext.Protocol.LOCAL
                    && streamKind != PlaybackAutoContext.StreamKind.UNKNOWN;
        }
    }
}
