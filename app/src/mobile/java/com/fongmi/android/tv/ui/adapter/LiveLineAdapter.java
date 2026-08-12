package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.databinding.AdapterLiveLineBinding;
import com.fongmi.android.tv.utils.ResUtil;

public class LiveLineAdapter extends RecyclerView.Adapter<LiveLineAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final Channel channel;

    public LiveLineAdapter(OnClickListener listener, Channel channel) {
        this.listener = listener;
        this.channel = channel;
    }

    public interface OnClickListener {

        void onLineClick(int position);
    }

    @Override
    public int getItemCount() {
        return channel.getUrls().size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLiveLineBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(getName(position));
        holder.binding.text.setSelected(channel.getIndex() == position);
        holder.binding.text.setOnClickListener(view -> listener.onLineClick(position));
    }

    private String getName(int position) {
        String[] split = channel.getUrls().get(position).split("\\$");
        if (split.length > 1 && !split[1].isEmpty()) return split[1];
        return ResUtil.getString(com.fongmi.android.tv.R.string.live_line, position + 1);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLiveLineBinding binding;

        ViewHolder(@NonNull AdapterLiveLineBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
