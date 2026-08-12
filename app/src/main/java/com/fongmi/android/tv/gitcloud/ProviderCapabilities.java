package com.fongmi.android.tv.gitcloud;

public class ProviderCapabilities {

    public boolean createPrivateRepo;
    public boolean contentsWrite;
    public boolean releaseAsset;
    public boolean archive;
    public boolean raw;
    public boolean pagination;
    public boolean jgitWrite;

    public ProviderCapabilities createPrivateRepo(boolean value) {
        createPrivateRepo = value;
        return this;
    }

    public ProviderCapabilities contentsWrite(boolean value) {
        contentsWrite = value;
        return this;
    }

    public ProviderCapabilities releaseAsset(boolean value) {
        releaseAsset = value;
        return this;
    }

    public ProviderCapabilities archive(boolean value) {
        archive = value;
        return this;
    }

    public ProviderCapabilities raw(boolean value) {
        raw = value;
        return this;
    }

    public ProviderCapabilities pagination(boolean value) {
        pagination = value;
        return this;
    }

    public ProviderCapabilities jgitWrite(boolean value) {
        jgitWrite = value;
        return this;
    }
}
