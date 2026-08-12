package com.fongmi.android.tv.gitcloud;

import com.github.catvod.utils.Path;

import java.io.File;

public final class GitCloudPaths {

    private GitCloudPaths() {
    }

    public static File worktree(GitAccount account, GitRepo repo) {
        return new File(new File(new File(Path.files("git-drive"), safe(account.id)), safe(repo.fullName)), "worktree");
    }

    public static String backupDir() {
        return "apps/webhtv/backups";
    }

    public static String publicDir() {
        return "apps/webhtv/public";
    }

    public static String scriptsDir() {
        return "apps/webhtv/scripts";
    }

    private static String safe(String value) {
        return value == null ? "_" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
