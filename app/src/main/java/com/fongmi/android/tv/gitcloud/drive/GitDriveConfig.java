package com.fongmi.android.tv.gitcloud.drive;

import com.fongmi.android.tv.gitcloud.GitAccount;
import com.fongmi.android.tv.gitcloud.GitRepo;

import java.io.File;

public class GitDriveConfig {

    public GitAccount account;
    public GitRepo repo;
    public String token;
    public String branch;
    public File worktreeDir;
    public String purpose;
    public String defaultRemotePath;

    public String branch() {
        return branch == null || branch.isEmpty() ? repo.defaultBranch : branch;
    }
}
