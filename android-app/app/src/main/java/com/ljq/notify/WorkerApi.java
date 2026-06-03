package com.ljq.notify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class WorkerApi {
    private WorkerApi() {
    }

    public static void registerToken(String token) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("userId", AppConfig.USER_ID);
        payload.put("platform", "honor");
        payload.put("token", token);
        request("POST", "/register", payload.toString());
    }

    public static List<NotifyMessage> fetchMessages() throws Exception {
        String response = request(
                "GET",
                "/messages?userId=" + AppConfig.USER_ID + "&limit=100",
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
            String createdAt = row.optString("createdAt");
            long timestamp = System.currentTimeMillis();
            if (!createdAt.isEmpty()) {
                try {
                    Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(createdAt);
                    if (date != null) {
                        timestamp = date.getTime();
                    }
                } catch (Exception ignored) {
                }
            }
            messages.add(new NotifyMessage(
                    row.optString("id"),
                    row.optString("title"),
                    row.optString("body"),
                    String.valueOf(row.opt("data")),
                    timestamp
            ));
        }
        return messages;
    }

    private static String request(String method, String path, String body) throws Exception {
        URL url = new URL(AppConfig.WORKER_BASE_URL + path);
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
}
