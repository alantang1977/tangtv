package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.databinding.AdapterTypeDialogBinding;

import java.util.List;

public class TypeDialogAdapter extends RecyclerView.Adapter<TypeDialogAdapter.ViewHolder> {

    private final TypeAdapter.OnClickListener listener;
    private final List<Class> mItems;

    public TypeDialogAdapter(TypeAdapter.OnClickListener listener, List<Class> items) {
        this.listener = listener;
        this.mItems = items;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterTypeDialogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Class item = mItems.get(position);
        holder.binding.text.setText(item.getTypeName());
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(position, item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterTypeDialogBinding binding;

        ViewHolder(@NonNull AdapterTypeDialogBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
