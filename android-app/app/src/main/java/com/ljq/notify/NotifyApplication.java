package com.ljq.notify;

import android.app.Application;

public class NotifyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannel(this);
    }
}
