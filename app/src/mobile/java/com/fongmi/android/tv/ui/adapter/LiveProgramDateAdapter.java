package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.databinding.AdapterLiveProgramDateBinding;
import com.fongmi.android.tv.utils.Formatters;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class LiveProgramDateAdapter extends RecyclerView.Adapter<LiveProgramDateAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Epg> items;
    private int selected;

    public LiveProgramDateAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public void setItems(List<Epg> items, String date) {
        this.items.clear();
        this.items.addAll(items);
        this.selected = Math.max(0, find(date));
        notifyDataSetChanged();
    }

    public Epg getSelected() {
        return items.isEmpty() ? new Epg() : items.get(selected);
    }

    private int find(String date) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).getDate().equals(date)) return i;
        return -1;
    }

    public interface OnClickListener {

        void onDateClick(Epg epg);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLiveProgramDateBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Epg item = items.get(position);
        holder.binding.text.setText(label(item.getDate()));
        holder.binding.text.setSelected(position == selected);
        holder.binding.text.setOnClickListener(v -> {
            int old = selected;
            selected = holder.getBindingAdapterPosition();
            notifyItemChanged(old);
            notifyItemChanged(selected);
            listener.onDateClick(item);
        });
    }

    private String label(String date) {
        try {
            LocalDate target = LocalDate.parse(date, Formatters.DATE);
            LocalDate today = LocalDate.now();
            if (target.equals(today.minusDays(1))) return "昨天";
            if (target.equals(today)) return "今天";
            if (target.equals(today.plusDays(1))) return "明天";
            return target.getMonthValue() + "/" + target.getDayOfMonth();
        } catch (Exception e) {
            return date;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLiveProgramDateBinding binding;

        ViewHolder(@NonNull AdapterLiveProgramDateBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
