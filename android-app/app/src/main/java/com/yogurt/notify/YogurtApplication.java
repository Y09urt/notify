package com.yogurt.notify;

import android.app.Application;

public class YogurtApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannel(this);
    }
}

