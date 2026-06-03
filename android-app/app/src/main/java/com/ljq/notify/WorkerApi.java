package com.ljq.notify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class WorkerApi {
    private final String workerBaseUrl;
    private final String userId;

    public WorkerApi(SettingsStore settings) {
        workerBaseUrl = settings.workerUrl();
        userId = settings.userId();
    }

    public void registerToken(String token) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("platform", "honor");
        payload.put("token", token);
        request("POST", "/register", payload.toString());
    }

    public List<NotifyMessage> fetchMessages() throws Exception {
        String response = request(
                "GET",
                "/messages?userId=" + URLEncoder.encode(userId, "UTF-8") + "&limit=100",
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

    private String request(String method, String path, String body) throws Exception {
        URL url = new URL(workerBaseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("content-type", "application/json");

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
            throw new IllegalStateException("Worker request failed: " + status + " " + text);
        }
        return text.toString();
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
