package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.AdapterSearchHotWordBinding;

public class HotWordAdapter extends BaseDiffAdapter<Word.Data, HotWordAdapter.ViewHolder> {

    private final WordAdapter.OnClickListener listener;

    public HotWordAdapter(WordAdapter.OnClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchHotWordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Word.Data item = getItem(position);
        holder.binding.text.setText(item.getTitle());
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(item.getTitle()));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchHotWordBinding binding;

        public ViewHolder(@NonNull AdapterSearchHotWordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
