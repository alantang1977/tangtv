package com.fongmi.android.tv.gitcloud;

public class CreateRepoRequest {

    public String name;
    public String description;
    public boolean privateRepo;
    public boolean autoInit = true;

    public CreateRepoRequest(String name, String description, boolean privateRepo) {
        this.name = name;
        this.description = description;
        this.privateRepo = privateRepo;
    }
}
