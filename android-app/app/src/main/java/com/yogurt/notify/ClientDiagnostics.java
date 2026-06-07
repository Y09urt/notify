package com.yogurt.notify;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

public final class ClientDiagnostics {
    private static final String TAG = "YogurtApp";

    private ClientDiagnostics() {
    }

    public static void record(Context context, String event, String message) {
        record(context, event, "info", message, null);
    }

    public static void record(Context context, String event, String level, String message) {
        record(context, event, level, message, null);
    }

    public static void record(Context context, String event, String level, String message, JSONObject details) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                SettingsStore settings = new SettingsStore(appContext);
                if (!settings.hasSession()) {
                    Log.i(TAG, "diagnostic skipped without session: " + event);
                    return;
                }
                new WorkerApi(settings).logClientEvent(event, level, message, details);
            } catch (Exception error) {
                Log.e(TAG, "diagnostic upload failed: " + event, error);
            }
        }).start();
    }
}
