package com.fongmi.android.tv.player;

import java.util.Locale;
import java.util.Objects;

public record PlaybackAutoContext(
        SessionToken session,
        long startedAtElapsedMs,
        long revision,
        long publishedAtElapsedMs,
        Fact<Kernel> kernel,
        Fact<DecodeMode> decodeMode,
        DeviceFacts device,
        ResourceFacts resource,
        PathFacts path,
        RuntimeFacts runtime,
        MediaFacts media) {

    public PlaybackAutoContext(
            SessionToken session,
            long startedAtElapsedMs,
            long revision,
            long publishedAtElapsedMs,
            Fact<Kernel> kernel,
            Fact<DecodeMode> decodeMode,
            DeviceFacts device,
            ResourceFacts resource,
            PathFacts path,
            RuntimeFacts runtime) {
        this(session, startedAtElapsedMs, revision, publishedAtElapsedMs, kernel, decodeMode,
                device, resource, path, runtime, MediaFacts.unknown());
    }

    public PlaybackAutoContext {
        session = session == null ? SessionToken.none() : session;
        kernel = kernel == null ? Fact.unknown(Kernel.UNKNOWN) : kernel;
        decodeMode = decodeMode == null ? Fact.unknown(DecodeMode.UNKNOWN) : decodeMode;
        device = device == null ? DeviceFacts.unknown() : device;
        resource = resource == null ? ResourceFacts.unknown() : resource;
        path = path == null ? PathFacts.unknown() : path;
        runtime = runtime == null ? RuntimeFacts.unknown() : runtime;
        media = media == null ? MediaFacts.unknown() : media;
    }

    public static PlaybackAutoContext empty() {
        return new PlaybackAutoContext(SessionToken.none(), -1, 0, -1,
                Fact.unknown(Kernel.UNKNOWN),
                Fact.unknown(DecodeMode.UNKNOWN),
                DeviceFacts.unknown(),
                ResourceFacts.unknown(),
                PathFacts.unknown(),
                RuntimeFacts.unknown(),
                MediaFacts.unknown());
    }

    static PlaybackAutoContext begin(SessionToken session, long startedAtElapsedMs) {
        long startedAt = Math.max(0, startedAtElapsedMs);
        return new PlaybackAutoContext(session, startedAt, 0, startedAt,
                Fact.unknown(Kernel.UNKNOWN),
                Fact.unknown(DecodeMode.UNKNOWN),
                DeviceFacts.unknown(),
                ResourceFacts.unknown(),
                PathFacts.unknown(),
                RuntimeFacts.unknown(),
                MediaFacts.unknown());
    }

    public boolean active() {
        return session.active();
    }

    PlaybackAutoContext withPlaybackFacts(Fact<Kernel> kernel, Fact<DecodeMode> decodeMode, PathFacts path) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withPlaybackFacts(Fact<Kernel> kernel, Fact<DecodeMode> decodeMode, ResourceFacts resource, PathFacts path) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withDeviceFacts(DeviceFacts device) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withMemoryFacts(Fact<MemoryPressure> pressure, Fact<MemorySnapshot> snapshot,
                                        Fact<Long> diagnosticPssBytes) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device.withMemoryFacts(pressure, snapshot, diagnosticPssBytes), resource, path, runtime, media);
    }

    PlaybackAutoContext withSystemConditionFacts(
            Fact<ThermalState> thermal,
            Fact<PowerState> power,
            Fact<NetworkCost> networkCost,
            Fact<NetworkSnapshot> networkSnapshot) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode,
                device.withSystemConditionFacts(thermal, power, networkCost, networkSnapshot),
                resource, path, runtime, media);
    }

    PlaybackAutoContext withResourceFacts(ResourceFacts resource) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withPathFacts(PathFacts path) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withRuntimeFacts(RuntimeFacts runtime) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withMediaFacts(MediaFacts media) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime, media);
    }

    PlaybackAutoContext withPublication(long revision, long publishedAtElapsedMs) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, Math.max(0, revision),
                Math.max(startedAtElapsedMs, publishedAtElapsedMs), kernel, decodeMode, device, resource, path, runtime, media);
    }

    public String logSummary() {
        return "generation=" + session.generation() +
                " revision=" + revision +
                " kernel=" + factLabel(kernel, kernel.value().label()) +
                " decode=" + factLabel(decodeMode, decodeMode.value().label()) +
                " " + path.logSummary() +
                " protocol=" + factLabel(resource.protocol(), resource.protocol().value().label()) +
                " stream=" + factLabel(resource.streamKind(), resource.streamKind().value().label()) +
                " transfer=" + factLabel(resource.transferUnit(), resource.transferUnit().value().label()) +
                " manifest=" + factLabel(resource.manifest(), resource.manifest().value().kind().label()) +
                " runtime=" + runtime.logSummary() +
                " memory=" + factLabel(device.memoryPressure(), device.memoryPressure().value().label()) +
                " " + device.memoryLogSummary() +
                " " + device.systemConditionLogSummary() +
                " " + media.logSummary();
    }

    private static String factLabel(Fact<?> fact, String value) {
        String safeValue = fact.hasValue() ? value : "unknown";
        return safeValue + "/" + fact.source().label() + "/" + fact.confidence().label() + "/" + fact.expiryRule().label();
    }

    private static String safeRuntimeLabel(String value) {
        if (value == null) return "unknown";
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) return "unknown";
        boolean mimeLike = normalized.matches("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+");
        if (normalized.contains("://") || (!mimeLike && normalized.indexOf('/') >= 0) || normalized.indexOf('\\') >= 0
                || normalized.indexOf('?') >= 0 || normalized.indexOf('@') >= 0) return "unknown";
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '+' || (mimeLike && c == '/')
                    || c == ':' || c == ',' || c == ' ' || c == '(' || c == ')') continue;
            return "unknown";
        }
        return normalized;
    }

    public record SessionToken(String traceId, long generation) {

        public SessionToken {
            traceId = PlaybackTrace.normalize(traceId);
            if (PlaybackTrace.NONE.equals(traceId) || generation <= 0) {
                traceId = PlaybackTrace.NONE;
                generation = 0;
            }
        }

        public static SessionToken none() {
            return new SessionToken(PlaybackTrace.NONE, 0);
        }

        public boolean active() {
            return generation > 0 && !PlaybackTrace.NONE.equals(traceId);
        }
    }

    public record Fact<T>(
            T value,
            ValueSource source,
            Confidence confidence,
            long sampledAtElapsedMs,
            ExpiryRule expiryRule,
            long expiresAtElapsedMs) {

        public Fact {
            value = Objects.requireNonNull(value);
            source = source == null ? ValueSource.UNKNOWN : source;
            confidence = confidence == null ? Confidence.UNKNOWN : confidence;
            expiryRule = expiryRule == null ? ExpiryRule.UNKNOWN : expiryRule;
            if (expiryRule == ExpiryRule.TTL) {
                if (sampledAtElapsedMs < 0 || expiresAtElapsedMs <= sampledAtElapsedMs) {
                    throw new IllegalArgumentException("TTL fact must expire after its sample");
                }
            } else {
                expiresAtElapsedMs = -1;
            }
        }

        public static <T> Fact<T> unknown(T value) {
            return new Fact<>(value, ValueSource.UNKNOWN, Confidence.UNKNOWN, -1, ExpiryRule.UNKNOWN, -1);
        }

        public static <T> Fact<T> forSession(T value, ValueSource source, Confidence confidence, long sampledAtElapsedMs) {
            return new Fact<>(value, source, confidence, Math.max(0, sampledAtElapsedMs), ExpiryRule.SESSION, -1);
        }

        public static <T> Fact<T> untilReplaced(T value, ValueSource source, Confidence confidence, long sampledAtElapsedMs) {
            return new Fact<>(value, source, confidence, Math.max(0, sampledAtElapsedMs), ExpiryRule.UNTIL_REPLACED, -1);
        }

        public static <T> Fact<T> withTtl(T value, ValueSource source, Confidence confidence, long sampledAtElapsedMs, long validForMs) {
            long sampledAt = Math.max(0, sampledAtElapsedMs);
            long duration = Math.max(1, validForMs);
            long expiresAt = sampledAt > Long.MAX_VALUE - duration ? Long.MAX_VALUE : sampledAt + duration;
            if (expiresAt <= sampledAt) throw new IllegalArgumentException("TTL fact duration overflow");
            return new Fact<>(value, source, confidence, sampledAt, ExpiryRule.TTL, expiresAt);
        }

        public boolean hasValue() {
            return source != ValueSource.UNKNOWN;
        }

        public boolean isExpired(long elapsedRealtimeMs) {
            return switch (expiryRule) {
                case UNKNOWN -> true;
                case TTL -> Math.max(0, elapsedRealtimeMs) >= expiresAtElapsedMs;
                case SESSION, UNTIL_REPLACED -> false;
            };
        }

        public boolean isUsable(long elapsedRealtimeMs) {
            return hasValue() && confidence != Confidence.UNKNOWN && !isExpired(elapsedRealtimeMs);
        }

        public long ageMs(long elapsedRealtimeMs) {
            return sampledAtElapsedMs < 0 ? -1 : Math.max(0, elapsedRealtimeMs - sampledAtElapsedMs);
        }
    }

    public record DeviceFacts(
            Fact<MemoryPressure> memoryPressure,
            Fact<MemorySnapshot> memorySnapshot,
            Fact<Long> diagnosticPssBytes,
            Fact<ThermalState> thermalState,
            Fact<PowerState> powerState,
            Fact<NetworkCost> networkCost,
            Fact<NetworkSnapshot> networkSnapshot) {

        public DeviceFacts(
                Fact<MemoryPressure> memoryPressure,
                Fact<ThermalState> thermalState,
                Fact<PowerState> powerState,
                Fact<NetworkCost> networkCost) {
            this(memoryPressure, Fact.unknown(MemorySnapshot.unknown()), Fact.unknown(-1L),
                    thermalState, powerState, networkCost, Fact.unknown(NetworkSnapshot.unknown()));
        }

        public DeviceFacts(
                Fact<MemoryPressure> memoryPressure,
                Fact<MemorySnapshot> memorySnapshot,
                Fact<Long> diagnosticPssBytes,
                Fact<ThermalState> thermalState,
                Fact<PowerState> powerState,
                Fact<NetworkCost> networkCost) {
            this(memoryPressure, memorySnapshot, diagnosticPssBytes, thermalState, powerState,
                    networkCost, Fact.unknown(NetworkSnapshot.unknown()));
        }

        public DeviceFacts {
            memoryPressure = memoryPressure == null ? Fact.unknown(MemoryPressure.UNKNOWN) : memoryPressure;
            memorySnapshot = memorySnapshot == null ? Fact.unknown(MemorySnapshot.unknown()) : memorySnapshot;
            diagnosticPssBytes = diagnosticPssBytes == null ? Fact.unknown(-1L) : diagnosticPssBytes;
            thermalState = thermalState == null ? Fact.unknown(ThermalState.UNKNOWN) : thermalState;
            powerState = powerState == null ? Fact.unknown(PowerState.UNKNOWN) : powerState;
            networkCost = networkCost == null ? Fact.unknown(NetworkCost.UNKNOWN) : networkCost;
            networkSnapshot = networkSnapshot == null ? Fact.unknown(NetworkSnapshot.unknown()) : networkSnapshot;
        }

        public static DeviceFacts unknown() {
            return new DeviceFacts(
                    Fact.unknown(MemoryPressure.UNKNOWN),
                    Fact.unknown(MemorySnapshot.unknown()),
                    Fact.unknown(-1L),
                    Fact.unknown(ThermalState.UNKNOWN),
                    Fact.unknown(PowerState.UNKNOWN),
                    Fact.unknown(NetworkCost.UNKNOWN),
                    Fact.unknown(NetworkSnapshot.unknown()));
        }

        DeviceFacts withMemoryFacts(Fact<MemoryPressure> pressure, Fact<MemorySnapshot> snapshot,
                                    Fact<Long> pssBytes) {
            return new DeviceFacts(
                    pressure == null ? memoryPressure : pressure,
                    snapshot == null ? memorySnapshot : snapshot,
                    pssBytes == null ? diagnosticPssBytes : pssBytes,
                    thermalState,
                    powerState,
                    networkCost,
                    networkSnapshot);
        }

        DeviceFacts withSystemConditionFacts(
                Fact<ThermalState> thermal,
                Fact<PowerState> power,
                Fact<NetworkCost> cost,
                Fact<NetworkSnapshot> network) {
            return new DeviceFacts(
                    memoryPressure,
                    memorySnapshot,
                    diagnosticPssBytes,
                    thermal == null ? thermalState : thermal,
                    power == null ? powerState : power,
                    cost == null ? networkCost : cost,
                    network == null ? networkSnapshot : network);
        }

        private String memoryLogSummary() {
            String snapshot = memorySnapshot.hasValue() ? memorySnapshot.value().logSummary() : MemorySnapshot.unknown().logSummary();
            long pss = diagnosticPssBytes.hasValue() ? diagnosticPssBytes.value() : -1;
            return snapshot + " pssBytes=" + pss;
        }

        private String systemConditionLogSummary() {
            NetworkSnapshot snapshot = networkSnapshot.hasValue()
                    ? networkSnapshot.value() : NetworkSnapshot.unknown();
            return "thermal=" + factLabel(thermalState, thermalState.value().label())
                    + " power=" + factLabel(powerState, powerState.value().label())
                    + " networkCost=" + factLabel(networkCost, networkCost.value().label())
                    + " " + snapshot.logSummary();
        }
    }

    public record MemorySnapshot(
            MemoryTrigger trigger,
            Long javaHeapUsedBytes,
            Long javaHeapLimitBytes,
            Long javaHeapHeadroomBytes,
            Boolean lowRamDevice,
            Long systemTotalBytes,
            Long systemAvailableBytes,
            Long systemThresholdBytes,
            Boolean systemLowMemory,
            Integer lastTrimLevel,
            Integer processImportance,
            Long nativeHeapAllocatedBytes) {

        public MemorySnapshot {
            trigger = trigger == null ? MemoryTrigger.UNKNOWN : trigger;
            javaHeapUsedBytes = nonNegativeOrNull(javaHeapUsedBytes);
            javaHeapLimitBytes = nonNegativeOrNull(javaHeapLimitBytes);
            javaHeapHeadroomBytes = nonNegativeOrNull(javaHeapHeadroomBytes);
            systemTotalBytes = nonNegativeOrNull(systemTotalBytes);
            systemAvailableBytes = nonNegativeOrNull(systemAvailableBytes);
            systemThresholdBytes = nonNegativeOrNull(systemThresholdBytes);
            lastTrimLevel = nonNegativeOrNull(lastTrimLevel);
            processImportance = nonNegativeOrNull(processImportance);
            nativeHeapAllocatedBytes = nonNegativeOrNull(nativeHeapAllocatedBytes);
            if (javaHeapLimitBytes != null && javaHeapHeadroomBytes != null) {
                javaHeapHeadroomBytes = Math.min(javaHeapLimitBytes, javaHeapHeadroomBytes);
            }
        }

        public static MemorySnapshot unknown() {
            return new MemorySnapshot(MemoryTrigger.UNKNOWN, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        public boolean hasEvidence() {
            return trigger != MemoryTrigger.UNKNOWN
                    || javaHeapUsedBytes != null
                    || javaHeapLimitBytes != null
                    || systemAvailableBytes != null
                    || systemLowMemory != null
                    || lastTrimLevel != null
                    || nativeHeapAllocatedBytes != null;
        }

        public boolean hasJavaMetrics() {
            return javaHeapUsedBytes != null && javaHeapLimitBytes != null && javaHeapHeadroomBytes != null;
        }

        public boolean hasSystemMetrics() {
            return systemAvailableBytes != null && systemThresholdBytes != null && systemLowMemory != null;
        }

        private String logSummary() {
            return "memoryTrigger=" + trigger.label()
                    + " javaUsed=" + safeLong(javaHeapUsedBytes)
                    + " javaLimit=" + safeLong(javaHeapLimitBytes)
                    + " javaHeadroom=" + safeLong(javaHeapHeadroomBytes)
                    + " lowRam=" + safeBoolean(lowRamDevice)
                    + " systemTotal=" + safeLong(systemTotalBytes)
                    + " systemAvail=" + safeLong(systemAvailableBytes)
                    + " systemThreshold=" + safeLong(systemThresholdBytes)
                    + " systemLow=" + safeBoolean(systemLowMemory)
                    + " trim=" + safeInt(lastTrimLevel)
                    + " importance=" + safeInt(processImportance)
                    + " nativeHeap=" + safeLong(nativeHeapAllocatedBytes);
        }

        private static Long nonNegativeOrNull(Long value) {
            return value == null || value < 0 ? null : value;
        }

        private static Integer nonNegativeOrNull(Integer value) {
            return value == null || value < 0 ? null : value;
        }

        private static long safeLong(Long value) {
            return value == null ? -1 : value;
        }

        private static int safeInt(Integer value) {
            return value == null ? -1 : value;
        }

        private static String safeBoolean(Boolean value) {
            return value == null ? "unknown" : value.toString();
        }
    }

    public record NetworkSnapshot(
            Boolean available,
            Boolean validated,
            Boolean metered,
            Boolean roaming,
            NetworkTransport transport,
            DataSaverState dataSaverState,
            String identityDigest) {

        public NetworkSnapshot(
                Boolean available,
                Boolean validated,
                Boolean metered,
                Boolean roaming,
                NetworkTransport transport,
                DataSaverState dataSaverState) {
            this(available, validated, metered, roaming, transport, dataSaverState, "");
        }

        public NetworkSnapshot {
            transport = transport == null ? NetworkTransport.UNKNOWN : transport;
            dataSaverState = dataSaverState == null ? DataSaverState.UNKNOWN : dataSaverState;
            identityDigest = PlaybackNetworkIdentityPolicy.isValidDigest(identityDigest)
                    ? identityDigest : "";
        }

        public static NetworkSnapshot unknown() {
            return new NetworkSnapshot(null, null, null, null,
                    NetworkTransport.UNKNOWN, DataSaverState.UNKNOWN, "");
        }

        public boolean hasEvidence() {
            return available != null
                    || validated != null
                    || metered != null
                    || roaming != null
                    || transport != NetworkTransport.UNKNOWN
                    || dataSaverState != DataSaverState.UNKNOWN
                    || !identityDigest.isEmpty();
        }

        public boolean hasCoreEvidence() {
            if (available == null || validated == null || dataSaverState == DataSaverState.UNKNOWN) return false;
            return !available || metered != null;
        }

        private String logSummary() {
            return "networkAvailable=" + safeBoolean(available)
                    + " validated=" + safeBoolean(validated)
                    + " metered=" + safeBoolean(metered)
                    + " roaming=" + safeBoolean(roaming)
                    + " transport=" + transport.label()
                    + " dataSaver=" + dataSaverState.label()
                    + " networkIdentity=" + (identityDigest.isEmpty() ? "unknown" : "known");
        }

        @Override
        public String toString() {
            return logSummary();
        }

        private static String safeBoolean(Boolean value) {
            return value == null ? "unknown" : value.toString();
        }
    }

    public record ResourceFacts(
            Fact<Protocol> protocol,
            Fact<StreamKind> streamKind,
            Fact<RangeSupport> rangeSupport,
            Fact<TransferUnit> transferUnit,
            Fact<ManifestFacts> manifest) {

        public ResourceFacts(
                Fact<Protocol> protocol,
                Fact<StreamKind> streamKind,
                Fact<RangeSupport> rangeSupport,
                Fact<TransferUnit> transferUnit) {
            this(protocol, streamKind, rangeSupport, transferUnit, Fact.unknown(ManifestFacts.unknown()));
        }

        public ResourceFacts {
            protocol = protocol == null ? Fact.unknown(Protocol.UNKNOWN) : protocol;
            streamKind = streamKind == null ? Fact.unknown(StreamKind.UNKNOWN) : streamKind;
            rangeSupport = rangeSupport == null ? Fact.unknown(RangeSupport.UNKNOWN) : rangeSupport;
            transferUnit = transferUnit == null ? Fact.unknown(TransferUnit.UNKNOWN) : transferUnit;
            manifest = manifest == null ? Fact.unknown(ManifestFacts.unknown()) : manifest;
        }

        public static ResourceFacts unknown() {
            return new ResourceFacts(
                    Fact.unknown(Protocol.UNKNOWN),
                    Fact.unknown(StreamKind.UNKNOWN),
                    Fact.unknown(RangeSupport.UNKNOWN),
                    Fact.unknown(TransferUnit.UNKNOWN),
                    Fact.unknown(ManifestFacts.unknown()));
        }
    }

    public record PathFacts(
            Fact<PlaybackRoute> route,
            Fact<PlaybackRoute.Owner> owner,
            Fact<Boolean> loopback,
            Fact<PlaybackRouteCapabilities.ObservedLeg> observedLeg,
            Fact<PlaybackRouteCapabilities.UpstreamVisibility> upstreamVisibility,
            Fact<PlaybackRouteCapabilities.ControlScope> controlScope,
            Fact<PathKind> playerPath,
            Fact<PathKind> upstreamPath,
            Fact<UpstreamState> upstreamState) {

        public PathFacts(
                Fact<PlaybackRoute> route,
                Fact<PlaybackRoute.Owner> owner,
                Fact<Boolean> loopback,
                Fact<PlaybackRouteCapabilities.ObservedLeg> observedLeg,
                Fact<PlaybackRouteCapabilities.UpstreamVisibility> upstreamVisibility,
                Fact<PlaybackRouteCapabilities.ControlScope> controlScope) {
            this(route, owner, loopback, observedLeg, upstreamVisibility, controlScope,
                    Fact.unknown(PathKind.UNKNOWN), Fact.unknown(PathKind.UNKNOWN), Fact.unknown(UpstreamState.UNKNOWN));
        }

        public PathFacts {
            route = route == null ? Fact.unknown(PlaybackRoute.OTHER) : route;
            owner = owner == null ? Fact.unknown(PlaybackRoute.Owner.UNKNOWN) : owner;
            loopback = loopback == null ? Fact.unknown(false) : loopback;
            observedLeg = observedLeg == null ? Fact.unknown(PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC) : observedLeg;
            upstreamVisibility = upstreamVisibility == null ? Fact.unknown(PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN) : upstreamVisibility;
            controlScope = controlScope == null ? Fact.unknown(PlaybackRouteCapabilities.ControlScope.NONE) : controlScope;
            playerPath = playerPath == null ? Fact.unknown(PathKind.UNKNOWN) : playerPath;
            upstreamPath = upstreamPath == null ? Fact.unknown(PathKind.UNKNOWN) : upstreamPath;
            upstreamState = upstreamState == null ? Fact.unknown(UpstreamState.UNKNOWN) : upstreamState;
        }

        public static PathFacts unknown() {
            return new PathFacts(
                    Fact.unknown(PlaybackRoute.OTHER),
                    Fact.unknown(PlaybackRoute.Owner.UNKNOWN),
                    Fact.unknown(false),
                    Fact.unknown(PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC),
                    Fact.unknown(PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN),
                    Fact.unknown(PlaybackRouteCapabilities.ControlScope.NONE),
                    Fact.unknown(PathKind.UNKNOWN),
                    Fact.unknown(PathKind.UNKNOWN),
                    Fact.unknown(UpstreamState.UNKNOWN));
        }

        public static PathFacts fromResolution(PlaybackRoute.Resolution resolution, long sampledAtElapsedMs) {
            PlaybackRoute.Resolution safe = resolution == null ? PlaybackRoute.resolve(null) : resolution;
            PlaybackRouteCapabilities capabilities = PlaybackRouteCapabilities.resolve(safe);
            Confidence confidence = Confidence.fromRoute(safe.confidence());
            PathKind playerPath = pathKind(safe.location());
            PathKind upstreamPath = switch (playerPath) {
                case LOCAL, LAN_PRIVATE, REMOTE -> playerPath;
                default -> PathKind.UNKNOWN;
            };
            UpstreamState upstreamState = playerPath == PathKind.LOCAL
                    ? UpstreamState.NOT_APPLICABLE : upstreamState(capabilities.upstreamVisibility());
            return new PathFacts(
                    Fact.forSession(safe.route(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(safe.owner(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(safe.loopback(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(capabilities.observedLeg(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(capabilities.upstreamVisibility(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(capabilities.controlScope(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(playerPath, ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    upstreamPath == PathKind.UNKNOWN ? Fact.unknown(PathKind.UNKNOWN) : Fact.forSession(upstreamPath, ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(upstreamState, ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs));
        }

        private String logSummary() {
            String routeValue = route.hasValue() ? route.value().name().toLowerCase(Locale.US) : "unknown";
            String ownerValue = owner.hasValue() ? owner.value().label() : "unknown";
            String playerValue = playerPath.hasValue() ? playerPath.value().label() : "unknown";
            String upstreamValue = upstreamPath.hasValue() ? upstreamPath.value().label() : "unknown";
            return "route=" + factLabel(route, routeValue) + " owner=" + factLabel(owner, ownerValue)
                    + " playerPath=" + factLabel(playerPath, playerValue)
                    + " upstreamPath=" + factLabel(upstreamPath, upstreamValue)
                    + " upstreamState=" + factLabel(upstreamState, upstreamState.value().label());
        }

        private static PathKind pathKind(PlaybackRoute.Location location) {
            if (location == null) return PathKind.UNKNOWN;
            return switch (location) {
                case LOCAL -> PathKind.LOCAL;
                case LAN_PRIVATE -> PathKind.LAN_PRIVATE;
                case REMOTE -> PathKind.REMOTE;
                case APP_INTERNAL_SERVICE -> PathKind.APP_INTERNAL_SERVICE;
                case EXTERNAL_LOOPBACK -> PathKind.EXTERNAL_LOOPBACK;
                case UNKNOWN -> PathKind.UNKNOWN;
            };
        }

        private static UpstreamState upstreamState(PlaybackRouteCapabilities.UpstreamVisibility visibility) {
            if (visibility == null) return UpstreamState.UNKNOWN;
            return switch (visibility) {
                case REQUEST_LEVEL_ONLY, APP_SERVICE_PATH -> UpstreamState.VISIBLE;
                case OPAQUE_EXTERNAL_PROCESS -> UpstreamState.OPAQUE;
                case UNKNOWN -> UpstreamState.UNKNOWN;
            };
        }
    }

    public record ManifestFacts(
            ManifestKind kind,
            Boolean endList,
            Long targetDurationMs,
            Long partDurationMs,
            Long holdBackMs,
            Integer variantCount,
            Boolean byteRange,
            Boolean lowLatency) {

        public ManifestFacts {
            kind = kind == null ? ManifestKind.UNKNOWN : kind;
            targetDurationMs = nonNegativeOrNull(targetDurationMs);
            partDurationMs = nonNegativeOrNull(partDurationMs);
            holdBackMs = nonNegativeOrNull(holdBackMs);
            variantCount = variantCount == null ? null : Math.max(0, variantCount);
        }

        public static ManifestFacts unknown() {
            return new ManifestFacts(ManifestKind.UNKNOWN, null, null, null, null, null, null, null);
        }

        public static ManifestFacts none() {
            return new ManifestFacts(ManifestKind.NONE, null, null, null, null, null, null, null);
        }

        private static Long nonNegativeOrNull(Long value) {
            return value == null || value < 0 ? null : value;
        }
    }

    public record RuntimeFacts(
            Fact<PlaybackPhase> phase,
            Fact<Long> bufferedDurationMs,
            Fact<Long> bandwidthBitsPerSecond,
            Fact<Long> mediaBitrateBitsPerSecond,
            Fact<Float> renderedFrameRate,
            Fact<Long> droppedFrames,
            Fact<Integer> rebufferCount,
            Fact<Long> rebufferTotalMs,
            Fact<Long> firstFrameElapsedMs,
            Fact<Long> liveLagMs,
            Fact<Boolean> loading,
            Fact<Long> positionMs,
            Fact<Long> durationMs) {

        public RuntimeFacts(
                Fact<PlaybackPhase> phase,
                Fact<Long> bufferedDurationMs,
                Fact<Long> bandwidthBitsPerSecond,
                Fact<Long> mediaBitrateBitsPerSecond,
                Fact<Float> renderedFrameRate,
                Fact<Long> droppedFrames,
                Fact<Integer> rebufferCount) {
            this(phase, bufferedDurationMs, bandwidthBitsPerSecond, mediaBitrateBitsPerSecond,
                    renderedFrameRate, droppedFrames, rebufferCount,
                    Fact.unknown(0L), Fact.unknown(0L), Fact.unknown(0L),
                    Fact.unknown(false), Fact.unknown(0L), Fact.unknown(0L));
        }

        public RuntimeFacts {
            phase = phase == null ? Fact.unknown(PlaybackPhase.UNKNOWN) : phase;
            bufferedDurationMs = bufferedDurationMs == null ? Fact.unknown(0L) : bufferedDurationMs;
            bandwidthBitsPerSecond = bandwidthBitsPerSecond == null ? Fact.unknown(0L) : bandwidthBitsPerSecond;
            mediaBitrateBitsPerSecond = mediaBitrateBitsPerSecond == null ? Fact.unknown(0L) : mediaBitrateBitsPerSecond;
            renderedFrameRate = renderedFrameRate == null ? Fact.unknown(0f) : renderedFrameRate;
            droppedFrames = droppedFrames == null ? Fact.unknown(0L) : droppedFrames;
            rebufferCount = rebufferCount == null ? Fact.unknown(0) : rebufferCount;
            rebufferTotalMs = rebufferTotalMs == null ? Fact.unknown(0L) : rebufferTotalMs;
            firstFrameElapsedMs = firstFrameElapsedMs == null ? Fact.unknown(0L) : firstFrameElapsedMs;
            liveLagMs = liveLagMs == null ? Fact.unknown(0L) : liveLagMs;
            loading = loading == null ? Fact.unknown(false) : loading;
            positionMs = positionMs == null ? Fact.unknown(0L) : positionMs;
            durationMs = durationMs == null ? Fact.unknown(0L) : durationMs;
        }

        public static RuntimeFacts unknown() {
            return new RuntimeFacts(
                    Fact.unknown(PlaybackPhase.UNKNOWN),
                    Fact.unknown(0L),
                    Fact.unknown(0L),
                    Fact.unknown(0L),
                    Fact.unknown(0f),
                    Fact.unknown(0L),
                    Fact.unknown(0),
                    Fact.unknown(0L),
                    Fact.unknown(0L),
                    Fact.unknown(0L),
                    Fact.unknown(false),
                    Fact.unknown(0L),
                    Fact.unknown(0L));
        }

        private String logSummary() {
            return "phase=" + factLabel(phase, phase.value().label())
                    + " loading=" + factLabel(loading, loading.value().toString())
                    + " positionMs=" + factLabel(positionMs, String.valueOf(positionMs.value()))
                    + " durationMs=" + factLabel(durationMs, String.valueOf(durationMs.value()))
                    + " bufferedMs=" + factLabel(bufferedDurationMs, String.valueOf(bufferedDurationMs.value()))
                    + " bandwidthBps=" + factLabel(bandwidthBitsPerSecond, String.valueOf(bandwidthBitsPerSecond.value()))
                    + " mediaBps=" + factLabel(mediaBitrateBitsPerSecond, String.valueOf(mediaBitrateBitsPerSecond.value()))
                    + " renderedFps=" + factLabel(renderedFrameRate, String.valueOf(renderedFrameRate.value()))
                    + " dropped=" + factLabel(droppedFrames, String.valueOf(droppedFrames.value()))
                    + " rebufferCount=" + factLabel(rebufferCount, String.valueOf(rebufferCount.value()))
                    + " rebufferTotalMs=" + factLabel(rebufferTotalMs, String.valueOf(rebufferTotalMs.value()))
                    + " firstFrameMs=" + factLabel(firstFrameElapsedMs, String.valueOf(firstFrameElapsedMs.value()))
                    + " liveLagMs=" + factLabel(liveLagMs, String.valueOf(liveLagMs.value()));
        }
    }

    public record MediaFacts(
            long trackSequence,
            TrackFacts videoTrack,
            TrackFacts audioTrack,
            DecoderFacts decoder,
            OutputFacts output,
            DisplayFacts display) {

        public MediaFacts {
            trackSequence = Math.max(0, trackSequence);
            videoTrack = videoTrack == null ? TrackFacts.unknown() : videoTrack;
            audioTrack = audioTrack == null ? TrackFacts.unknown() : audioTrack;
            decoder = decoder == null ? DecoderFacts.unknown(trackSequence) : decoder;
            output = output == null ? OutputFacts.unknown() : output;
            display = display == null ? DisplayFacts.unknown() : display;
        }

        public static MediaFacts unknown() {
            return new MediaFacts(0, TrackFacts.unknown(), TrackFacts.unknown(),
                    DecoderFacts.unknown(0), OutputFacts.unknown(), DisplayFacts.unknown());
        }

        MediaFacts withEngineFacts(
                long observedTrackSequence,
                TrackFacts observedVideoTrack,
                TrackFacts observedAudioTrack,
                DecoderFacts observedDecoder,
                OutputFacts observedOutput) {
            if (observedTrackSequence < trackSequence) return null;
            boolean sequenceAdvanced = observedTrackSequence > trackSequence;
            TrackFacts nextVideo = observedVideoTrack != null
                    ? observedVideoTrack : sequenceAdvanced ? TrackFacts.unknown() : videoTrack;
            TrackFacts nextAudio = observedAudioTrack != null
                    ? observedAudioTrack : sequenceAdvanced ? TrackFacts.unknown() : audioTrack;
            DecoderFacts nextDecoder = observedDecoder != null
                    ? observedDecoder : sequenceAdvanced ? DecoderFacts.unknown(observedTrackSequence) : decoder;
            if (nextDecoder.trackSequence() != observedTrackSequence) {
                nextDecoder = DecoderFacts.unknown(observedTrackSequence);
            }
            OutputFacts nextOutput = observedOutput == null ? output : output.withEngineFacts(observedOutput);
            return new MediaFacts(observedTrackSequence, nextVideo, nextAudio, nextDecoder, nextOutput, display);
        }

        MediaFacts withRenderTarget(Fact<RenderTarget> renderTarget) {
            if (renderTarget == null) return this;
            return new MediaFacts(trackSequence, videoTrack, audioTrack, decoder,
                    output.withRenderTarget(renderTarget), display);
        }

        MediaFacts withDisplayFacts(DisplayFacts display) {
            if (display == null) return this;
            return new MediaFacts(trackSequence, videoTrack, audioTrack, decoder, output, display);
        }

        private String logSummary() {
            String videoMime = videoTrack.mimeType().hasValue()
                    ? safeRuntimeLabel(videoTrack.mimeType().value()) : "unknown";
            String videoCodec = videoTrack.codecs().hasValue()
                    ? safeRuntimeLabel(videoTrack.codecs().value()) : "unknown";
            String decoderName = decoder.videoDecoderName().hasValue()
                    ? safeRuntimeLabel(decoder.videoDecoderName().value()) : "unknown";
            String hwdec = output.hwdecCurrent().hasValue()
                    ? safeRuntimeLabel(output.hwdecCurrent().value()) : "unknown";
            String vo = output.currentVideoOutput().hasValue()
                    ? safeRuntimeLabel(output.currentVideoOutput().value()) : "unknown";
            DisplayMode currentMode = display.currentMode().hasValue()
                    ? display.currentMode().value() : DisplayMode.unknown();
            DisplayMode requestedMode = display.requestedMode().hasValue()
                    ? display.requestedMode().value() : DisplayMode.unknown();
            return "trackSeq=" + trackSequence
                    + " videoMime=" + videoMime
                    + " videoCodec=" + videoCodec
                    + " videoSize=" + safeInt(videoTrack.width()) + "x" + safeInt(videoTrack.height())
                    + " videoFps=" + safeFloat(videoTrack.frameRate())
                    + " hdr=" + (videoTrack.hdrType().hasValue() ? videoTrack.hdrType().value().label() : "unknown")
                    + " avgBitrate=" + safeLong(videoTrack.averageBitrateBitsPerSecond())
                    + " peakBitrate=" + safeLong(videoTrack.peakBitrateBitsPerSecond())
                    + " decoderSeq=" + decoder.trackSequence()
                    + " decoder=" + decoderName
                    + " actualDecode=" + (decoder.videoDecodeMode().hasValue() ? decoder.videoDecodeMode().value().label() : "unknown")
                    + " secure=" + safeBoolean(decoder.secureVideoDecoder())
                    + " renderPath=" + (output.renderPath().hasValue() ? output.renderPath().value().label() : "unknown")
                    + " renderTarget=" + (output.renderTarget().hasValue() ? output.renderTarget().value().label() : "unknown")
                    + " tunneling=" + safeBoolean(output.tunneling())
                    + " hwdec=" + hwdec
                    + " vo=" + vo
                    + " display=" + currentMode.logSummary()
                    + " requestedDisplay=" + requestedMode.logSummary();
        }

        private static int safeInt(Fact<Integer> fact) {
            return fact.hasValue() ? fact.value() : -1;
        }

        private static long safeLong(Fact<Long> fact) {
            return fact.hasValue() ? fact.value() : -1;
        }

        private static float safeFloat(Fact<Float> fact) {
            return fact.hasValue() ? fact.value() : -1f;
        }

        private static String safeBoolean(Fact<Boolean> fact) {
            return fact.hasValue() ? fact.value().toString() : "unknown";
        }
    }

    public record TrackFacts(
            Fact<String> mimeType,
            Fact<String> codecs,
            Fact<Integer> profile,
            Fact<Integer> level,
            Fact<Integer> width,
            Fact<Integer> height,
            Fact<Float> frameRate,
            Fact<HdrType> hdrType,
            Fact<ColorSnapshot> color,
            Fact<Long> averageBitrateBitsPerSecond,
            Fact<Long> peakBitrateBitsPerSecond) {

        public TrackFacts {
            mimeType = mimeType == null ? Fact.unknown("") : mimeType;
            codecs = codecs == null ? Fact.unknown("") : codecs;
            profile = profile == null ? Fact.unknown(-1) : profile;
            level = level == null ? Fact.unknown(-1) : level;
            width = width == null ? Fact.unknown(-1) : width;
            height = height == null ? Fact.unknown(-1) : height;
            frameRate = frameRate == null ? Fact.unknown(-1f) : frameRate;
            hdrType = hdrType == null ? Fact.unknown(HdrType.UNKNOWN) : hdrType;
            color = color == null ? Fact.unknown(ColorSnapshot.unknown()) : color;
            averageBitrateBitsPerSecond = averageBitrateBitsPerSecond == null ? Fact.unknown(-1L) : averageBitrateBitsPerSecond;
            peakBitrateBitsPerSecond = peakBitrateBitsPerSecond == null ? Fact.unknown(-1L) : peakBitrateBitsPerSecond;
        }

        public static TrackFacts unknown() {
            return new TrackFacts(
                    Fact.unknown(""), Fact.unknown(""), Fact.unknown(-1), Fact.unknown(-1),
                    Fact.unknown(-1), Fact.unknown(-1), Fact.unknown(-1f),
                    Fact.unknown(HdrType.UNKNOWN), Fact.unknown(ColorSnapshot.unknown()),
                    Fact.unknown(-1L), Fact.unknown(-1L));
        }

        public boolean hasEvidence() {
            return mimeType.hasValue() || codecs.hasValue() || width.hasValue() || height.hasValue()
                    || frameRate.hasValue() || hdrType.hasValue() || color.hasValue()
                    || averageBitrateBitsPerSecond.hasValue() || peakBitrateBitsPerSecond.hasValue();
        }
    }

    public record DecoderFacts(
            long trackSequence,
            Fact<String> videoDecoderName,
            Fact<String> audioDecoderName,
            Fact<DecodeMode> videoDecodeMode,
            Fact<Boolean> secureVideoDecoder) {

        public DecoderFacts {
            trackSequence = Math.max(0, trackSequence);
            videoDecoderName = videoDecoderName == null ? Fact.unknown("") : videoDecoderName;
            audioDecoderName = audioDecoderName == null ? Fact.unknown("") : audioDecoderName;
            videoDecodeMode = videoDecodeMode == null ? Fact.unknown(DecodeMode.UNKNOWN) : videoDecodeMode;
            secureVideoDecoder = secureVideoDecoder == null ? Fact.unknown(false) : secureVideoDecoder;
        }

        public static DecoderFacts unknown(long trackSequence) {
            return new DecoderFacts(trackSequence, Fact.unknown(""), Fact.unknown(""),
                    Fact.unknown(DecodeMode.UNKNOWN), Fact.unknown(false));
        }

        public boolean hasEvidence() {
            return videoDecoderName.hasValue() || audioDecoderName.hasValue()
                    || videoDecodeMode.hasValue() || secureVideoDecoder.hasValue();
        }
    }

    public record OutputFacts(
            Fact<RenderPath> renderPath,
            Fact<RenderTarget> renderTarget,
            Fact<Boolean> tunneling,
            Fact<String> hwdecCurrent,
            Fact<String> currentVideoOutput) {

        public OutputFacts {
            renderPath = renderPath == null ? Fact.unknown(RenderPath.UNKNOWN) : renderPath;
            renderTarget = renderTarget == null ? Fact.unknown(RenderTarget.UNKNOWN) : renderTarget;
            tunneling = tunneling == null ? Fact.unknown(false) : tunneling;
            hwdecCurrent = hwdecCurrent == null ? Fact.unknown("") : hwdecCurrent;
            currentVideoOutput = currentVideoOutput == null ? Fact.unknown("") : currentVideoOutput;
        }

        public static OutputFacts unknown() {
            return new OutputFacts(Fact.unknown(RenderPath.UNKNOWN), Fact.unknown(RenderTarget.UNKNOWN),
                    Fact.unknown(false), Fact.unknown(""), Fact.unknown(""));
        }

        OutputFacts withEngineFacts(OutputFacts observed) {
            if (observed == null) return this;
            return new OutputFacts(observed.renderPath, renderTarget, observed.tunneling,
                    observed.hwdecCurrent, observed.currentVideoOutput);
        }

        OutputFacts withRenderTarget(Fact<RenderTarget> observedRenderTarget) {
            return new OutputFacts(renderPath, observedRenderTarget, tunneling, hwdecCurrent, currentVideoOutput);
        }
    }

    public record DisplayFacts(Fact<DisplayMode> currentMode, Fact<DisplayMode> requestedMode) {

        public DisplayFacts {
            currentMode = currentMode == null ? Fact.unknown(DisplayMode.unknown()) : currentMode;
            requestedMode = requestedMode == null ? Fact.unknown(DisplayMode.unknown()) : requestedMode;
        }

        public static DisplayFacts unknown() {
            return new DisplayFacts(Fact.unknown(DisplayMode.unknown()), Fact.unknown(DisplayMode.unknown()));
        }
    }

    public record ColorSnapshot(int colorSpace, int colorRange, int colorTransfer, boolean hdrStaticMetadata) {

        public static ColorSnapshot unknown() {
            return new ColorSnapshot(-1, -1, -1, false);
        }

        public boolean hasEvidence() {
            return colorSpace >= 0 || colorRange >= 0 || colorTransfer >= 0 || hdrStaticMetadata;
        }
    }

    public record DisplayMode(int modeId, int width, int height, int refreshRateMilliHz) {

        public DisplayMode {
            modeId = modeId > 0 ? modeId : -1;
            width = width > 0 ? width : -1;
            height = height > 0 ? height : -1;
            refreshRateMilliHz = refreshRateMilliHz > 0 ? refreshRateMilliHz : -1;
        }

        public static DisplayMode unknown() {
            return new DisplayMode(-1, -1, -1, -1);
        }

        public boolean hasEvidence() {
            return modeId > 0 && width > 0 && height > 0 && refreshRateMilliHz > 0;
        }

        private String logSummary() {
            return hasEvidence() ? width + "x" + height + "@" + refreshRateMilliHz + "mHz#" + modeId : "unknown";
        }
    }

    public enum Kernel {
        EXO("exo"),
        IJK("ijk"),
        MPV("mpv"),
        UNKNOWN("unknown");

        private final String label;

        Kernel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum DecodeMode {
        HARDWARE("hardware"),
        SOFTWARE("software"),
        UNKNOWN("unknown");

        private final String label;

        DecodeMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum HdrType {
        SDR("sdr"),
        HDR10("hdr10"),
        HLG("hlg"),
        DOLBY_VISION("dolby-vision"),
        HDR_OTHER("hdr-other"),
        UNKNOWN("unknown");

        private final String label;

        HdrType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum RenderPath {
        EXO_TUNNELING("exo-tunneling"),
        MPV_SURFACE_DIRECT("mpv-surface-direct"),
        MPV_GPU("mpv-gpu"),
        IJK_NATIVE("ijk-native"),
        UNKNOWN("unknown");

        private final String label;

        RenderPath(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum RenderTarget {
        SURFACE_VIEW("surface-view"),
        TEXTURE_VIEW("texture-view"),
        DETACHED("detached"),
        UNKNOWN("unknown");

        private final String label;

        RenderTarget(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ValueSource {
        PLAYER_MANAGER("player-manager"),
        PLAYBACK_REQUEST("playback-request"),
        MANIFEST("manifest"),
        PROXY("proxy"),
        DATA_SOURCE("data-source"),
        ROUTE_CLASSIFIER("route-classifier"),
        PLAYER_CALLBACK("player-callback"),
        SYSTEM_CALLBACK("system-callback"),
        SYSTEM_API("system-api"),
        CODEC_STRING("codec-string"),
        NATIVE_RUNTIME("native-runtime"),
        ESTIMATOR("estimator"),
        UNKNOWN("unknown");

        private final String label;

        ValueSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Confidence {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        UNKNOWN("unknown");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Confidence fromRoute(PlaybackRoute.Confidence confidence) {
            if (confidence == null) return UNKNOWN;
            return switch (confidence) {
                case CONFIRMED -> HIGH;
                case INFERRED -> MEDIUM;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }

    public enum ExpiryRule {
        SESSION("session"),
        TTL("ttl"),
        UNTIL_REPLACED("until-replaced"),
        UNKNOWN("unknown");

        private final String label;

        ExpiryRule(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum MemoryPressure {
        NORMAL("normal"),
        MODERATE("moderate"),
        CRITICAL("critical"),
        UNKNOWN("unknown");

        private final String label;

        MemoryPressure(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum MemoryTrigger {
        SESSION_START("session-start"),
        PERIODIC("periodic"),
        TRIM_MEMORY("trim-memory"),
        LOW_MEMORY("low-memory"),
        UNKNOWN("unknown");

        private final String label;

        MemoryTrigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ThermalState {
        NOMINAL("nominal"),
        MODERATE("moderate"),
        SEVERE("severe"),
        CRITICAL("critical"),
        UNKNOWN("unknown");

        private final String label;

        ThermalState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum PowerState {
        NORMAL("normal"),
        POWER_SAVE("power-save"),
        UNKNOWN("unknown");

        private final String label;

        PowerState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum SystemConditionTrigger {
        SESSION_START("session-start"),
        PERIODIC("periodic"),
        NETWORK_CALLBACK("network-callback"),
        DATA_SAVER_CALLBACK("data-saver-callback"),
        POWER_CALLBACK("power-callback"),
        THERMAL_CALLBACK("thermal-callback"),
        UNKNOWN("unknown");

        private final String label;

        SystemConditionTrigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum DataSaverState {
        DISABLED("disabled"),
        ENABLED("enabled"),
        WHITELISTED("whitelisted"),
        UNKNOWN("unknown");

        private final String label;

        DataSaverState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum NetworkTransport {
        WIFI("wifi"),
        ETHERNET("ethernet"),
        CELLULAR("cellular"),
        VPN("vpn"),
        OTHER("other"),
        UNKNOWN("unknown");

        private final String label;

        NetworkTransport(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum NetworkCost {
        UNMETERED("unmetered"),
        METERED("metered"),
        ROAMING("roaming"),
        UNKNOWN("unknown");

        private final String label;

        NetworkCost(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A path classification that does not imply a change to the legacy route policy. */
    public enum PathKind {
        LOCAL("local"),
        LAN_PRIVATE("lan-private"),
        REMOTE("remote"),
        APP_INTERNAL_SERVICE("app-internal-service"),
        EXTERNAL_LOOPBACK("external-loopback"),
        UNKNOWN("unknown");

        private final String label;

        PathKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum UpstreamState {
        VISIBLE("visible"),
        OPAQUE("opaque"),
        NOT_APPLICABLE("not-applicable"),
        UNKNOWN("unknown");

        private final String label;

        UpstreamState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Protocol {
        LOCAL("local"),
        PROGRESSIVE_HTTP("progressive-http"),
        HLS("hls"),
        DASH("dash"),
        RTSP("rtsp"),
        RTMP("rtmp"),
        OTHER("other"),
        UNKNOWN("unknown");

        private final String label;

        Protocol(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum StreamKind {
        VOD("vod"),
        LIVE("live"),
        LOW_LATENCY_LIVE("low-latency-live"),
        UNKNOWN("unknown");

        private final String label;

        StreamKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum RangeSupport {
        SUPPORTED("supported"),
        UNSUPPORTED("unsupported"),
        UNKNOWN("unknown");

        private final String label;

        RangeSupport(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum TransferUnit {
        CONTINUOUS("continuous"),
        SEGMENT("segment"),
        PART("part"),
        UNKNOWN("unknown");

        private final String label;

        TransferUnit(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ManifestKind {
        NONE("none"),
        HLS_MASTER("hls-master"),
        HLS_MEDIA("hls-media"),
        DASH_STATIC("dash-static"),
        DASH_DYNAMIC("dash-dynamic"),
        UNKNOWN("unknown");

        private final String label;

        ManifestKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum PlaybackPhase {
        IDLE("idle"),
        PREPARING("preparing"),
        BUFFERING("buffering"),
        READY("ready"),
        ENDED("ended"),
        ERROR("error"),
        UNKNOWN("unknown");

        private final String label;

        PlaybackPhase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
