package com.fongmi.android.tv.gitcloud;

import android.text.TextUtils;

public class GitRepo {

    public GitProviderType providerType;
    public String owner;
    public String name;
    public String fullName;
    public String cloneUrl;
    public String webUrl;
    public String defaultBranch;
    public boolean privateRepo;
    public long sizeKb;
    public long updatedAt;

    public String displayName() {
        if (!TextUtils.isEmpty(fullName)) return fullName;
        if (TextUtils.isEmpty(owner)) return name == null ? "" : name;
        return owner + "/" + name;
    }
}
