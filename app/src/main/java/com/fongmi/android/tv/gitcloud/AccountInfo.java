package com.fongmi.android.tv.gitcloud;

public class AccountInfo {

    public String id;
    public String username;
    public String displayName;
    public String avatarUrl;
    public String webUrl;

    public AccountInfo(String id, String username, String displayName, String avatarUrl, String webUrl) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.webUrl = webUrl;
    }
}
