package com.fongmi.android.tv.gitcloud.provider;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.gitcloud.AccountInfo;
import com.fongmi.android.tv.gitcloud.CreateRepoRequest;
import com.fongmi.android.tv.gitcloud.DownloadRef;
import com.fongmi.android.tv.gitcloud.GitAccount;
import com.fongmi.android.tv.gitcloud.GitBranch;
import com.fongmi.android.tv.gitcloud.GitCloudException;
import com.fongmi.android.tv.gitcloud.GitFile;
import com.fongmi.android.tv.gitcloud.GitFileContent;
import com.fongmi.android.tv.gitcloud.GitProviderType;
import com.fongmi.android.tv.gitcloud.GitRepo;
import com.fongmi.android.tv.gitcloud.ProviderCapabilities;
import com.fongmi.android.tv.gitcloud.SaveOptions;
import com.fongmi.android.tv.gitcloud.SaveResult;
import com.fongmi.android.tv.web.GitRawUrlResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.Request;

public class GitHubProvider extends BaseGitProvider {

    private static final String API = "https://api.github.com";

    @Override
    public GitProviderType type() {
        return GitProviderType.GITHUB;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities()
                .createPrivateRepo(true)
                .contentsWrite(true)
                .releaseAsset(true)
                .archive(true)
                .raw(true)
                .pagination(true)
                .jgitWrite(true);
    }

    @Override
    protected void headers(Request.Builder builder, String token) {
        super.headers(builder, token);
        builder.header("Accept", "application/vnd.github+json");
        builder.header("X-GitHub-Api-Version", "2022-11-28");
    }

    @Override
    public AccountInfo validateToken(GitAccount account, String token) throws GitCloudException {
        JsonObject object = get(API + "/user", token);
        return new AccountInfo(str(object, "id"), str(object, "login"), str(object, "name"), str(object, "avatar_url"), str(object, "html_url"));
    }

    @Override
    public List<GitRepo> listRepos(GitAccount account, String token) throws GitCloudException {
        List<GitRepo> repos = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JsonArray array = getArray(API + "/user/repos?per_page=100&sort=updated&type=owner&page=" + page, token);
            if (array.size() == 0) break;
            for (JsonElement element : array) if (element.isJsonObject()) repos.add(repo(element.getAsJsonObject()));
            if (array.size() < 100) break;
        }
        return repos;
    }

    @Override
    public List<GitRepo> searchRepos(GitAccount account, String token, String keyword) throws GitCloudException {
        if (TextUtils.isEmpty(keyword)) throw new GitCloudException("搜索关键词为空");
        JsonObject object = get(API + "/search/repositories?q=" + enc(keyword) + "&sort=updated&per_page=30", token);
        JsonArray array = array(object, "items");
        List<GitRepo> repos = new ArrayList<>();
        for (JsonElement element : array) if (element.isJsonObject()) repos.add(repo(element.getAsJsonObject()));
        return repos;
    }

    @Override
    public GitRepo getRepo(GitAccount account, String token, String fullName) throws GitCloudException {
        if (TextUtils.isEmpty(fullName)) throw new GitCloudException("仓库地址为空");
        return repo(get(API + "/repos/" + encPath(fullName), token));
    }

    @Override
    public List<GitRepo> listUserRepos(GitAccount account, String token, String owner) throws GitCloudException {
        if (TextUtils.isEmpty(owner)) throw new GitCloudException("用户名为空");
        JsonArray array = getArray(API + "/users/" + enc(owner) + "/repos?per_page=60&sort=updated&type=owner", token);
        List<GitRepo> repos = new ArrayList<>();
        for (JsonElement element : array) if (element.isJsonObject()) repos.add(repo(element.getAsJsonObject()));
        return repos;
    }

    @Override
    public GitRepo forkRepo(GitAccount account, String token, GitRepo repo) throws GitCloudException {
        if (repo == null || TextUtils.isEmpty(repo.fullName)) throw new GitCloudException("仓库地址为空");
        return repo(post(API + "/repos/" + encPath(repo.fullName) + "/forks", token, new JsonObject()));
    }

    @Override
    public GitRepo createRepo(GitAccount account, String token, CreateRepoRequest request) throws GitCloudException {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", request.name);
        payload.addProperty("description", request.description == null ? "" : request.description);
        payload.addProperty("private", request.privateRepo);
        payload.addProperty("auto_init", request.autoInit);
        return repo(post(API + "/user/repos", token, payload));
    }

    @Override
    public void deleteRepo(GitAccount account, String token, GitRepo repo) throws GitCloudException {
        delete(API + "/repos/" + encPath(repo.fullName), token);
    }

    @Override
    public List<GitBranch> listBranches(GitAccount account, String token, GitRepo repo) throws GitCloudException {
        JsonArray array = getArray(API + "/repos/" + encPath(repo.fullName) + "/branches?per_page=100", token);
        List<GitBranch> branches = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = str(object, "name");
            branches.add(new GitBranch(name, str(obj(object, "commit"), "sha"), TextUtils.equals(name, repo.defaultBranch)));
        }
        return branches;
    }

    @Override
    public List<GitFile> listFiles(GitAccount account, String token, GitRepo repo, String ref, String path) throws GitCloudException {
        String url = API + "/repos/" + encPath(repo.fullName) + "/contents/" + encPath(path);
        if (!TextUtils.isEmpty(ref)) url += "?ref=" + enc(ref);
        HttpResult result = request("GET", url, token, null);
        JsonArray array;
        try {
            array = result.array();
        } catch (GitCloudException e) {
            array = new JsonArray();
            array.add(result.object());
        }
        List<GitFile> files = new ArrayList<>();
        for (JsonElement element : array) if (element.isJsonObject()) files.add(file(repo, ref, element.getAsJsonObject()));
        files.sort((a, b) -> a.directory == b.directory ? a.name.compareToIgnoreCase(b.name) : a.directory ? -1 : 1);
        return files;
    }

    @Override
    public GitFileContent readFile(GitAccount account, String token, GitRepo repo, String ref, String path) throws GitCloudException {
        String url = API + "/repos/" + encPath(repo.fullName) + "/contents/" + encPath(path);
        if (!TextUtils.isEmpty(ref)) url += "?ref=" + enc(ref);
        JsonObject object = get(url, token);
        GitFileContent content = new GitFileContent();
        content.file = file(repo, ref, object);
        String encoded = str(object, "content").replace("\n", "");
        content.data = TextUtils.isEmpty(encoded) ? new byte[0] : Base64.decode(encoded, Base64.DEFAULT);
        content.text = new String(content.data, StandardCharsets.UTF_8);
        return content;
    }

    @Override
    public SaveResult saveSmallFile(GitAccount account, String token, GitRepo repo, String branch, String path, byte[] data, SaveOptions options) throws GitCloudException {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", TextUtils.isEmpty(options == null ? "" : options.message) ? "upload: " + path : options.message);
        payload.addProperty("content", Base64.encodeToString(data == null ? new byte[0] : data, Base64.NO_WRAP));
        if (!TextUtils.isEmpty(branch)) payload.addProperty("branch", branch);
        if (options != null && !TextUtils.isEmpty(options.sha)) payload.addProperty("sha", options.sha);
        JsonObject object = put(API + "/repos/" + encPath(repo.fullName) + "/contents/" + encPath(path), token, payload);
        SaveResult result = new SaveResult();
        JsonObject content = obj(object, "content");
        result.sha = str(content, "sha");
        result.webUrl = str(content, "html_url");
        result.commitSha = str(obj(object, "commit"), "sha");
        return result;
    }

    @Override
    public void deleteFile(GitAccount account, String token, GitRepo repo, String branch, GitFile file, String message) throws GitCloudException {
        if (file == null || TextUtils.isEmpty(file.path)) throw new GitCloudException("删除文件路径为空");
        String sha = file.sha;
        if (TextUtils.isEmpty(sha)) sha = readFile(account, token, repo, branch, file.path).file.sha;
        if (TextUtils.isEmpty(sha)) throw new GitCloudException("删除文件缺少 sha：" + file.path);
        JsonObject payload = new JsonObject();
        payload.addProperty("message", TextUtils.isEmpty(message) ? "delete: " + file.path : message);
        payload.addProperty("sha", sha);
        if (!TextUtils.isEmpty(branch)) payload.addProperty("branch", branch);
        request("DELETE", API + "/repos/" + encPath(repo.fullName) + "/contents/" + encPath(file.path), token, payload);
    }

    @Override
    public String rawUrl(GitAccount account, GitRepo repo, String ref, String path) {
        String branch = TextUtils.isEmpty(ref) ? repo.defaultBranch : ref;
        return GitRawUrlResolver.github(repo.owner, repo.name, branch, path);
    }

    @Override
    public DownloadRef archiveUrl(GitAccount account, String token, GitRepo repo, String ref, String path) {
        String branch = TextUtils.isEmpty(ref) ? repo.defaultBranch : ref;
        return new DownloadRef("https://github.com/" + repo.fullName + "/archive/refs/heads/" + branch + ".zip", Map.of("Authorization", "Bearer " + token));
    }

    private GitRepo repo(JsonObject object) {
        GitRepo repo = new GitRepo();
        repo.providerType = GitProviderType.GITHUB;
        repo.name = str(object, "name");
        repo.fullName = str(object, "full_name");
        int split = repo.fullName.indexOf('/');
        repo.owner = split > 0 ? repo.fullName.substring(0, split) : str(obj(object, "owner"), "login");
        repo.cloneUrl = str(object, "clone_url");
        repo.webUrl = str(object, "html_url");
        repo.defaultBranch = str(object, "default_branch");
        repo.privateRepo = bool(object, "private");
        repo.sizeKb = integer(object, "size");
        return repo;
    }

    private GitFile file(GitRepo repo, String ref, JsonObject object) {
        GitFile file = new GitFile();
        file.name = str(object, "name");
        file.path = str(object, "path");
        file.directory = "dir".equals(str(object, "type"));
        file.size = integer(object, "size");
        file.sha = str(object, "sha");
        file.downloadUrl = str(object, "download_url");
        file.webUrl = str(object, "html_url");
        file.rawUrl = rawUrl(null, repo, ref, file.path);
        return file;
    }
}
