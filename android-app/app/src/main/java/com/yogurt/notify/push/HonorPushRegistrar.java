package com.yogurt.notify.push;

import android.content.Context;

import com.hihonor.push.sdk.HonorPushCallback;
import com.hihonor.push.sdk.HonorPushClient;

public final class HonorPushRegistrar {
    public interface Callback {
        void onToken(String token);

        void onError(String message);
    }

    private HonorPushRegistrar() {
    }

    public static void requestToken(Context context, Callback callback) {
        try {
            HonorPushClient client = HonorPushClient.getInstance();
            client.init(context, true);
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
        } catch (Exception error) {
            callback.onError(error.getMessage());
        }
    }
}

