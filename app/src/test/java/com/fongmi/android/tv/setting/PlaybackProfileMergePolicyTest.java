package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackProfileMergePolicyTest {

    @Test
    public void missingStateDefaultsToVersionedMerge() {
        PlaybackProfileMergePolicy.Resolution resolution =
                PlaybackProfileMergePolicy.resolve(null);

        assertEquals(PlaybackProfileMergePolicy.Status.MISSING,
                resolution.status());
        assertTrue(resolution.writeBack());
        assertTrue(resolution.sourceValid());
        assertTrue(resolution.mergeEnabled());
    }

    @Test
    public void corruptAndFutureStateFailSafeToLegacyEntry() {
        PlaybackProfileMergePolicy.Resolution corrupt =
                PlaybackProfileMergePolicy.resolve(
                        new PlaybackProfileMergePolicy.RawState(
                                1, "bad", 0));
        PlaybackProfileMergePolicy.Resolution future =
                PlaybackProfileMergePolicy.resolve(
                        new PlaybackProfileMergePolicy.RawState(
                                2, false, 0));

        assertEquals(PlaybackProfileMergePolicy.Status.CORRUPT,
                corrupt.status());
        assertTrue(corrupt.writeBack());
        assertFalse(corrupt.mergeEnabled());
        assertEquals(PlaybackProfileMergePolicy.Status.FUTURE_SCHEMA,
                future.status());
        assertFalse(future.writeBack());
        assertFalse(future.mutable());
        assertFalse(future.mergeEnabled());
    }

    @Test
    public void mergedModePreservesDevProfilesAndDefaultsUnknownToAuto() {
        assertEquals(PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                PlaybackProfileMergePolicy.effectiveProfile(
                        PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                        true));
        assertEquals(PlaybackPerformanceSetting.PROFILE_AUTO,
                PlaybackProfileMergePolicy.effectiveProfile(99, true));
        assertEquals(PlaybackPerformanceSetting.PROFILE_CUSTOM,
                PlaybackProfileMergePolicy.effectiveProfile(
                        PlaybackPerformanceSetting.PROFILE_CUSTOM,
                        true));
        assertEquals(PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                PlaybackProfileMergePolicy.effectiveProfile(
                        PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                        true));
    }

    @Test
    public void legacyMergeStateAlsoPreservesDevProfiles() {
        assertEquals(PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                PlaybackProfileMergePolicy.effectiveProfile(
                        PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                        false));
        assertEquals(PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                PlaybackProfileMergePolicy.effectiveProfile(
                        PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                        false));
        assertEquals(PlaybackPerformanceSetting.PROFILE_AUTO,
                PlaybackProfileMergePolicy.effectiveProfile(99, false));
    }

    @Test
    public void consolidationRefreshesOnlyLegacyFixedBaselines() {
        assertEquals(PlaybackProfileMergePolicy.ConsolidationAction.APPLY_AUTO,
                PlaybackProfileMergePolicy.consolidationAction(
                        PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertEquals(PlaybackProfileMergePolicy.ConsolidationAction.APPLY_LIGHTWEIGHT,
                PlaybackProfileMergePolicy.consolidationAction(
                        PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
        assertEquals(PlaybackProfileMergePolicy.ConsolidationAction.APPLY_LIGHTWEIGHT,
                PlaybackProfileMergePolicy.consolidationAction(
                        PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
        assertEquals(PlaybackProfileMergePolicy.ConsolidationAction.KEEP,
                PlaybackProfileMergePolicy.consolidationAction(
                        PlaybackPerformanceSetting.PROFILE_AUTO));
        assertEquals(PlaybackProfileMergePolicy.ConsolidationAction.KEEP,
                PlaybackProfileMergePolicy.consolidationAction(
                        PlaybackPerformanceSetting.PROFILE_CUSTOM));
    }

    @Test
    public void profileListKeepsAllDevPresets() {
        assertArrayEquals(new int[]{
                        PlaybackPerformanceSetting.PROFILE_AUTO,
                        PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                        PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                        PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT,
                        PlaybackPerformanceSetting.PROFILE_ORIGINAL},
                PlaybackProfileMergePolicy.selectableProfiles(true));
        assertArrayEquals(new int[]{
                        PlaybackPerformanceSetting.PROFILE_AUTO,
                        PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                        PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                        PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT,
                        PlaybackPerformanceSetting.PROFILE_ORIGINAL},
                PlaybackProfileMergePolicy.selectableProfiles(false));
        assertEquals(2, PlaybackProfileMergePolicy.positionOf(
                PlaybackPerformanceSetting.PROFILE_COMPATIBLE, true));
        assertEquals(-1, PlaybackProfileMergePolicy.positionOf(
                PlaybackPerformanceSetting.PROFILE_CUSTOM, true));
    }

    @Test
    public void rollbackRestoresOnlySlotsThatWereMigratedAndRemainAuto() {
        PlaybackProfileMergePolicy.State state =
                PlaybackProfileMergePolicy.State.merged()
                        .withMigrated(PlaybackProfileMergePolicy.Slot.EXO)
                        .withRolledBack(true);

        assertTrue(PlaybackProfileMergePolicy.shouldRestore(
                state,
                PlaybackProfileMergePolicy.Slot.EXO,
                PlaybackPerformanceSetting.PROFILE_AUTO));
        assertFalse(PlaybackProfileMergePolicy.shouldRestore(
                state,
                PlaybackProfileMergePolicy.Slot.MPV,
                PlaybackPerformanceSetting.PROFILE_AUTO));
        assertFalse(PlaybackProfileMergePolicy.shouldRestore(
                state,
                PlaybackProfileMergePolicy.Slot.EXO,
                PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
    }

    @Test
    public void migrationMarkerIsStableAcrossRepeatedMerge() {
        PlaybackProfileMergePolicy.State state =
                PlaybackProfileMergePolicy.State.merged()
                        .withMigrated(PlaybackProfileMergePolicy.Slot.IJK)
                        .withMigrated(PlaybackProfileMergePolicy.Slot.IJK);

        assertTrue(state.wasMigrated(
                PlaybackProfileMergePolicy.Slot.IJK));
        assertEquals(1 << 2, state.migratedMask());
        PlaybackProfileMergePolicy.State completed =
                state.withoutMigrated(PlaybackProfileMergePolicy.Slot.IJK);
        assertFalse(completed.wasMigrated(
                PlaybackProfileMergePolicy.Slot.IJK));
        assertEquals(0, completed.migratedMask());
        assertTrue(PlaybackProfileMergePolicy.shouldMigrate(
                PlaybackPerformanceSetting.PROFILE_RECOMMENDED,
                true));
        assertFalse(PlaybackProfileMergePolicy.shouldMigrate(
                PlaybackPerformanceSetting.PROFILE_AUTO,
                true));
    }
}
