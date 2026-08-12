package com.fongmi.android.tv.player.exo;

/** Ensures network protection never changes an output feature selected by the user. */
public final class ExoNetworkGuardEligibility {

    private ExoNetworkGuardEligibility() {
    }

    public static Decision resolve(Request request) {
        if (request == null || !request.enabled()) return Decision.blocked("disabled");
        if (!request.exo()) return Decision.blocked("exo-only");
        if (!request.vod()) return Decision.blocked("vod-only");
        if (!request.userUnitSpeed()) return Decision.blocked("user-speed");
        if (!request.speedCommandAvailable()) return Decision.blocked("speed-unsupported");
        if (request.tunnelingRequested()) return Decision.blocked("preserve-tunneling");
        if (request.audioPassthroughRequested()) return Decision.blocked("preserve-passthrough");
        return new Decision(true, "eligible");
    }

    public record Request(boolean enabled, boolean exo, boolean vod, boolean userUnitSpeed, boolean speedCommandAvailable, boolean tunnelingRequested, boolean audioPassthroughRequested) {
    }

    public record Decision(boolean eligible, String reason) {

        private static Decision blocked(String reason) {
            return new Decision(false, reason);
        }
    }
}
