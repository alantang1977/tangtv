package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.databinding.DialogLiveLineBinding;
import com.fongmi.android.tv.ui.adapter.LiveLineAdapter;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class LiveLineDialog extends BaseBottomSheetDialog implements LiveLineAdapter.OnClickListener {

    private DialogLiveLineBinding binding;
    private LiveLineAdapter adapter;
    private Channel channel;
    private Listener listener;

    public static LiveLineDialog create() {
        return new LiveLineDialog();
    }

    public LiveLineDialog channel(Channel channel) {
        this.channel = channel;
        return this;
    }

    public LiveLineDialog listener(Listener listener) {
        this.listener = listener;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof LiveLineDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog());
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected boolean stableOverlay() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogLiveLineBinding.inflate(inflater, container, false);
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
    }

    @Override
    protected void initView() {
        if (channel == null) return;
        int ratio = ResUtil.isLand(requireContext()) ? 46 : 58;
        binding.title.setText(channel.getShow());
        binding.recycler.setMaxHeight(ResUtil.getScreenHeight(requireContext()) * ratio / 100);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setItemAnimator(null);
        FlexboxLayoutManager manager = new FlexboxLayoutManager(requireContext(), FlexDirection.ROW);
        manager.setFlexWrap(FlexWrap.WRAP);
        binding.recycler.setLayoutManager(manager);
        binding.recycler.setAdapter(adapter = new LiveLineAdapter(this, channel));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(Math.max(channel.getIndex(), 0)));
    }

    @Override
    public void onLineClick(int position) {
        if (listener != null) listener.onLineSelected(position);
        if (adapter != null) adapter.notifyItemRangeChanged(0, adapter.getItemCount());
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setDimAmount(0f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, true);
    }

    public interface Listener {

        void onLineSelected(int position);
    }
}
