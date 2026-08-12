package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.view.WindowCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.utils.Util;
import com.google.android.material.sidesheet.SideSheetDialog;

public abstract class BaseSideSheetDialog extends AppCompatDialogFragment {

    private boolean fullscreen;

    protected abstract ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container);

    protected abstract int getWidth();

    protected boolean edgeToEdgeOnFullscreen() {
        return false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        dialog.getBehavior().setDraggable(false);
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        fullscreen = Util.isFullscreen(getActivity());
        if (fullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            Util.hideSystemUI(window);
            if (edgeToEdgeOnFullscreen()) WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return getBinding(inflater, container).getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView();
        initEvent();
    }

    protected void initView() {
    }

    protected void initEvent() {
    }

    @Override
    public void onStart() {
        super.onStart();
        applyWindow();
        FrameLayout sheet = getDialog().findViewById(com.google.android.material.R.id.m3_side_sheet);
        if (sheet == null) return;
        if (edgeToEdgeOnFullscreen() && fullscreen) {
            if (sheet.getParent() instanceof ViewGroup) ((ViewGroup) sheet.getParent()).setPadding(0, 0, 0, 0);
            sheet.setPadding(0, 0, 0, 0);
        }
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.width = getWidth();
        if (edgeToEdgeOnFullscreen() && fullscreen && params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(0, 0, 0, 0);
        }
        sheet.setLayoutParams(params);
    }

    private void applyWindow() {
        if (!fullscreen || getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Util.hideSystemUI(window);
        if (!edgeToEdgeOnFullscreen()) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }
}
