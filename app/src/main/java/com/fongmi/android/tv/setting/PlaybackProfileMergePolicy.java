package com.fongmi.android.tv.setting;

/** Pure, versioned policy for consolidating legacy playback profiles. */
public final class PlaybackProfileMergePolicy {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int ALL_MIGRATED_MASK = 0b111;

    private static final int[] CONSOLIDATED_PROFILES = {
            PlaybackPerformanceSetting.PROFILE_AUTO,
            PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT
    };

    private PlaybackProfileMergePolicy() {
    }

    public static Resolution resolve(RawState rawState) {
        RawState raw = rawState == null ? RawState.missing() : rawState;
        if (raw.schemaVersion() == null
                && raw.rolledBack() == null
                && raw.migratedMask() == null) {
            return new Resolution(
                    State.merged(), Status.MISSING, true, true);
        }
        Integer version = integer(raw.schemaVersion());
        if (version == null || version < 0) {
            return Resolution.corrupt();
        }
        if (version > CURRENT_SCHEMA_VERSION) {
            return new Resolution(
                    State.legacyRollback(),
                    Status.FUTURE_SCHEMA,
                    false,
                    false);
        }
        Integer migratedMask = integer(raw.migratedMask());
        if (version != CURRENT_SCHEMA_VERSION
                || !(raw.rolledBack() instanceof Boolean rolledBack)
                || migratedMask == null
                || migratedMask < 0
                || migratedMask > ALL_MIGRATED_MASK) {
            return Resolution.corrupt();
        }
        return new Resolution(
                new State(version, rolledBack, migratedMask),
                Status.CURRENT,
                false,
                true);
    }

    public static int effectiveProfile(int rawProfile, boolean mergeEnabled) {
        return switch (consolidationAction(rawProfile)) {
            case APPLY_AUTO -> PlaybackPerformanceSetting.PROFILE_AUTO;
            case APPLY_LIGHTWEIGHT ->
                    PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT;
            case KEEP -> rawProfile;
        };
    }

    public static ConsolidationAction consolidationAction(int rawProfile) {
        return switch (rawProfile) {
            case PlaybackPerformanceSetting.PROFILE_RECOMMENDED ->
                    ConsolidationAction.APPLY_AUTO;
            case PlaybackPerformanceSetting.PROFILE_COMPATIBLE,
                 PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT ->
                    ConsolidationAction.APPLY_LIGHTWEIGHT;
            case PlaybackPerformanceSetting.PROFILE_CUSTOM,
                 PlaybackPerformanceSetting.PROFILE_AUTO ->
                    ConsolidationAction.KEEP;
            default -> ConsolidationAction.APPLY_AUTO;
        };
    }

    public static boolean shouldMigrate(
            int rawProfile,
            boolean mergeEnabled) {
        return mergeEnabled
                && rawProfile
                == PlaybackPerformanceSetting.PROFILE_RECOMMENDED;
    }

    public static boolean shouldRestore(
            State state,
            Slot slot,
            int currentRawProfile) {
        State safe = state == null ? State.legacyRollback() : state;
        return safe.rolledBack()
                && safe.wasMigrated(slot)
                && currentRawProfile
                == PlaybackPerformanceSetting.PROFILE_AUTO;
    }

    public static int[] selectableProfiles(boolean mergeEnabled) {
        return CONSOLIDATED_PROFILES.clone();
    }

    public static int positionOf(int profile, boolean mergeEnabled) {
        int effective = effectiveProfile(profile, mergeEnabled);
        for (int index = 0; index < CONSOLIDATED_PROFILES.length; index++) {
            if (CONSOLIDATED_PROFILES[index] == effective) return index;
        }
        return -1;
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) return null;
        long result = ((Number) value).longValue();
        return result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                ? null : (int) result;
    }

    public enum Status {
        MISSING,
        CURRENT,
        CORRUPT,
        FUTURE_SCHEMA
    }

    public enum ConsolidationAction {
        KEEP,
        APPLY_AUTO,
        APPLY_LIGHTWEIGHT
    }

    public enum Slot {
        EXO(1),
        MPV(1 << 1),
        IJK(1 << 2);

        private final int bit;

        Slot(int bit) {
            this.bit = bit;
        }
    }

    public record RawState(
            Object schemaVersion,
            Object rolledBack,
            Object migratedMask) {

        public static RawState missing() {
            return new RawState(null, null, null);
        }
    }

    public record State(
            int schemaVersion,
            boolean rolledBack,
            int migratedMask) {

        public State {
            migratedMask = Math.max(0,
                    Math.min(ALL_MIGRATED_MASK, migratedMask));
        }

        public static State merged() {
            return new State(CURRENT_SCHEMA_VERSION, false, 0);
        }

        public static State legacyRollback() {
            return new State(CURRENT_SCHEMA_VERSION, true, 0);
        }

        public boolean mergeEnabled() {
            return schemaVersion == CURRENT_SCHEMA_VERSION
                    && !rolledBack;
        }

        public boolean wasMigrated(Slot slot) {
            return slot != null && (migratedMask & slot.bit) != 0;
        }

        public State withMigrated(Slot slot) {
            if (slot == null) return this;
            return new State(
                    CURRENT_SCHEMA_VERSION,
                    rolledBack,
                    migratedMask | slot.bit);
        }

        public State withoutMigrated(Slot slot) {
            if (slot == null) return this;
            return new State(
                    CURRENT_SCHEMA_VERSION,
                    rolledBack,
                    migratedMask & ~slot.bit);
        }

        public State withRolledBack(boolean value) {
            return new State(
                    CURRENT_SCHEMA_VERSION,
                    value,
                    migratedMask);
        }
    }

    public record Resolution(
            State state,
            Status status,
            boolean writeBack,
            boolean sourceValid) {

        public Resolution {
            state = state == null ? State.legacyRollback() : state;
            status = status == null ? Status.CORRUPT : status;
        }

        private static Resolution corrupt() {
            return new Resolution(
                    State.legacyRollback(),
                    Status.CORRUPT,
                    true,
                    false);
        }

        public boolean mergeEnabled() {
            return sourceValid && state.mergeEnabled();
        }

        public boolean mutable() {
            return status != Status.FUTURE_SCHEMA;
        }
    }
}
