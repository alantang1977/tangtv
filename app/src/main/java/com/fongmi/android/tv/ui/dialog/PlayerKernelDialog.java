package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

public final class PlayerKernelDialog {

    public interface Listener {
        void onSelected(int player);
    }

    private PlayerKernelDialog() {
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, activity.getResources().getStringArray(R.array.select_player_kernel), current, which -> notifySelected(current, which, listener));
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        ChoiceDialog.showSingleNoCancel(fragment, R.string.player_kernel, fragment.getResources().getStringArray(R.array.select_player_kernel), current, which -> notifySelected(current, which, listener));
    }

    private static void notifySelected(int current, int selected, Listener listener) {
        int target = PlayerSetting.sanitizePlayer(selected);
        if (target != current && listener != null) listener.onSelected(target);
    }
}
