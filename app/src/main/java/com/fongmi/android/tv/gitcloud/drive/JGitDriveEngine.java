package com.fongmi.android.tv.gitcloud.drive;

import android.text.TextUtils;

import com.fongmi.android.tv.gitcloud.GitCloudException;
import com.fongmi.android.tv.gitcloud.GitProviderType;
import com.github.catvod.utils.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JGitDriveEngine implements GitDriveEngine {

    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();

    @Override
    public GitDriveState bind(GitDriveConfig config) throws GitCloudException {
        require(config);
        if (new File(config.worktreeDir, ".git").exists()) return status(config);
        File tmp = new File(config.worktreeDir.getParentFile(), config.worktreeDir.getName() + ".tmp");
        Path.clear(tmp);
        try {
            org.eclipse.jgit.api.CloneCommand command = Git.cloneRepository()
                    .setURI(config.repo.cloneUrl)
                    .setDirectory(tmp)
                    .setCredentialsProvider(credentials(config));
            if (!TextUtils.isEmpty(config.branch())) command.setBranch(config.branch());
            command.call().close();
            Path.clear(config.worktreeDir);
            if (!tmp.renameTo(config.worktreeDir)) throw new GitCloudException("切换本地工作目录失败");
            return status(config);
        } catch (GitCloudException e) {
            throw e;
        } catch (Throwable e) {
            Path.clear(tmp);
            throw new GitCloudException("绑定仓库失败：" + sanitize(e.getMessage()), e);
        }
    }

    @Override
    public GitDriveState pull(GitDriveConfig config) throws GitCloudException {
        require(config);
        try (Git git = Git.open(config.worktreeDir)) {
            git.pull().setCredentialsProvider(credentials(config)).call();
            return state(git, "同步完成");
        } catch (Throwable e) {
            throw new GitCloudException("同步失败：" + sanitize(e.getMessage()), e);
        }
    }

    @Override
    public CommitResult commitAndPush(GitDriveConfig config, List<FileChange> changes) throws GitCloudException {
        require(config);
        if (changes == null || changes.isEmpty()) throw new GitCloudException("没有需要上传的文件");
        try (Git git = openOrBind(config)) {
            for (FileChange change : changes) {
                if (change == null || TextUtils.isEmpty(change.path)) continue;
                File target = safeFile(config.worktreeDir, change.path);
                if (change.delete) {
                    Path.clear(target);
                    git.rm().addFilepattern(change.path).call();
                } else {
                    write(target, change.data);
                    git.add().addFilepattern(change.path).call();
                }
            }
            Status status = git.status().call();
            if (status.isClean()) {
                CommitResult result = new CommitResult();
                result.message = "没有文件变化";
                return result;
            }
            String message = "upload: WebHTV files " + System.currentTimeMillis();
            ObjectId id = git.commit().setMessage(message).setAuthor("WebHTV", "webhtv@app.local").call().getId();
            git.push().setCredentialsProvider(credentials(config)).call();
            CommitResult result = new CommitResult();
            result.commitSha = id == null ? "" : id.name();
            result.pushed = true;
            result.message = message;
            return result;
        } catch (GitCloudException e) {
            throw e;
        } catch (Throwable e) {
            throw new GitCloudException("上传失败：" + sanitize(e.getMessage()), e);
        }
    }

    @Override
    public GitDriveState status(GitDriveConfig config) throws GitCloudException {
        require(config);
        try (Git git = Git.open(config.worktreeDir)) {
            return state(git, "本地缓存就绪");
        } catch (Throwable e) {
            throw new GitCloudException("读取仓库状态失败：" + sanitize(e.getMessage()), e);
        }
    }

    @Override
    public void cancel(String taskId) {
        if (!TextUtils.isEmpty(taskId)) cancelled.put(taskId, true);
    }

    private Git openOrBind(GitDriveConfig config) throws Exception {
        if (!new File(config.worktreeDir, ".git").exists()) bind(config);
        return Git.open(config.worktreeDir);
    }

    private GitDriveState state(Git git, String message) throws Exception {
        GitDriveState state = new GitDriveState();
        state.branch = git.getRepository().getBranch();
        ObjectId head = git.getRepository().resolve("HEAD");
        state.head = head == null ? "" : head.name();
        state.clean = git.status().call().isClean();
        state.message = message;
        return state;
    }

    private UsernamePasswordCredentialsProvider credentials(GitDriveConfig config) {
        String username = config.account.providerType == GitProviderType.CNB ? "cnb" : config.account.username;
        if (TextUtils.isEmpty(username)) username = "x-access-token";
        return new UsernamePasswordCredentialsProvider(username, config.token);
    }

    private void require(GitDriveConfig config) throws GitCloudException {
        if (config == null || config.account == null || config.repo == null) throw new GitCloudException("同步配置不完整");
        if (TextUtils.isEmpty(config.repo.cloneUrl)) throw new GitCloudException("仓库缺少 HTTPS 地址");
        if (TextUtils.isEmpty(config.token)) throw new GitCloudException("token 为空");
        if (config.worktreeDir == null) throw new GitCloudException("本地工作目录为空");
        File parent = config.worktreeDir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new GitCloudException("创建本地工作目录失败");
    }

    private File safeFile(File root, String path) throws GitCloudException {
        try {
            File file = new File(root, path.replace('\\', '/')).getCanonicalFile();
            File canonicalRoot = root.getCanonicalFile();
            if (!file.getPath().startsWith(canonicalRoot.getPath())) throw new GitCloudException("文件路径不安全");
            return file;
        } catch (GitCloudException e) {
            throw e;
        } catch (Throwable e) {
            throw new GitCloudException("文件路径不合法", e);
        }
    }

    private void write(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new GitCloudException("创建目录失败");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data == null ? new byte[0] : data);
        }
    }

    private String sanitize(String message) {
        if (message == null) return "";
        return message.replaceAll("(?i)(token|authorization|password)=?[^\\s]+", "$1=***");
    }
}
