package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterFileBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private static final int WINDOW_SIZE = 128;
    private static final int WINDOW_STEP = 32;
    private static final int WINDOW_EDGE = 12;
    private static final int WINDOW_MAX = WINDOW_SIZE + WINDOW_STEP;

    private final OnClickListener listener;
    private final List<File> mItems;
    private boolean selectDir;
    private File dir;
    private int visibleStart;
    private int visibleEnd;
    private boolean windowUpdatePending;

    public FileAdapter(OnClickListener listener) {
        this.mItems = new ArrayList<>();
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(File file);

        void onCurrentDirClick(File dir);
    }

    public void addAll(File dir, List<File> items, boolean selectDir) {
        this.dir = dir;
        this.selectDir = selectDir;
        mItems.clear();
        mItems.addAll(items);
        setWindowAround(0);
        notifyDataSetChanged();
    }

    public void addAll(List<File> items) {
        addAll(null, items, false);
    }

    @Override
    public int getItemCount() {
        return visibleEnd - visibleStart;
    }

    public void scheduleWindowUpdate(RecyclerView recyclerView) {
        if (windowUpdatePending) return;
        windowUpdatePending = true;
        recyclerView.post(() -> {
            windowUpdatePending = false;
            expandWindow(recyclerView);
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int itemPosition = visibleStart + position;
        if (selectDir && itemPosition == 0) {
            holder.binding.name.setText(R.string.lut_select_current_dir);
            holder.binding.getRoot().setOnClickListener(v -> listener.onCurrentDirClick(dir));
            holder.binding.image.setImageResource(R.drawable.ic_folder);
            return;
        }
        File file = mItems.get(selectDir ? itemPosition - 1 : itemPosition);
        holder.binding.name.setText(file.getName());
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(file));
        holder.binding.image.setImageResource(file.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
    }

    private void expandWindow(RecyclerView recyclerView) {
        if (recyclerView.isComputingLayout()) {
            scheduleWindowUpdate(recyclerView);
            return;
        }
        if (!(recyclerView.getLayoutManager() instanceof LinearLayoutManager manager)) return;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;
        int oldStart = visibleStart;
        int oldEnd = visibleEnd;
        int top = getTop(manager, first);
        int newStart = visibleStart;
        int newEnd = visibleEnd;
        boolean prepend = first <= WINDOW_EDGE;
        boolean append = last >= getItemCount() - WINDOW_EDGE - 1;
        if (prepend) newStart = Math.max(0, visibleStart - WINDOW_STEP);
        if (append) newEnd = Math.min(getTotalCount(), visibleEnd + WINDOW_STEP);
        if (newEnd - newStart > WINDOW_MAX) {
            if (prepend) newEnd = Math.min(newEnd, newStart + WINDOW_MAX);
            else if (append) newStart = Math.max(0, newEnd - WINDOW_MAX);
        }
        applyWindowChange(recyclerView, manager, first, top, oldStart, oldEnd, newStart, newEnd);
    }

    private int getTotalCount() {
        return mItems.size() + (selectDir ? 1 : 0);
    }

    private int getTop(LinearLayoutManager manager, int position) {
        View view = manager.findViewByPosition(position);
        return view == null ? 0 : view.getTop();
    }

    private void applyWindowChange(RecyclerView recyclerView, LinearLayoutManager manager, int first, int top, int oldStart, int oldEnd, int newStart, int newEnd) {
        if (oldStart == newStart && oldEnd == newEnd) return;
        if (newStart < oldStart) {
            int inserted = oldStart - newStart;
            visibleStart = newStart;
            notifyItemRangeInserted(0, inserted);
            recyclerView.post(() -> manager.scrollToPositionWithOffset(first + inserted, top));
        }
        if (newEnd > oldEnd) {
            int inserted = newEnd - oldEnd;
            int position = oldEnd - visibleStart;
            visibleEnd = newEnd;
            notifyItemRangeInserted(position, inserted);
        }
        if (newStart > oldStart) {
            int removed = newStart - oldStart;
            visibleStart = newStart;
            notifyItemRangeRemoved(0, removed);
            recyclerView.post(() -> manager.scrollToPositionWithOffset(Math.max(0, first - removed), top));
        }
        if (newEnd < oldEnd) {
            int removed = oldEnd - newEnd;
            visibleEnd = newEnd;
            notifyItemRangeRemoved(newEnd - visibleStart, removed);
        }
    }

    private void setWindowAround(int position) {
        int total = getTotalCount();
        if (total == 0) {
            visibleStart = visibleEnd = 0;
            return;
        }
        int half = WINDOW_SIZE / 2;
        visibleStart = Math.max(0, position - half);
        visibleEnd = Math.min(total, visibleStart + WINDOW_SIZE);
        visibleStart = Math.max(0, visibleEnd - WINDOW_SIZE);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFileBinding binding;

        ViewHolder(@NonNull AdapterFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
