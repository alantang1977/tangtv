package com.fongmi.android.tv.player.engine;

import com.fongmi.android.tv.player.ijk.IjkBufferPolicy;

/** Pure prepare-time option selection; staging never mutates a running IJK context. */
final class IjkBufferOptionPolicy {

    private IjkBufferOptionPolicy() {
    }

    static Decision resolve(
            boolean automatic,
            IjkBufferPolicy.Config stagedAutomatic,
            String url,
            int scene,
            int fixedBufferMb,
            long configuredMaxBufferBytes,
            int fixedFirstWaterMs,
            int fixedNextWaterMs,
            int fixedLastWaterMs) {
        IjkBufferPolicy.Config selected = automatic
                ? stagedAutomatic == null
                ? IjkBufferPolicy.safeInitialConfig() : stagedAutomatic
                : new IjkBufferPolicy.Config(
                fixedBufferMb, fixedFirstWaterMs,
                fixedNextWaterMs, fixedLastWaterMs);
        IjkInputBufferPolicy.Decision finite = IjkInputBufferPolicy.resolve(
                url, scene, selected.bufferMb(), configuredMaxBufferBytes);
        IjkBufferPolicy.Config applied = new IjkBufferPolicy.Config(
                finite.bufferMb(), selected.firstWaterMs(),
                selected.nextWaterMs(), selected.lastWaterMs());
        return new Decision(
                applied, finite.maxBufferBytes(), finite.realtime(),
                finite.infiniteBuffer());
    }

    record Decision(
            IjkBufferPolicy.Config config,
            long maxBufferBytes,
            boolean realtime,
            boolean infiniteBuffer) {

        Decision {
            config = config == null
                    ? IjkBufferPolicy.safeInitialConfig() : config;
            maxBufferBytes = Math.max(0, maxBufferBytes);
        }
    }
}
