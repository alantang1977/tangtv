package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import android.text.TextUtils;
import android.view.ViewGroup;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

public class EpisodeGridHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeGridBinding binding;
    private final int maxSingleWidth;
    private final int horizontalPadding;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        this.maxSingleWidth = ResUtil.getScreenWidth();
        this.horizontalPadding = ResUtil.dp2px(12);
    }

    @Override
    public void initView(Episode item) {
        updateLayout();
        binding.text.setActivated(item.isSelected());
        binding.text.setHorizontallyScrolling(true);
        binding.text.setText(item.getDisplayName());
        setMarquee(binding.text.hasFocus() || item.isSelected());
        binding.text.setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus || binding.text.isActivated()));
        binding.text.setOnClickListener(v -> {
            listener.onItemClick(item);
        });
        binding.text.post(() -> setMarquee(binding.text.hasFocus() || binding.text.isActivated()));
    }

    private void updateLayout() {
        boolean single = getBindingAdapter() != null && getBindingAdapter().getItemCount() == 1;
        ViewGroup.LayoutParams params = binding.text.getLayoutParams();
        int width = single ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        if (params.width != width) {
            params.width = width;
            binding.text.setLayoutParams(params);
        }
        binding.text.setMaxWidth(single ? maxSingleWidth : Integer.MAX_VALUE);
        binding.text.setPadding(horizontalPadding, 0, horizontalPadding, 0);
    }

    private void setMarquee(boolean focused) {
        binding.text.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.START);
        binding.text.setSelected(focused);
    }
}
