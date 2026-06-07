package com.yogurt.notify;

import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class WorkerApi {
    private static final String TAG = "YogurtApp";
    private final String workerBaseUrl;
    private final String userId;
    private final String sessionToken;

    public static final class AuthSession {
        public final String userId;
        public final String sessionToken;
        public final boolean isAdmin;

        public AuthSession(String userId, String sessionToken, boolean isAdmin) {
            this.userId = userId;
            this.sessionToken = sessionToken;
            this.isAdmin = isAdmin;
        }
    }

    public static final class UserInfo {
        public final String userId;
        public final boolean isAdmin;

        public UserInfo(String userId, boolean isAdmin) {
            this.userId = userId;
            this.isAdmin = isAdmin;
        }
    }

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String downloadUrl;
        public final String releaseNotes;
        public final String channel;

        public UpdateInfo(int versionCode, String versionName, String downloadUrl, String releaseNotes, String channel) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.downloadUrl = downloadUrl;
            this.releaseNotes = releaseNotes;
            this.channel = channel;
        }
    }

    public static final class UserGroup {
        public final String id;
        public final String name;
        public final boolean isAdmin;
        public final int memberCount;

        public UserGroup(String id, String name, boolean isAdmin, int memberCount) {
            this.id = id;
            this.name = name;
            this.isAdmin = isAdmin;
            this.memberCount = memberCount;
        }
    }

    public WorkerApi(SettingsStore settings) {
        workerBaseUrl = settings.workerUrl();
        userId = settings.userId();
        sessionToken = settings.sessionToken();
    }

    public AuthSession registerAccount(String id, String password) throws Exception {
        return authRequest("/auth/register", id, password);
    }

    public AuthSession login(String id, String password) throws Exception {
        return authRequest("/auth/login", id, password);
    }

    public UserInfo checkSession() throws Exception {
        String response = request("GET", "/auth/me", null);
        JSONObject payload = new JSONObject(response);
        return new UserInfo(payload.optString("userId"), payload.optBoolean("isAdmin", false));
    }

    public void logout() throws Exception {
        request("POST", "/auth/logout", "{}");
    }

    public UpdateInfo fetchUpdateInfo(String channel) throws Exception {
        String response = request("GET", "/app/version?channel=" + channel, null);
        JSONObject payload = new JSONObject(response);
        return new UpdateInfo(
                payload.optInt("versionCode", 1),
                payload.optString("versionName", "1.0"),
                payload.optString("downloadUrl"),
                payload.optString("releaseNotes"),
                payload.optString("channel", channel)
        );
    }

    public void registerToken(String token) throws Exception {
        Log.i(TAG, "registerToken userId=" + userId + " baseUrl=" + workerBaseUrl);
        JSONObject payload = new JSONObject();
        payload.put("platform", "honor");
        payload.put("token", token);
        request("POST", "/register", payload.toString());
    }

    public void sendMessage(String targetUserId, String groupId, String title, String body) throws Exception {
        JSONObject payload = new JSONObject();
        if (targetUserId != null && !targetUserId.trim().isEmpty()) {
            payload.put("userId", targetUserId);
        }
        if (groupId != null && !groupId.trim().isEmpty()) {
            payload.put("groupId", groupId);
        }
        payload.put("title", title);
        payload.put("body", body);
        request("POST", "/messages", payload.toString());
    }

    public List<UserGroup> fetchGroups() throws Exception {
        String response = request("GET", "/groups", null);
        JSONObject payload = new JSONObject(response);
        JSONArray rows = payload.optJSONArray("groups");
        ArrayList<UserGroup> groups = new ArrayList<>();
        if (rows == null) {
            return groups;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            groups.add(new UserGroup(
                    row.optString("id"),
                    row.optString("name"),
                    row.optBoolean("isAdmin", false),
                    row.optInt("memberCount", 0)
            ));
        }
        return groups;
    }

    public void saveGroup(String id, String name, boolean isAdmin) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("id", id);
        payload.put("name", name);
        payload.put("isAdmin", isAdmin);
        request("POST", "/groups", payload.toString());
    }

    public void setGroupMember(String groupId, String userId, boolean add) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("groupId", groupId);
        payload.put("userId", userId);
        payload.put("action", add ? "add" : "remove");
        request("POST", "/groups/members", payload.toString());
    }

    public List<NotifyMessage> fetchMessages() throws Exception {
        Log.i(TAG, "fetchMessages userId=" + userId + " baseUrl=" + workerBaseUrl);
        String response = request(
                "GET",
                "/messages?limit=100",
                null
        );
        JSONObject payload = new JSONObject(response);
        JSONArray rows = payload.optJSONArray("messages");
        ArrayList<NotifyMessage> messages = new ArrayList<>();
        if (rows == null) {
            return messages;
        }

        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            messages.add(new NotifyMessage(
                    row.optString("id"),
                    row.optString("title"),
                    row.optString("body"),
                    String.valueOf(row.opt("data")),
                    parseTimestamp(row.optString("createdAt"))
            ));
        }
        return messages;
    }

    private AuthSession authRequest(String path, String id, String password) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("id", id);
        payload.put("password", password);
        String response = request("POST", path, payload.toString());
        JSONObject body = new JSONObject(response);
        return new AuthSession(
                body.optString("userId"),
                body.optString("sessionToken"),
                body.optBoolean("isAdmin", false)
        );
    }

    private String request(String method, String path, String body) throws Exception {
        URL url = new URL(workerBaseUrl + path);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("content-type", "application/json");
            if (!sessionToken.isEmpty()) {
                connection.setRequestProperty("authorization", "Bearer " + sessionToken);
            }

            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(),
                    StandardCharsets.UTF_8
            ));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }

            if (status < 200 || status >= 300) {
                Log.e(TAG, "Worker request failed: " + method + " " + url + " status=" + status + " body=" + text);
                throw new IllegalStateException("Worker request failed: " + status + " " + text);
            }
            Log.i(TAG, "Worker request ok: " + method + " " + path + " status=" + status);
            return text.toString();
        } catch (Exception error) {
            Log.e(TAG, "Worker request error: " + method + " " + url, error);
            throw new IllegalStateException("Worker request failed: " + url + " - " + error.getMessage(), error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private long parseTimestamp(String createdAt) {
        if (createdAt == null || createdAt.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(createdAt);
            return date == null ? System.currentTimeMillis() : date.getTime();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }
}

