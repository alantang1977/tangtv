package com.fongmi.android.tv.player.exo;

import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.media3.common.Format;

import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;

/** Applies and restores EXO display modes at the Activity/Window boundary. */
public final class ExoOutputModeManager {

    private static final int INVALID_MODE_ID = 0;

    private final Window window;
    private int originalModeId = INVALID_MODE_ID;
    private int requestedModeId = INVALID_MODE_ID;

    public ExoOutputModeManager(Window window) {
        this.window = window;
    }

    public Result apply(Format format) {
        DisplaySnapshot display = readDisplay();
        ExoOutputModePolicy.Mode current = display.current();
        int setting = ExoPerformanceSetting.getFrameRateMode();
        if (setting == ExoPerformanceSetting.FRAME_RATE_OFF) return Result.skipped("disabled", current);
        if (format == null || format.frameRate <= 0 || format.width <= 0 || format.height <= 0) return Result.skipped("unknown-format", current);
        if (current == null || display.supported().isEmpty()) return Result.skipped(display.reason(), current);
        if (originalModeId == INVALID_MODE_ID) originalModeId = current.id();
        ExoOutputModePolicy.Policy policy = setting == ExoPerformanceSetting.FRAME_RATE_RESOLUTION_AND_RATE
                ? ExoOutputModePolicy.Policy.resolutionAndRate()
                : ExoOutputModePolicy.Policy.frameRateOnly();
        ExoOutputModePolicy.Decision decision = ExoOutputModePolicy.select(display.supported(), current.id(), ExoOutputModePolicy.Content.of(format.width, format.height, format.frameRate), policy);
        ExoOutputModePolicy.Mode selected = decision.mode();
        if (selected == null) return Result.skipped(decision.reason(), current);
        if (setting == ExoPerformanceSetting.FRAME_RATE_SEAMLESS) return new Result(decision, current, selected, false, "seamless-delegated");
        if (!decision.changeRequired() || selected.id() == requestedModeId) return new Result(decision, current, selected, false, "already-selected");
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.preferredDisplayModeId = selected.id();
        window.setAttributes(attributes);
        requestedModeId = selected.id();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-output", "requested mode=%dx%d@%.3fHz id=%d content=%dx%d@%.3fHz policy=%s", selected.width(), selected.height(), selected.refreshRateMilliHz() / 1000f, selected.id(), format.width, format.height, format.frameRate, ExoPerformanceSetting.getFrameRateText());
        return new Result(decision, current, selected, true, "requested");
    }

    public Result observe(String reason) {
        DisplaySnapshot display = readDisplay();
        return Result.skipped(reason == null ? display.reason() : reason, display.current());
    }

    public Result restore() {
        DisplaySnapshot display = readDisplay();
        if (window == null || originalModeId == INVALID_MODE_ID || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Result.skipped("restore-not-needed", display.current());
        }
        ExoOutputModePolicy.Mode restored = findMode(display.supported(), originalModeId);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.preferredDisplayModeId = originalModeId;
        window.setAttributes(attributes);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-output", "restored original mode id=%d", originalModeId);
        requestedModeId = INVALID_MODE_ID;
        originalModeId = INVALID_MODE_ID;
        return new Result(new ExoOutputModePolicy.Decision(restored, true, "restore"),
                display.current(), restored, true, "restore-requested");
    }

    private static List<ExoOutputModePolicy.Mode> toModes(Display.Mode[] modes) {
        List<ExoOutputModePolicy.Mode> result = new ArrayList<>(modes.length);
        for (Display.Mode mode : modes) result.add(ExoOutputModePolicy.Mode.of(mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate()));
        return result;
    }

    private DisplaySnapshot readDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return new DisplaySnapshot(null, List.of(), "unsupported-api");
        Display display = window == null ? null : window.getWindowManager().getDefaultDisplay();
        if (display == null) return new DisplaySnapshot(null, List.of(), "no-display");
        Display.Mode current = display.getMode();
        Display.Mode[] supported = display.getSupportedModes();
        if (current == null || supported == null || supported.length == 0) return new DisplaySnapshot(null, List.of(), "no-modes");
        return new DisplaySnapshot(
                ExoOutputModePolicy.Mode.of(current.getModeId(), current.getPhysicalWidth(), current.getPhysicalHeight(), current.getRefreshRate()),
                toModes(supported),
                "observed");
    }

    private static ExoOutputModePolicy.Mode findMode(List<ExoOutputModePolicy.Mode> modes, int id) {
        for (ExoOutputModePolicy.Mode mode : modes) if (mode.id() == id) return mode;
        return null;
    }

    private record DisplaySnapshot(ExoOutputModePolicy.Mode current, List<ExoOutputModePolicy.Mode> supported, String reason) {
    }

    public record Result(
            ExoOutputModePolicy.Decision decision,
            ExoOutputModePolicy.Mode currentMode,
            ExoOutputModePolicy.Mode requestedMode,
            boolean applied,
            String reason) {
        private static Result skipped(String reason, ExoOutputModePolicy.Mode currentMode) {
            return new Result(new ExoOutputModePolicy.Decision(null, false, reason), currentMode, null, false, reason);
        }
    }
}
