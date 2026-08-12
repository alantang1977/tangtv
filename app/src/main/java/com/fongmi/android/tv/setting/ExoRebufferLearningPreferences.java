package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** SharedPreferences adapter kept outside the pure learning store. */
final class ExoRebufferLearningPreferences implements ExoRebufferLearningStore.Backend {

    static final String KEY = "perf_exo_rebuffer_learning_v1";

    @Override
    public String read() {
        return Prefers.getString(KEY);
    }

    @Override
    public void write(String value) {
        Prefers.put(KEY, value);
    }

    @Override
    public void clear() {
        Prefers.remove(KEY);
    }
}
