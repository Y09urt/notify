package com.ljq.notify;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsStore {
    private static final String PREFS = "notify_settings";
    private static final String KEY_WORKER_URL = "worker_url";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_LAST_TOKEN = "last_token";

    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String workerUrl() {
        return prefs.getString(KEY_WORKER_URL, AppConfig.WORKER_BASE_URL);
    }

    public String userId() {
        return prefs.getString(KEY_USER_ID, AppConfig.USER_ID);
    }

    public String lastToken() {
        return prefs.getString(KEY_LAST_TOKEN, "");
    }

    public void setWorkerUrl(String value) {
        prefs.edit().putString(KEY_WORKER_URL, trimTrailingSlash(value)).apply();
    }

    public void setUserId(String value) {
        prefs.edit().putString(KEY_USER_ID, value.trim()).apply();
    }

    public void setLastToken(String value) {
        prefs.edit().putString(KEY_LAST_TOKEN, value).apply();
    }

    private String trimTrailingSlash(String value) {
        String text = value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
