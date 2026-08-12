package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterFileBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<File> mItems;
    private boolean selectDir;
    private File dir;

    public FileAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(File dir, List<File> items, boolean selectDir) {
        this.dir = dir;
        this.selectDir = selectDir;
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void addAll(List<File> items) {
        addAll(null, items, false);
    }

    @Override
    public int getItemCount() {
        return mItems.size() + (selectDir ? 1 : 0);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (selectDir && position == 0) {
            holder.binding.name.setText(R.string.lut_select_current_dir);
            holder.binding.image.setImageResource(R.drawable.ic_folder);
            holder.binding.getRoot().setOnClickListener(v -> mListener.onCurrentDirClick(dir));
            return;
        }
        File file = mItems.get(selectDir ? position - 1 : position);
        holder.binding.name.setText(file.getName());
        holder.binding.image.setImageResource(file.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(file));
    }

    public interface OnClickListener {

        void onItemClick(File file);

        void onCurrentDirClick(File dir);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFileBinding binding;

        ViewHolder(@NonNull AdapterFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
