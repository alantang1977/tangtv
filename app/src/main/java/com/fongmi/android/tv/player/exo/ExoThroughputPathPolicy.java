package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

/** Conservative mapping from F-02/F-04 facts to throughput control eligibility. */
final class ExoThroughputPathPolicy {

    private ExoThroughputPathPolicy() {
    }

    static Decision resolve(PlaybackAutoContext context, long nowElapsedMs, boolean preloadContended) {
        PlaybackAutoContext safe = context == null ? PlaybackAutoContext.empty() : context;
        PlaybackAutoContext.PathFacts path = safe.path();
        long now = Math.max(0, nowElapsedMs);

        Decision base = resolvePath(path, now);
        if (base.trust() == Trust.BLOCKED) return base;

        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> network =
                safe.device().networkSnapshot();
        if (network.isUsable(now)
                && network.value().transport() == PlaybackAutoContext.NetworkTransport.VPN) {
            base = new Decision(Trust.LIMITED, PlaybackAutoContext.Confidence.LOW, Reason.VPN);
        }
        if (preloadContended) {
            return new Decision(Trust.LIMITED, PlaybackAutoContext.Confidence.LOW,
                    Reason.PRELOAD_CONTENTION);
        }
        return base;
    }

    private static Decision resolvePath(PlaybackAutoContext.PathFacts path, long now) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> upstreamState = path.upstreamState();
        if (usable(upstreamState, now)
                && upstreamState.value() == PlaybackAutoContext.UpstreamState.OPAQUE) {
            return new Decision(Trust.BLOCKED, PlaybackAutoContext.Confidence.LOW,
                    Reason.OPAQUE_UPSTREAM);
        }

        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPath = path.playerPath();
        PlaybackAutoContext.Fact<Boolean> loopback = path.loopback();
        if ((usable(playerPath, now)
                && playerPath.value() == PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK)
                || (usable(loopback, now) && loopback.value()
                && isExternalLoopback(path, now))) {
            return new Decision(Trust.BLOCKED, PlaybackAutoContext.Confidence.LOW,
                    Reason.EXTERNAL_LOOPBACK);
        }

        if (usable(playerPath, now)) {
            return switch (playerPath.value()) {
                case LOCAL -> new Decision(Trust.BLOCKED, PlaybackAutoContext.Confidence.LOW,
                        Reason.LOCAL_SOURCE);
                case REMOTE -> directRemote(path, now, playerPath.confidence());
                case LAN_PRIVATE -> new Decision(Trust.TRUSTED,
                        capAtMedium(playerPath.confidence()), Reason.DIRECT_LAN);
                case APP_INTERNAL_SERVICE -> appService(path, now, playerPath.confidence());
                case EXTERNAL_LOOPBACK -> new Decision(Trust.BLOCKED,
                        PlaybackAutoContext.Confidence.LOW, Reason.EXTERNAL_LOOPBACK);
                case UNKNOWN -> fallbackRoute(path, now);
            };
        }
        return fallbackRoute(path, now);
    }

    private static Decision directRemote(
            PlaybackAutoContext.PathFacts path,
            long now,
            PlaybackAutoContext.Confidence pathConfidence) {
        PlaybackAutoContext.Fact<PlaybackRouteCapabilities.ObservedLeg> leg = path.observedLeg();
        PlaybackAutoContext.Fact<PlaybackRoute> route = path.route();
        boolean directLeg = usable(leg, now)
                && leg.value() == PlaybackRouteCapabilities.ObservedLeg.PLAYER_TO_REMOTE_HTTP;
        boolean directRoute = usable(route, now)
                && route.value() == PlaybackRoute.DIRECT_REMOTE_HTTP;
        PlaybackAutoContext.Confidence confidence = lower(pathConfidence,
                directLeg ? leg.confidence() : directRoute ? route.confidence() : pathConfidence);
        return new Decision(Trust.TRUSTED, confidence, Reason.DIRECT_REMOTE);
    }

    private static Decision appService(
            PlaybackAutoContext.PathFacts path,
            long now,
            PlaybackAutoContext.Confidence pathConfidence) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> state = path.upstreamState();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstream = path.upstreamPath();
        PlaybackAutoContext.Fact<PlaybackRouteCapabilities.UpstreamVisibility> visibility =
                path.upstreamVisibility();
        PlaybackAutoContext.Fact<PlaybackRouteCapabilities.ObservedLeg> leg =
                path.observedLeg();
        boolean visible = usable(state, now)
                && state.value() == PlaybackAutoContext.UpstreamState.VISIBLE;
        boolean ownedPath = usable(visibility, now)
                && visibility.value() == PlaybackRouteCapabilities.UpstreamVisibility.APP_SERVICE_PATH;
        boolean measurableUpstream = usable(upstream, now)
                && (upstream.value() == PlaybackAutoContext.PathKind.REMOTE
                || upstream.value() == PlaybackAutoContext.PathKind.LAN_PRIVATE);
        if (!visible || !ownedPath || !measurableUpstream) {
            return new Decision(Trust.LIMITED, PlaybackAutoContext.Confidence.LOW,
                    Reason.APP_SERVICE_UPSTREAM_UNKNOWN);
        }
        boolean upstreamMeasured = usable(leg, now)
                && leg.value()
                == PlaybackRouteCapabilities.ObservedLeg.PLAYER_TO_REMOTE_HTTP;
        if (!upstreamMeasured) {
            return new Decision(Trust.LIMITED, PlaybackAutoContext.Confidence.LOW,
                    Reason.APP_SERVICE_LOCAL_LEG);
        }
        PlaybackAutoContext.Confidence confidence = lower(pathConfidence,
                lower(
                        state.confidence(),
                        lower(
                                upstream.confidence(),
                                lower(visibility.confidence(), leg.confidence()))));
        return new Decision(Trust.TRUSTED, capAtMedium(confidence), Reason.APP_SERVICE_VISIBLE);
    }

    private static Decision fallbackRoute(PlaybackAutoContext.PathFacts path, long now) {
        PlaybackAutoContext.Fact<PlaybackRoute> route = path.route();
        PlaybackAutoContext.Fact<PlaybackRouteCapabilities.ObservedLeg> leg = path.observedLeg();
        if (usable(route, now) && route.value() == PlaybackRoute.DIRECT_REMOTE_HTTP
                && usable(leg, now)
                && leg.value() == PlaybackRouteCapabilities.ObservedLeg.PLAYER_TO_REMOTE_HTTP) {
            return new Decision(Trust.TRUSTED,
                    lower(route.confidence(), leg.confidence()), Reason.DIRECT_REMOTE);
        }
        if (usable(route, now) && route.value() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY) {
            return new Decision(Trust.BLOCKED, PlaybackAutoContext.Confidence.LOW,
                    Reason.EXTERNAL_LOOPBACK);
        }
        return new Decision(Trust.LIMITED, PlaybackAutoContext.Confidence.LOW,
                Reason.PATH_UNKNOWN);
    }

    private static boolean isExternalLoopback(PlaybackAutoContext.PathFacts path, long now) {
        PlaybackAutoContext.Fact<PlaybackRoute> route = path.route();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPath = path.playerPath();
        return (usable(route, now)
                && route.value() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY)
                || (usable(playerPath, now)
                && playerPath.value() == PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK);
    }

    private static boolean usable(PlaybackAutoContext.Fact<?> fact, long now) {
        return fact != null && fact.isUsable(now);
    }

    private static PlaybackAutoContext.Confidence capAtMedium(
            PlaybackAutoContext.Confidence confidence) {
        if (confidence == PlaybackAutoContext.Confidence.HIGH) {
            return PlaybackAutoContext.Confidence.MEDIUM;
        }
        return confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
    }

    private static PlaybackAutoContext.Confidence lower(
            PlaybackAutoContext.Confidence first,
            PlaybackAutoContext.Confidence second) {
        PlaybackAutoContext.Confidence safeFirst = first == null
                ? PlaybackAutoContext.Confidence.UNKNOWN : first;
        PlaybackAutoContext.Confidence safeSecond = second == null
                ? PlaybackAutoContext.Confidence.UNKNOWN : second;
        return rank(safeFirst) <= rank(safeSecond) ? safeFirst : safeSecond;
    }

    private static int rank(PlaybackAutoContext.Confidence confidence) {
        return switch (confidence) {
            case UNKNOWN -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    enum Trust {
        TRUSTED("trusted"),
        LIMITED("limited"),
        BLOCKED("blocked");

        private final String label;

        Trust(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Reason {
        DIRECT_REMOTE("direct-remote"),
        DIRECT_LAN("direct-lan"),
        APP_SERVICE_VISIBLE("app-service-visible"),
        APP_SERVICE_UPSTREAM_UNKNOWN("app-service-upstream-unknown"),
        APP_SERVICE_LOCAL_LEG("app-service-local-leg"),
        EXTERNAL_LOOPBACK("external-loopback"),
        OPAQUE_UPSTREAM("opaque-upstream"),
        LOCAL_SOURCE("local-source"),
        VPN("vpn"),
        PRELOAD_CONTENTION("preload-contention"),
        PATH_UNKNOWN("path-unknown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Decision(
            Trust trust,
            PlaybackAutoContext.Confidence confidence,
            Reason reason) {

        Decision {
            trust = trust == null ? Trust.LIMITED : trust;
            confidence = confidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            reason = reason == null ? Reason.PATH_UNKNOWN : reason;
        }

        boolean downgradeEligible() {
            return trust != Trust.BLOCKED;
        }

        boolean upgradeEligible() {
            return trust == Trust.TRUSTED
                    && (confidence == PlaybackAutoContext.Confidence.MEDIUM
                    || confidence == PlaybackAutoContext.Confidence.HIGH);
        }
    }
}
