package com.yogurt.notify;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsStore {
    private static final String PREFS = "notify_settings";
    private static final String KEY_WORKER_URL = "worker_url";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_LAST_TOKEN = "last_token";
    private static final String KEY_LAST_HISTORY_SYNC_AT = "last_history_sync_at";
    private static final String KEY_SESSION_TOKEN = "session_token";
    private static final String KEY_IS_ADMIN = "is_admin";

    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String workerUrl() {
        return normalizeWorkerUrl(prefs.getString(KEY_WORKER_URL, AppConfig.WORKER_BASE_URL));
    }

    public String userId() {
        return prefs.getString(KEY_USER_ID, AppConfig.USER_ID);
    }

    public String lastToken() {
        return prefs.getString(KEY_LAST_TOKEN, "");
    }

    public long lastHistorySyncAt() {
        return prefs.getLong(KEY_LAST_HISTORY_SYNC_AT, 0);
    }

    public String sessionToken() {
        return prefs.getString(KEY_SESSION_TOKEN, "");
    }

    public boolean hasSession() {
        return !sessionToken().isEmpty();
    }

    public boolean isAdmin() {
        return prefs.getBoolean(KEY_IS_ADMIN, false);
    }

    public void setWorkerUrl(String value) {
        prefs.edit().putString(KEY_WORKER_URL, normalizeWorkerUrl(value)).apply();
    }

    public void setUserId(String value) {
        prefs.edit().putString(KEY_USER_ID, value.trim()).apply();
    }

    public void setLastToken(String value) {
        prefs.edit().putString(KEY_LAST_TOKEN, value).apply();
    }

    public void setLastHistorySyncAt(long value) {
        prefs.edit().putLong(KEY_LAST_HISTORY_SYNC_AT, value).apply();
    }

    public void setSession(String userId, String sessionToken, boolean isAdmin) {
        prefs.edit()
                .putString(KEY_USER_ID, userId.trim())
                .putString(KEY_SESSION_TOKEN, sessionToken)
                .putBoolean(KEY_IS_ADMIN, isAdmin)
                .apply();
    }

    public void clearSession() {
        prefs.edit()
                .remove(KEY_SESSION_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_IS_ADMIN)
                .apply();
    }

    private String normalizeWorkerUrl(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", "");
        if (text.isEmpty()) {
            return AppConfig.WORKER_BASE_URL;
        }
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "https://" + text;
        }
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.isEmpty() ? AppConfig.WORKER_BASE_URL : text;
    }
}

