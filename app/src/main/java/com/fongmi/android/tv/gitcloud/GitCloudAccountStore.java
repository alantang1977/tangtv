package com.fongmi.android.tv.gitcloud;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class GitCloudAccountStore {

    private static final String KEY = "git_cloud_accounts";
    private static final Type TYPE = new TypeToken<List<GitAccount>>() {}.getType();

    private GitCloudAccountStore() {
    }

    public static List<GitAccount> list() {
        try {
            List<GitAccount> accounts = App.gson().fromJson(Prefers.getString(KEY), TYPE);
            return accounts == null ? new ArrayList<>() : normalize(accounts);
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    public static GitAccount first() {
        List<GitAccount> accounts = list();
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public static GitAccount first(GitProviderType type) {
        for (GitAccount account : list()) {
            if (account.providerType == type) return account;
        }
        return null;
    }

    public static void save(GitAccount account) {
        if (account == null || TextUtils.isEmpty(account.id)) return;
        List<GitAccount> accounts = list();
        boolean replaced = false;
        for (int i = 0; i < accounts.size(); i++) {
            if (!TextUtils.equals(accounts.get(i).id, account.id)) continue;
            accounts.set(i, account);
            replaced = true;
            break;
        }
        if (!replaced) accounts.add(0, account);
        persist(accounts);
    }

    public static void remove(GitAccount account) {
        if (account == null) return;
        List<GitAccount> accounts = list();
        accounts.removeIf(item -> TextUtils.equals(item.id, account.id));
        persist(accounts);
    }

    private static void persist(List<GitAccount> accounts) {
        Prefers.put(KEY, App.gson().toJson(normalize(accounts)));
    }

    private static List<GitAccount> normalize(List<GitAccount> accounts) {
        List<GitAccount> result = new ArrayList<>();
        for (GitAccount account : accounts) {
            if (account == null || TextUtils.isEmpty(account.id) || account.providerType == null) continue;
            if (TextUtils.isEmpty(account.tokenKey)) account.tokenKey = "git_cloud_" + account.id;
            result.add(account);
        }
        return result;
    }
}
