package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityFileBinding;
import com.fongmi.android.tv.ui.adapter.FileAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;
    private boolean selectDir;

    private boolean isRoot() {
        return Path.root().equals(dir);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        selectDir = getIntent().getBooleanExtra("select_dir", false);
        setRecyclerView();
        checkPermission();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        PermissionUtil.requestFile(this, allGranted -> update(Path.root()));
    }

    private void update(File dir) {
        mBinding.recycler.setSelectedPosition(0);
        mAdapter.addAll(this.dir = dir, list(dir), selectDir);
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
    }

    private List<File> list(File dir) {
        if (!selectDir) return Path.list(dir);
        File[] files = dir.listFiles(File::isDirectory);
        if (files == null) return new ArrayList<>();
        Path.sort(files);
        return Arrays.asList(files);
    }

    @Override
    public void onItemClick(File file) {
        if (file.isDirectory()) {
            update(file);
        } else {
            setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
            finish();
        }
    }

    @Override
    public void onCurrentDirClick(File dir) {
        if (dir == null) return;
        setResult(RESULT_OK, new Intent().setData(Uri.fromFile(dir)));
        finish();
    }

    @Override
    protected void onBackInvoked() {
        if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
    }
}
