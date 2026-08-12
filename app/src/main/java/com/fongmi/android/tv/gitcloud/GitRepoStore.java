package com.fongmi.android.tv.gitcloud;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class GitRepoStore {

    private static final String KEY = "git_cloud_favorite_repos";
    private static final Type TYPE = new TypeToken<List<GitRepo>>() {}.getType();

    private GitRepoStore() {
    }

    public static List<GitRepo> list(GitProviderType type) {
        List<GitRepo> result = new ArrayList<>();
        for (GitRepo repo : list()) if (repo.providerType == type) result.add(repo);
        return result;
    }

    public static boolean contains(GitRepo repo) {
        if (repo == null || TextUtils.isEmpty(repo.fullName)) return false;
        for (GitRepo item : list()) {
            if (item.providerType == repo.providerType && TextUtils.equals(item.fullName, repo.fullName)) return true;
        }
        return false;
    }

    public static void add(GitRepo repo) {
        if (repo == null || repo.providerType == null || TextUtils.isEmpty(repo.fullName)) return;
        List<GitRepo> repos = list();
        repos.removeIf(item -> item.providerType == repo.providerType && TextUtils.equals(item.fullName, repo.fullName));
        repos.add(0, repo);
        persist(repos);
    }

    public static void remove(GitRepo repo) {
        if (repo == null || TextUtils.isEmpty(repo.fullName)) return;
        List<GitRepo> repos = list();
        repos.removeIf(item -> item.providerType == repo.providerType && TextUtils.equals(item.fullName, repo.fullName));
        persist(repos);
    }

    private static List<GitRepo> list() {
        try {
            List<GitRepo> repos = App.gson().fromJson(Prefers.getString(KEY), TYPE);
            return repos == null ? new ArrayList<>() : normalize(repos);
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private static void persist(List<GitRepo> repos) {
        Prefers.put(KEY, App.gson().toJson(normalize(repos)));
    }

    private static List<GitRepo> normalize(List<GitRepo> repos) {
        List<GitRepo> result = new ArrayList<>();
        for (GitRepo repo : repos) {
            if (repo == null || repo.providerType == null || TextUtils.isEmpty(repo.fullName)) continue;
            int split = repo.fullName.indexOf('/');
            if (TextUtils.isEmpty(repo.owner) && split > 0) repo.owner = repo.fullName.substring(0, split);
            if (TextUtils.isEmpty(repo.name)) repo.name = split > 0 ? repo.fullName.substring(repo.fullName.lastIndexOf('/') + 1) : repo.fullName;
            if (TextUtils.isEmpty(repo.defaultBranch)) repo.defaultBranch = "main";
            result.add(repo);
        }
        return result;
    }
}
