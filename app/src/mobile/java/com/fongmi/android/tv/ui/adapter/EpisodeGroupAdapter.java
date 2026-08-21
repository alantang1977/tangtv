package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterEpisodeGroupBinding;

import java.util.ArrayList;
import java.util.List;

public class EpisodeGroupAdapter extends RecyclerView.Adapter<EpisodeGroupAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Group> items;

    public EpisodeGroupAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(Group item);
    }

    public void addAll(List<Group> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    public List<Group> getItems() {
        return items;
    }

    public int getPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).selected) return i;
        return 0;
    }

    public void setSelected(Group group) {
        for (Group item : items) item.selected = item == group;
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group item = items.get(position);
        holder.binding.text.setText(item.name);
        holder.binding.text.setSelected(item.selected);
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }

    public static List<Group> build(int size, int selectedIndex, boolean reverse) {
        List<Group> groups = new ArrayList<>();
        if (size <= 0) return groups;
        int groupSize = getGroupSize(size);
        int count = (int) Math.ceil(size / (float) groupSize);
        for (int i = 0; i < count; i++) {
            int start = i * groupSize;
            int end = Math.min(start + groupSize, size);
            int labelStart = reverse ? size - start : start + 1;
            int labelEnd = reverse ? size - end + 1 : end;
            Group group = new Group(Math.max(labelStart, labelEnd) == Math.min(labelStart, labelEnd) ? String.valueOf(labelStart) : labelStart + "-" + labelEnd, start, end);
            group.selected = selectedIndex >= start && selectedIndex < end;
            groups.add(group);
        }
        if (groups.stream().noneMatch(group -> group.selected)) groups.get(0).selected = true;
        return groups;
    }

    private static int getGroupSize(int size) {
        if (size <= 60) return 20;
        if (size > 2500) return 300;
        if (size > 1500) return 200;
        if (size > 1000) return 150;
        if (size > 500) return 100;
        if (size > 300) return 50;
        return 40;
    }

    public static class Group {

        public final String name;
        public final int start;
        public final int end;
        public boolean selected;

        public Group(String name, int start, int end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeGroupBinding binding;

        ViewHolder(@NonNull AdapterEpisodeGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
