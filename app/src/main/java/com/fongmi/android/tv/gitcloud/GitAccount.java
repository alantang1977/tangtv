package com.fongmi.android.tv.gitcloud;

import android.text.TextUtils;

import java.util.UUID;

public class GitAccount {

    public String id;
    public GitProviderType providerType;
    public String baseUrl;
    public String username;
    public String remark;
    public String tokenKey;
    public long createdAt;
    public long lastValidatedAt;

    public static GitAccount create(GitProviderType type, String baseUrl, String remark) {
        GitAccount account = new GitAccount();
        account.id = UUID.randomUUID().toString();
        account.providerType = type;
        account.baseUrl = baseUrl;
        account.remark = remark;
        account.tokenKey = "git_cloud_" + account.id;
        account.createdAt = System.currentTimeMillis();
        return account;
    }

    public String displayName() {
        if (!TextUtils.isEmpty(remark)) return remark;
        if (!TextUtils.isEmpty(username)) return username;
        return providerType == null ? "Git" : providerType.name();
    }

    public String normalizedBaseUrl() {
        if (TextUtils.isEmpty(baseUrl)) return "";
        return baseUrl.replaceAll("/+$", "");
    }
}
