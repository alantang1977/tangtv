package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure IJK picture-queue and software-decode pressure policy. */
public final class IjkDecodePressurePolicy {

    public static final int AUTOMATIC_PICTURE_QUEUE = 3;
    public static final int RISK_CONFIRMATION_SAMPLES = 3;
    public static final long RISK_CONFIRMATION_WINDOW_MS = 10_000L;
    public static final int RECOVERY_CONFIRMATION_SAMPLES = 4;
    public static final long RECOVERY_CONFIRMATION_WINDOW_MS = 30_000L;

    private static final int MODERATE_DECODE_RATIO_PERMILLE = 900;
    private static final int MODERATE_OUTPUT_RATIO_PERMILLE = 850;
    private static final int MODERATE_OUTPUT_TO_DECODE_PERMILLE = 850;
    private static final int SEVERE_DECODE_RATIO_PERMILLE = 700;
    private static final int SEVERE_OUTPUT_RATIO_PERMILLE = 650;
    private static final int SEVERE_OUTPUT_TO_DECODE_PERMILLE = 650;

    private IjkDecodePressurePolicy() {
    }

    public static Config automaticInitialConfig() {
        return Config.automatic(TuneMode.OFF);
    }

    /** Resolves only prepare-time options; automatic picture queue is always three frames. */
    public static Config prepareConfig(
            boolean automatic,
            Config stagedAutomatic,
            boolean requestedSoftwareDecode,
            int manualPictureQueue,
            TuneMode manualTuneMode) {
        if (automatic) {
            Config staged = stagedAutomatic == null
                    ? automaticInitialConfig() : stagedAutomatic;
            return Config.automatic(staged.tuneMode());
        }
        return new Config(
                normalizeManualPictureQueue(manualPictureQueue),
                requestedSoftwareDecode
                        ? safeTune(manualTuneMode) : TuneMode.OFF);
    }

    public static Assessment assess(Input input) {
        Input current = input == null ? Input.inactive() : input;
        if (!current.automatic() || !current.ijk()) {
            return Assessment.hold(Reason.NOT_AUTOMATIC_IJK);
        }
        if (!current.active()) return Assessment.hold(Reason.INACTIVE);
        if (!current.startupComplete()) return Assessment.hold(Reason.STARTUP);
        if (!current.normalSpeed()) return Assessment.hold(Reason.NON_UNIT_SPEED);
        if (current.userSeeking()) return Assessment.hold(Reason.USER_SEEK);
        if (current.actionPending()) return Assessment.hold(Reason.ACTION_PENDING);
        if (!current.decoderUsable()
                || current.actualDecode() == PlaybackAutoContext.DecodeMode.UNKNOWN) {
            return Assessment.hold(Reason.DECODER_UNKNOWN);
        }
        if (current.actualDecode() != PlaybackAutoContext.DecodeMode.SOFTWARE) {
            return new Assessment(
                    Pressure.HEALTHY,
                    Reason.HARDWARE_DECODER,
                    false,
                    false,
                    TuneMode.OFF,
                    metrics(current));
        }
        if (!current.thermalUsable()
                || current.thermal() == PlaybackAutoContext.ThermalState.UNKNOWN) {
            return new Assessment(
                    Pressure.UNKNOWN,
                    Reason.THERMAL_UNKNOWN,
                    false,
                    false,
                    TuneMode.OFF,
                    metrics(current));
        }

        Metrics metrics = metrics(current);
        if (!metrics.fpsUsable()) {
            return new Assessment(
                    Pressure.UNKNOWN,
                    Reason.FPS_UNKNOWN,
                    false,
                    false,
                    TuneMode.OFF,
                    metrics);
        }

        Pressure rawPressure = classify(metrics);
        if (rawPressure == Pressure.HEALTHY) {
            boolean recovery = current.thermal()
                    == PlaybackAutoContext.ThermalState.NOMINAL;
            return new Assessment(
                    Pressure.HEALTHY,
                    Reason.HEALTHY,
                    false,
                    recovery,
                    TuneMode.OFF,
                    metrics);
        }
        if (current.thermal() == PlaybackAutoContext.ThermalState.NOMINAL) {
            return new Assessment(
                    rawPressure,
                    Reason.THERMAL_NOMINAL_HOLD,
                    false,
                    false,
                    TuneMode.OFF,
                    metrics);
        }

        Pressure actionable = current.thermal()
                == PlaybackAutoContext.ThermalState.MODERATE
                ? Pressure.MODERATE : Pressure.SEVERE;
        TuneMode suggested = actionable == Pressure.SEVERE
                ? TuneMode.AGGRESSIVE : TuneMode.MILD;
        return new Assessment(
                actionable,
                actionable == Pressure.SEVERE
                        ? Reason.SEVERE_PRESSURE : Reason.MODERATE_PRESSURE,
                true,
                false,
                suggested,
                metrics);
    }

    static Pressure classify(Metrics metrics) {
        Metrics current = metrics == null ? Metrics.unknown() : metrics;
        if (!current.fpsUsable()) return Pressure.UNKNOWN;
        boolean targetUsable = current.targetFps() > 0;
        boolean severeTarget = targetUsable
                && (current.decodeRatioPermille()
                < SEVERE_DECODE_RATIO_PERMILLE
                || current.outputRatioPermille()
                < SEVERE_OUTPUT_RATIO_PERMILLE);
        boolean severeGap = current.decodeFps() - current.outputFps()
                >= Math.max(6f, current.decodeFps() * 0.25f)
                && current.outputToDecodePermille()
                < SEVERE_OUTPUT_TO_DECODE_PERMILLE;
        if (severeTarget || severeGap) return Pressure.SEVERE;

        boolean moderateTarget = targetUsable
                && (current.decodeRatioPermille()
                < MODERATE_DECODE_RATIO_PERMILLE
                || current.outputRatioPermille()
                < MODERATE_OUTPUT_RATIO_PERMILLE);
        boolean moderateGap = current.decodeFps() - current.outputFps()
                >= Math.max(3f, current.decodeFps() * 0.15f)
                && current.outputToDecodePermille()
                < MODERATE_OUTPUT_TO_DECODE_PERMILLE;
        return moderateTarget || moderateGap
                ? Pressure.MODERATE : Pressure.HEALTHY;
    }

    private static Metrics metrics(Input input) {
        DecodeSnapshot snapshot = input.snapshot();
        float target = input.targetFrameRateUsable()
                && finitePositive(input.targetFrameRate())
                ? input.targetFrameRate() : -1f;
        if (!snapshot.available()
                || !finitePositive(snapshot.decodeFps())
                || !finitePositive(snapshot.outputFps())) {
            return new Metrics(
                    false, target, snapshot.decodeFps(), snapshot.outputFps(),
                    -1, -1, -1);
        }
        int decodeRatio = target > 0
                ? ratioPermille(snapshot.decodeFps(), target) : -1;
        int outputRatio = target > 0
                ? ratioPermille(snapshot.outputFps(), target) : -1;
        int outputToDecode = ratioPermille(
                snapshot.outputFps(), snapshot.decodeFps());
        return new Metrics(
                true,
                target,
                snapshot.decodeFps(),
                snapshot.outputFps(),
                decodeRatio,
                outputRatio,
                outputToDecode);
    }

    private static int ratioPermille(float numerator, float denominator) {
        if (!finitePositive(numerator) || !finitePositive(denominator)) return -1;
        double ratio = numerator / (double) denominator * 1_000d;
        if (!Double.isFinite(ratio)) return -1;
        return (int) Math.max(0, Math.min(10_000, Math.round(ratio)));
    }

    private static int normalizeManualPictureQueue(int value) {
        return value <= 3 ? 3 : value <= 5 ? 5 : 8;
    }

    private static TuneMode safeTune(TuneMode value) {
        return value == null ? TuneMode.OFF : value;
    }

    private static boolean finitePositive(float value) {
        return Float.isFinite(value) && value > 0;
    }

    public enum TuneMode {
        OFF("off", 0, 0, 0),
        MILD("mild", 1, 8, 0),
        AGGRESSIVE("aggressive", 1, 32, 8);

        private final String label;
        private final int fast;
        private final int skipLoopFilter;
        private final int skipFrame;

        TuneMode(String label, int fast, int skipLoopFilter, int skipFrame) {
            this.label = label;
            this.fast = fast;
            this.skipLoopFilter = skipLoopFilter;
            this.skipFrame = skipFrame;
        }

        public String label() {
            return label;
        }

        public int fast() {
            return fast;
        }

        public int skipLoopFilter() {
            return skipLoopFilter;
        }

        public int skipFrame() {
            return skipFrame;
        }
    }

    public enum Pressure {
        UNKNOWN("unknown"),
        HEALTHY("healthy"),
        MODERATE("moderate"),
        SEVERE("severe");

        private final String label;

        Pressure(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        NOT_AUTOMATIC_IJK("not-automatic-ijk"),
        INACTIVE("inactive"),
        STARTUP("startup"),
        NON_UNIT_SPEED("non-unit-speed"),
        USER_SEEK("user-seek"),
        ACTION_PENDING("action-pending"),
        DECODER_UNKNOWN("decoder-unknown"),
        HARDWARE_DECODER("hardware-decoder"),
        THERMAL_UNKNOWN("thermal-unknown"),
        FPS_UNKNOWN("fps-unknown"),
        THERMAL_NOMINAL_HOLD("thermal-nominal-hold"),
        HEALTHY("healthy"),
        MODERATE_PRESSURE("moderate-pressure"),
        SEVERE_PRESSURE("severe-pressure");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Config(int pictureQueue, TuneMode tuneMode) {

        public Config {
            pictureQueue = Math.max(3, Math.min(16, pictureQueue));
            tuneMode = safeTune(tuneMode);
        }

        public static Config automatic(TuneMode tuneMode) {
            return new Config(AUTOMATIC_PICTURE_QUEUE, tuneMode);
        }

        public String label() {
            return "pictq-" + pictureQueue + "-" + tuneMode.label();
        }
    }

    public record DecodeSnapshot(
            boolean available,
            float decodeFps,
            float outputFps) {

        public DecodeSnapshot {
            decodeFps = finitePositive(decodeFps) ? decodeFps : 0f;
            outputFps = finitePositive(outputFps) ? outputFps : 0f;
            available = available && decodeFps > 0 && outputFps > 0;
        }

        public static DecodeSnapshot unknown() {
            return new DecodeSnapshot(false, 0f, 0f);
        }
    }

    public record Metrics(
            boolean fpsUsable,
            float targetFps,
            float decodeFps,
            float outputFps,
            int decodeRatioPermille,
            int outputRatioPermille,
            int outputToDecodePermille) {

        public Metrics {
            targetFps = finitePositive(targetFps) ? targetFps : -1f;
            decodeFps = finitePositive(decodeFps) ? decodeFps : 0f;
            outputFps = finitePositive(outputFps) ? outputFps : 0f;
            decodeRatioPermille = Math.max(-1, decodeRatioPermille);
            outputRatioPermille = Math.max(-1, outputRatioPermille);
            outputToDecodePermille = Math.max(-1, outputToDecodePermille);
            fpsUsable = fpsUsable && decodeFps > 0 && outputFps > 0;
        }

        public static Metrics unknown() {
            return new Metrics(false, -1f, 0f, 0f, -1, -1, -1);
        }
    }

    public record Input(
            boolean automatic,
            boolean ijk,
            boolean active,
            boolean startupComplete,
            boolean normalSpeed,
            boolean userSeeking,
            boolean actionPending,
            boolean decoderUsable,
            PlaybackAutoContext.DecodeMode actualDecode,
            boolean thermalUsable,
            PlaybackAutoContext.ThermalState thermal,
            boolean targetFrameRateUsable,
            float targetFrameRate,
            DecodeSnapshot snapshot) {

        public Input {
            actualDecode = actualDecode == null
                    ? PlaybackAutoContext.DecodeMode.UNKNOWN : actualDecode;
            thermal = thermal == null
                    ? PlaybackAutoContext.ThermalState.UNKNOWN : thermal;
            targetFrameRate = finitePositive(targetFrameRate)
                    ? targetFrameRate : -1f;
            snapshot = snapshot == null ? DecodeSnapshot.unknown() : snapshot;
        }

        public Input withRuntimeGuards(
                boolean userSeeking,
                boolean actionPending) {
            return new Input(
                    automatic,
                    ijk,
                    active,
                    startupComplete,
                    normalSpeed,
                    userSeeking,
                    actionPending,
                    decoderUsable,
                    actualDecode,
                    thermalUsable,
                    thermal,
                    targetFrameRateUsable,
                    targetFrameRate,
                    snapshot);
        }

        static Input inactive() {
            return new Input(
                    false, false, false, false, false, false, false,
                    false, PlaybackAutoContext.DecodeMode.UNKNOWN,
                    false, PlaybackAutoContext.ThermalState.UNKNOWN,
                    false, -1f, DecodeSnapshot.unknown());
        }
    }

    public record Assessment(
            Pressure pressure,
            Reason reason,
            boolean actionableRisk,
            boolean recoveryEligible,
            TuneMode suggestedTune,
            Metrics metrics) {

        public Assessment {
            pressure = pressure == null ? Pressure.UNKNOWN : pressure;
            reason = reason == null ? Reason.FPS_UNKNOWN : reason;
            suggestedTune = safeTune(suggestedTune);
            metrics = metrics == null ? Metrics.unknown() : metrics;
        }

        static Assessment hold(Reason reason) {
            return new Assessment(
                    Pressure.UNKNOWN,
                    reason,
                    false,
                    false,
                    TuneMode.OFF,
                    Metrics.unknown());
        }
    }
}
