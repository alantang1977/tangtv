package com.fongmi.android.tv.ad.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.player.audio.PlaybackMediaClock;
import com.fongmi.android.tv.player.audio.PlaybackMediaSignalHub;

import org.junit.Test;

import java.util.List;

public class AdAudioRuntimeControllerTest {

    @Test
    public void enabledRulesRequestCaptureOnlyWhileEligibleUiIsBound() {
        PlaybackMediaSignalHub hub = new PlaybackMediaSignalHub(8);
        hub.beginSession(0L);
        FakePlaybackPort playback = new FakePlaybackPort(hub, true);
        AdAudioRuntimeController runtime = runtime(hub, playback, goodSnapshot());

        runtime.start(true);
        assertFalse(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        runtime.bindUi(new FakeUiPort());
        assertTrue(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        assertTrue(runtime.needsPipelineRebuild());

        runtime.unbindUi();
        assertFalse(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        runtime.close();
    }

    @Test
    public void disabledEmptyAndIneligibleConfigurationsNeverCapture() {
        PlaybackMediaSignalHub disabledHub = new PlaybackMediaSignalHub(8);
        disabledHub.beginSession(0L);
        AdAudioRuntimeController disabled = runtime(
                disabledHub, new FakePlaybackPort(disabledHub, true), goodSnapshot());
        disabled.start(false);
        disabled.bindUi(new FakeUiPort());

        PlaybackMediaSignalHub emptyHub = new PlaybackMediaSignalHub(8);
        emptyHub.beginSession(0L);
        AdAudioRuntimeController empty = runtime(
                emptyHub, new FakePlaybackPort(emptyHub, true), emptySnapshot());
        empty.start(true);
        empty.bindUi(new FakeUiPort());

        PlaybackMediaSignalHub ineligibleHub = new PlaybackMediaSignalHub(8);
        ineligibleHub.beginSession(0L);
        AdAudioRuntimeController ineligible = runtime(
                ineligibleHub, new FakePlaybackPort(ineligibleHub, false), goodSnapshot());
        ineligible.start(true);
        ineligible.bindUi(new FakeUiPort());

        assertFalse(disabledHub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        assertFalse(emptyHub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        assertFalse(ineligibleHub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        disabled.close();
        empty.close();
        ineligible.close();
    }

    @Test
    public void refreshReleasesCaptureWhenPlaybackBecomesLive() {
        PlaybackMediaSignalHub hub = new PlaybackMediaSignalHub(8);
        hub.beginSession(0L);
        FakePlaybackPort playback = new FakePlaybackPort(hub, true);
        AdAudioRuntimeController runtime = runtime(hub, playback, goodSnapshot());
        runtime.start(true);
        runtime.bindUi(new FakeUiPort());
        assertTrue(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));

        playback.eligible = false;
        runtime.refresh();

        assertFalse(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        runtime.close();
    }

    @Test
    public void ruleSnapshotErrorIsReportedAndCaptureStaysDisabled() {
        PlaybackMediaSignalHub hub = new PlaybackMediaSignalHub(8);
        hub.beginSession(0L);
        AdAudioRuntimeController runtime = runtime(
                hub, new FakePlaybackPort(hub, true), errorSnapshot());

        runtime.start(true);
        runtime.bindUi(new FakeUiPort());

        assertEquals(1L, runtime.diagnostics().count(
                AdAudioDiagnostics.Code.RULE_LOAD_FAILED));
        assertFalse(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        runtime.close();
    }

    @Test
    public void nullRuleSnapshotIsReportedAndCaptureStaysDisabled() {
        PlaybackMediaSignalHub hub = new PlaybackMediaSignalHub(8);
        hub.beginSession(0L);
        FakePlaybackPort playback = new FakePlaybackPort(hub, true);
        AdAudioRuntimeController runtime = new AdAudioRuntimeController(
                hub, new PlaybackMediaClock(500L), () -> null, playback,
                Runnable::run, () -> { });

        runtime.start(true);
        runtime.bindUi(new FakeUiPort());

        assertEquals(1L, runtime.diagnostics().count(
                AdAudioDiagnostics.Code.RULE_LOAD_FAILED));
        assertFalse(hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO));
        runtime.close();
    }

    @Test
    public void reloadRulesReplacesTheActiveMatcher() {
        PlaybackMediaSignalHub hub = new PlaybackMediaSignalHub(8);
        PlaybackMediaSignalHub.Session firstSession = hub.beginSession(0L);
        FakePlaybackPort playback = new FakePlaybackPort(hub, true);
        MutableRuleSource rules = new MutableRuleSource(snapshotForRule("old-ad"));
        AdAudioRuntimeController runtime = new AdAudioRuntimeController(
                hub, new PlaybackMediaClock(500L), rules, playback,
                Runnable::run, () -> { });
        FakeUiPort ui = new FakeUiPort();
        runtime.start(true);
        runtime.bindUi(ui);

        rules.snapshot = snapshotForRule("new-ad");
        runtime.reloadRules();
        PlaybackMediaSignalHub.Session nextSession = hub.beginSession(0L);
        hub.publishPcm(nextSession.frame(chirp16k(), 16_000, 0L));

        assertEquals("new-ad", ui.lastPrompt.ruleId());
        assertTrue(firstSession.id() != nextSession.id());
        runtime.close();
    }

    @Test
    public void repeatedStartReplacesTheActiveMatcher() {
        PlaybackMediaSignalHub hub = new PlaybackMediaSignalHub(8);
        hub.beginSession(0L);
        FakePlaybackPort playback = new FakePlaybackPort(hub, true);
        MutableRuleSource rules = new MutableRuleSource(snapshotForRule("old-ad"));
        AdAudioRuntimeController runtime = new AdAudioRuntimeController(
                hub, new PlaybackMediaClock(500L), rules, playback,
                Runnable::run, () -> { });
        FakeUiPort ui = new FakeUiPort();
        runtime.start(true);
        runtime.bindUi(ui);

        rules.snapshot = snapshotForRule("new-ad");
        runtime.start(true);
        PlaybackMediaSignalHub.Session session = hub.beginSession(0L);
        hub.publishPcm(session.frame(chirp16k(), 16_000, 0L));

        assertEquals("new-ad", ui.lastPrompt.ruleId());
        runtime.close();
    }

    private static AdAudioRuntimeController runtime(
            PlaybackMediaSignalHub hub, FakePlaybackPort playback, AdAudioRuleStore.Snapshot snapshot) {
        return new AdAudioRuntimeController(
                hub, new PlaybackMediaClock(500L), () -> snapshot, playback,
                Runnable::run, () -> { });
    }

    private static AdAudioRuleStore.Snapshot goodSnapshot() {
        return snapshotForRule("ad");
    }

    private static AdAudioRuleStore.Snapshot snapshotForRule(String ruleId) {
        AudioFingerprintRuleSet rules = AudioFingerprintRuleCodec.fromJson("{"
                + "\"schemaVersion\":2,\"algorithm\":{"
                + "\"id\":\"spectral-sequence-v2\",\"sampleRate\":16000,"
                + "\"windowMs\":512,\"hopMs\":256,\"bandCount\":16},"
                + "\"rules\":[{\"id\":\"" + ruleId + "\",\"durationMs\":10000,"
                + "\"anchorOffsetMs\":0,\"anchorDurationMs\":3000,"
                + "\"fingerprint\":[\"32f0007c\",\"35c100e0\",\"3b8b01c0\",\"d30a0380\"]}]}" );
        return new AdAudioRuleStore.Snapshot("test", "v1", rules, List.of(), "");
    }

    private static AdAudioRuleStore.Snapshot emptySnapshot() {
        return new AdAudioRuleStore.Snapshot(
                "test", "", AudioFingerprintRuleSet.empty(), List.of(), "");
    }

    private static AdAudioRuleStore.Snapshot errorSnapshot() {
        return new AdAudioRuleStore.Snapshot(
                "test", "", AudioFingerprintRuleSet.empty(), List.of(), "INVALID_JSON");
    }

    private static float[] chirp16k() {
        int sampleRate = 16_000;
        int seconds = 3;
        float[] output = new float[sampleRate * seconds];
        double startFrequency = 300;
        double endFrequency = 3_000;
        double rate = (endFrequency - startFrequency) / seconds;
        for (int index = 0; index < output.length; index++) {
            double time = index / (double) sampleRate;
            double phase = 2.0 * Math.PI * (startFrequency * time + 0.5 * rate * time * time);
            short pcm = (short) Math.round(Math.sin(phase) * Short.MAX_VALUE * 0.8);
            output[index] = pcm / (float) Short.MAX_VALUE;
        }
        return output;
    }

    private static final class MutableRuleSource implements AdAudioRuntimeController.RuleSource {
        private AdAudioRuleStore.Snapshot snapshot;

        MutableRuleSource(AdAudioRuleStore.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public AdAudioRuleStore.Snapshot load() {
            return snapshot;
        }
    }

    private static final class FakePlaybackPort implements AdAudioRuntimeController.PlaybackPort {
        private final PlaybackMediaSignalHub hub;
        private boolean eligible;

        FakePlaybackPort(PlaybackMediaSignalHub hub, boolean eligible) {
            this.hub = hub;
            this.eligible = eligible;
        }

        @Override
        public boolean isEligible(long sessionId, long generation) {
            return eligible;
        }

        @Override
        public AdSkipCoordinator.PlaybackSnapshot snapshot(long sessionId, long generation) {
            return new AdSkipCoordinator.PlaybackSnapshot(
                    sessionId, generation, 1_000L, 100_000L, eligible, !eligible,
                    new PlaybackMediaClock.Snapshot(generation, 0L, 50_000L, 1_000L, true, true));
        }

        @Override
        public AdSkipCoordinator.SeekResult seekTo(long sessionId, long generation, long positionMs) {
            PlaybackMediaSignalHub.Session session = hub.session();
            boolean applied = eligible && session.id() == sessionId && session.generation() == generation;
            return new AdSkipCoordinator.SeekResult(applied, session.id(), session.generation());
        }
    }

    private static final class FakeUiPort implements AdSkipCoordinator.UiPort {
        private AdSkipCoordinator.Prompt lastPrompt;

        @Override
        public void showCandidate(AdSkipCoordinator.Prompt prompt, AdSkipCoordinator.Actions actions) {
            lastPrompt = prompt;
        }

        @Override
        public void showUndo(AdSkipCoordinator.UndoPrompt prompt, AdSkipCoordinator.Actions actions) {
        }

        @Override
        public void dismiss(long sessionId) {
        }
    }
}
