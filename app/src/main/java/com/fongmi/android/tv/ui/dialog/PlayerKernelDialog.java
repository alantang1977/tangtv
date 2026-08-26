package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.Arrays;

public final class PlayerKernelDialog {

    public interface Listener {
        void onSelected(int player);
    }

    public interface ExternalListener {
        void onExternal();
    }

    private PlayerKernelDialog() {
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        show(activity, selected, listener, null);
    }

    public static void show(FragmentActivity activity, int selected, Listener listener, ExternalListener external) {
        int current = PlayerSetting.sanitizePlayer(selected);
        String[] kernel = activity.getResources().getStringArray(R.array.select_player_kernel);
        String[] items = withExternal(kernel, external, activity.getString(R.string.player_kernel_external));
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, items, current, which -> notifySelected(kernel.length, current, which, listener, external));
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        show(fragment, selected, listener, null);
    }

    public static void show(Fragment fragment, int selected, Listener listener, ExternalListener external) {
        int current = PlayerSetting.sanitizePlayer(selected);
        String[] kernel = fragment.getResources().getStringArray(R.array.select_player_kernel);
        String[] items = withExternal(kernel, external, fragment.getString(R.string.player_kernel_external));
        ChoiceDialog.showSingleNoCancel(fragment, R.string.player_kernel, items, current, which -> notifySelected(kernel.length, current, which, listener, external));
    }

    private static String[] withExternal(String[] kernel, ExternalListener external, String label) {
        if (external == null) return kernel;
        String[] items = Arrays.copyOf(kernel, kernel.length + 1);
        items[kernel.length] = label;
        return items;
    }

    private static void notifySelected(int count, int current, int selected, Listener listener, ExternalListener external) {
        if (external != null && selected >= count) {
            external.onExternal();
            return;
        }
        int target = PlayerSetting.sanitizePlayer(selected);
        if (target != current && listener != null) listener.onSelected(target);
    }
}
