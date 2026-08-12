package com.fongmi.android.tv.gitcloud.provider;

import com.fongmi.android.tv.gitcloud.GitProviderType;

public final class GitCloudProviders {

    private static final GitHubProvider GITHUB = new GitHubProvider();
    private static final CnbProvider CNB = new CnbProvider();

    private GitCloudProviders() {
    }

    public static GitCloudProvider get(GitProviderType type) {
        if (type == GitProviderType.CNB) return CNB;
        return GITHUB;
    }
}
