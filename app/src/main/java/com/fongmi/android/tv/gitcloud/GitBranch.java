package com.fongmi.android.tv.gitcloud;

public class GitBranch {

    public String name;
    public String sha;
    public boolean isDefault;

    public GitBranch(String name, String sha, boolean isDefault) {
        this.name = name;
        this.sha = sha;
        this.isDefault = isDefault;
    }
}
