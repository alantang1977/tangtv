package com.fongmi.android.tv.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class RemoteModels {

    private RemoteModels() {
    }

    public static class RemoteStoreFile {
        public int version = 1;
        public List<RemoteProfile> profiles = new ArrayList<>();
    }

    public static class RemoteProfile {
        public boolean enabled = true;
        public boolean keepOnline;
        public String serverUrl = "";
        public String serverOrigin = "";
        public String deviceId = "";
        public String deviceToken = "";
        public List<RemoteBindGrant> pendingBindGrants = new ArrayList<>();
        public List<RemoteGroup> groups = new ArrayList<>();
        public long updatedAt;
    }

    public static class RemoteGroup {
        public String groupId = "";
        public String groupToken = "";
        public String groupTokenHash = "";
        public String name = "";
        public List<RemoteDevice> devices = new ArrayList<>();
        public long updatedAt;
    }

    public static class RemoteBindGrant {
        public String grantId = "";
        public String bindGrantToken = "";
        public long createdAt;
        public long consumedAt;
    }

    public static class RemoteDevice {
        public String deviceId = "";
        public String name = "";
        public int type;
        public String appVersion = "";
        public long lastSeen;
        public boolean online;
        public RemoteCapabilities capabilities = new RemoteCapabilities();
    }

    public static class RemoteCapabilities {
        public boolean configManage = false;
        public boolean remoteSync = false;
        public boolean pushAction = true;
        public boolean recentLog = true;
        public boolean deviceBackup = false;
        public boolean fileManage = false;
        public boolean webHomeManage = false;
        public boolean shellProxyManage = false;
        public boolean siteInjectManage = false;
        public boolean webHomeExtensionManage = false;
        public boolean multiDeviceBatch = false;
        public boolean webSocket = false;
        public boolean persistentStorage = false;
        public boolean externalObjectStorage = false;
        public boolean deviceRevoke = false;
    }

    public static class ServerCapabilities {
        public boolean ok;
        public String serverMode = "";
        public String serverName = "";
        public String relayMode = "";
        public long time;
        public long maxSyncPartBytes;
        public RemoteCapabilities capabilities = new RemoteCapabilities();
    }

    public static class RemoteCommand {
        public String id = "";
        public String type = "";
        public String status = "";
        public String groupId = "";
        public String groupTokenHash = "";
        public String targetDeviceId = "";
        public JsonObject payload = new JsonObject();
        public RemoteCommandResult result;
        public long createdAt;
        public long deliveredAt;
        public long finishedAt;
    }

    public static class RemoteCommandResult {
        public boolean ok;
        public boolean accepted;
        public String message = "";
        public JsonElement data;

        public static RemoteCommandResult success(String message, JsonElement data) {
            RemoteCommandResult result = new RemoteCommandResult();
            result.ok = true;
            result.message = message == null ? "" : message;
            result.data = data;
            return result;
        }

        public static RemoteCommandResult accepted(String message) {
            RemoteCommandResult result = success(message, null);
            result.accepted = true;
            return result;
        }

        public static RemoteCommandResult failure(String message) {
            RemoteCommandResult result = new RemoteCommandResult();
            result.ok = false;
            result.message = message == null ? "" : message;
            return result;
        }
    }

    public static class RegisterResponse {
        public boolean ok;
        public String deviceId = "";
        public String deviceToken = "";
        public String deviceSecret = "";
        public List<String> groupIds = new ArrayList<>();
        public JsonObject server;
    }

    public static class BindCodeResponse {
        public boolean ok;
        public String code = "";
        public String grantId = "";
        public int expiresIn;
        public JsonObject server;
    }

    public static class ClaimResponse {
        public boolean ok;
        public String deviceId = "";
        public String groupId = "";
        public String groupToken = "";
        public String familyToken = "";
        public String groupTokenHash = "";
        public String grantId = "";
        public String bindGrantToken = "";
        public String commandId = "";
        public RemoteDevice device;
        public JsonObject server;
    }

    public static class DevicesResponse {
        public boolean ok;
        public List<RemoteDevice> devices = new ArrayList<>();
        public JsonObject server;
    }

    public static class CommandResponse {
        public boolean ok;
        public String commandId = "";
        public RemoteCommand command;
    }

    public static class CommandDetailResponse {
        public boolean ok;
        public RemoteCommand command;
        public JsonObject server;
    }

    public static class PollResponse {
        public boolean ok;
        public RemoteCommand command;
        public JsonObject server;
    }
}
