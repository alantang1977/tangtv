package com.fongmi.android.tv.gitcloud.drive;

import com.fongmi.android.tv.gitcloud.GitCloudException;

import java.util.List;

public interface GitDriveEngine {

    GitDriveState bind(GitDriveConfig config) throws GitCloudException;

    GitDriveState pull(GitDriveConfig config) throws GitCloudException;

    CommitResult commitAndPush(GitDriveConfig config, List<FileChange> changes) throws GitCloudException;

    GitDriveState status(GitDriveConfig config) throws GitCloudException;

    void cancel(String taskId);
}
