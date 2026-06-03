package com.ljq.notify.push;

import com.hihonor.push.sdk.HonorMessageService;
import com.hihonor.push.sdk.bean.HonorPushDataMsg;
import com.ljq.notify.MessageStore;
import com.ljq.notify.NotificationHelper;
import com.ljq.notify.NotifyMessage;
import com.ljq.notify.SettingsStore;
import com.ljq.notify.WorkerApi;

import org.json.JSONObject;

public class NotifyHonorPushService extends HonorMessageService {
    @Override
    public void onNewToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                SettingsStore settings = new SettingsStore(this);
                settings.setLastToken(token);
                new WorkerApi(settings).registerToken(token);
            } catch (Exception ignored) {
            }
        }).start();
    }

    @Override
    public void onMessageReceived(HonorPushDataMsg message) {
        String raw = message == null ? null : message.getData();
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
