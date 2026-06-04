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

        public AuthSession(String userId, String sessionToken) {
            this.userId = userId;
            this.sessionToken = sessionToken;
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

    public String checkSession() throws Exception {
        String response = request("GET", "/auth/me", null);
        JSONObject payload = new JSONObject(response);
        return payload.optString("userId");
    }

    public void logout() throws Exception {
        request("POST", "/auth/logout", "{}");
    }

    public void registerToken(String token) throws Exception {
        Log.i(TAG, "registerToken userId=" + userId + " baseUrl=" + workerBaseUrl);
        JSONObject payload = new JSONObject();
        payload.put("platform", "honor");
        payload.put("token", token);
        request("POST", "/register", payload.toString());
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
        return new AuthSession(body.optString("userId"), body.optString("sessionToken"));
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

