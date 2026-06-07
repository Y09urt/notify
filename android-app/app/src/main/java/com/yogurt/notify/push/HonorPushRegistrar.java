package com.yogurt.notify.push;

import android.content.Context;
import android.util.Log;

import com.hihonor.push.sdk.HonorPushCallback;
import com.hihonor.push.sdk.HonorPushClient;

public final class HonorPushRegistrar {
    private static final String TAG = "YogurtApp";

    public interface Callback {
        void onToken(String token);

        void onError(String message);

        default void onInfo(String message) {
        }
    }

    private interface NextStep {
        void run();
    }

    private HonorPushRegistrar() {
    }

    public static void requestToken(Context context, Callback callback) {
        try {
            Context appContext = context.getApplicationContext();
            HonorPushClient client = HonorPushClient.getInstance();
            client.init(appContext, true);
            if (!client.checkSupportHonorPush(appContext)) {
                callback.onError("this device does not support Honor Push");
                return;
            }
            ensureNotificationCenter(client, callback, () -> requestPushToken(client, callback));
        } catch (Exception error) {
            callback.onError(error.getMessage());
        }
    }

    private static void ensureNotificationCenter(HonorPushClient client, Callback callback, NextStep nextStep) {
        client.getNotificationCenterStatus(new HonorPushCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean enabled) {
                Log.i(TAG, "Honor notification center enabled=" + enabled);
                if (Boolean.TRUE.equals(enabled)) {
                    callback.onInfo("荣耀通知中心已开启");
                    nextStep.run();
                    return;
                }

                callback.onInfo("正在开启荣耀通知中心...");
                client.turnOnNotificationCenter(new HonorPushCallback<Void>() {
                    @Override
                    public void onSuccess(Void ignored) {
                        Log.i(TAG, "Honor notification center turned on");
                        callback.onInfo("荣耀通知中心已开启");
                        nextStep.run();
                    }

                    @Override
                    public void onFailure(int errorCode, String errorMessage) {
                        Log.w(TAG, "Turn on Honor notification center failed: " + errorCode + " " + errorMessage);
                        callback.onInfo("荣耀通知中心开启失败: " + errorCode + " " + errorMessage);
                        nextStep.run();
                    }
                });
            }

            @Override
            public void onFailure(int errorCode, String errorMessage) {
                Log.w(TAG, "Query Honor notification center failed: " + errorCode + " " + errorMessage);
                callback.onInfo("荣耀通知中心状态读取失败: " + errorCode + " " + errorMessage);
                nextStep.run();
            }
        });
    }

    private static void requestPushToken(HonorPushClient client, Callback callback) {
        client.getPushToken(new HonorPushCallback<String>() {
            @Override
            public void onSuccess(String token) {
                if (token == null || token.trim().isEmpty()) {
                    callback.onError("empty token");
                } else {
                    callback.onToken(token);
                }
            }

            @Override
            public void onFailure(int errorCode, String errorMessage) {
                callback.onError(errorCode + " " + errorMessage);
            }
        });
    }
}

