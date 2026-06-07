package com.yogurt.notify.push;

import android.util.Log;

import com.hihonor.push.sdk.HonorMessageService;
import com.hihonor.push.sdk.HonorPushDataMsg;
import com.yogurt.notify.ClientDiagnostics;
import com.yogurt.notify.MessageStore;
import com.yogurt.notify.NotificationHelper;
import com.yogurt.notify.NotifyMessage;
import com.yogurt.notify.SettingsStore;
import com.yogurt.notify.WorkerApi;

import org.json.JSONObject;

public class NotifyHonorPushService extends HonorMessageService {
    private static final String TAG = "YogurtApp";

    @Override
    public void onNewToken(String token) {
        Log.i(TAG, "service onNewToken tokenEmpty=" + (token == null || token.trim().isEmpty()));
        ClientDiagnostics.record(this, "service_new_token", "tokenEmpty=" + (token == null || token.trim().isEmpty()));
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                SettingsStore settings = new SettingsStore(this);
                settings.setLastToken(token);
                new WorkerApi(settings).registerToken(token);
                Log.i(TAG, "service token uploaded");
                ClientDiagnostics.record(this, "service_token_uploaded", "Honor push token uploaded from service");
            } catch (Exception ignored) {
                Log.e(TAG, "service token upload failed", ignored);
                ClientDiagnostics.record(this, "service_token_upload_failed", "error", ignored.getMessage());
            }
        }).start();
    }

    @Override
    public void onMessageReceived(HonorPushDataMsg message) {
        Log.i(TAG, "service onMessageReceived");
        String raw = message == null ? null : message.getData();
        ClientDiagnostics.record(this, "service_message_received", "rawLength=" + (raw == null ? 0 : raw.length()));
        NotifyMessage notifyMessage = parseMessage(raw);
        new MessageStore(this).save(notifyMessage);
        NotificationHelper.show(this, notifyMessage);
    }

    private NotifyMessage parseMessage(String raw) {
        String id = "push-" + System.currentTimeMillis();
        String title = "新消息";
        String body = raw == null ? "" : raw;
        try {
            JSONObject data = new JSONObject(raw == null ? "{}" : raw);
            id = data.optString("id", id);
            title = data.optString("title", title);
            body = data.optString("body", body);
        } catch (Exception ignored) {
        }
        return new NotifyMessage(id, title, body, raw, System.currentTimeMillis());
    }
}

