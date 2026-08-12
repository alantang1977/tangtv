package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterTmdbPhotoBinding;
import com.fongmi.android.tv.utils.ImgUtil;

public class TmdbPhotoPresenter extends Presenter {

    private final OnClickListener mListener;

    public TmdbPhotoPresenter(OnClickListener listener) {
        this.mListener = listener;
    }

    public interface OnClickListener {
        void onItemClick(String url, int position);
    }

    @Override
    public Presenter.ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(AdapterTmdbPhotoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        String url = (String) item;
        ViewHolder holder = (ViewHolder) viewHolder;
        ImgUtil.load(holder.binding.photo.getContext().getString(R.string.tmdb_photos_label), url, holder.binding.photo);
        setOnClickListener(holder, view -> {
            if (mListener != null) mListener.onItemClick(url, 0);
        });
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterTmdbPhotoBinding binding;

        public ViewHolder(@NonNull AdapterTmdbPhotoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
