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
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.databinding.DialogTypeBinding;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.adapter.TypeDialogAdapter;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayoutManager;

import java.util.List;

public class TypeDialog extends BaseBottomSheetDialog implements TypeAdapter.OnClickListener {

    private DialogTypeBinding binding;
    private TypeAdapter.OnClickListener listener;
    private List<Class> items;

    public static TypeDialog create() {
        return new TypeDialog();
    }

    public TypeDialog items(List<Class> items) {
        this.items = items;
        return this;
    }

    public void show(Fragment fragment) {
        for (Fragment child : fragment.getChildFragmentManager().getFragments()) if (child instanceof TypeDialog) return;
        this.listener = (TypeAdapter.OnClickListener) fragment;
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTypeBinding.inflate(inflater, container, false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog.getWindow());
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) configureWindow(getDialog().getWindow());
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
    protected void setBehavior(BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        configureWindow(dialog.getWindow());
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    @Override
    protected void initView() {
        int ratio = ResUtil.isLand(requireContext()) ? 46 : 58;
        binding.recycler.setMaxHeight(ResUtil.getScreenHeight(requireContext()) * ratio / 100);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setItemAnimator(null);
        FlexboxLayoutManager manager = new FlexboxLayoutManager(requireContext(), FlexDirection.ROW);
        manager.setFlexWrap(FlexWrap.WRAP);
        binding.recycler.setLayoutManager(manager);
        binding.recycler.setAdapter(new TypeDialogAdapter(this, items));
    }

    @Override
    public void onItemClick(int position, Class item) {
        dismiss();
        listener.onItemClick(position, item);
    }

    private void configureWindow(Window window) {
        if (window == null) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0f;
        window.setAttributes(params);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, true);
    }
}
