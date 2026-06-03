package com.ljq.notify;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ljq.notify.push.HonorPushRegistrar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MessageStore store;
    private LinearLayout messageList;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MessageStore(this);
        buildUi();
        requestNotificationPermission();
        saveLaunchMessage();
        refreshLocal();
        fetchHistory();
        requestPushToken();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Notify");
        title.setTextSize(24);
        title.setTextColor(0xFF111827);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("用户: " + AppConfig.USER_ID);
        statusView.setTextColor(0xFF4B5563);
        root.addView(statusView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("刷新消息");
        refresh.setOnClickListener(v -> fetchHistory());
        actions.addView(refresh);

        Button register = new Button(this);
        register.setText("注册推送");
        register.setOnClickListener(v -> requestPushToken());
        actions.addView(register);
        root.addView(actions);

        ScrollView scrollView = new ScrollView(this);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(messageList);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void requestPushToken() {
        setStatus("正在请求荣耀 PushToken...");
        HonorPushRegistrar.requestToken(this, new HonorPushRegistrar.Callback() {
            @Override
            public void onToken(String token) {
                setStatus("PushToken 已获取，正在上传...");
                executor.execute(() -> {
                    try {
                        WorkerApi.registerToken(token);
                        runOnUiThread(() -> setStatus("PushToken 已注册到 Worker"));
                    } catch (Exception error) {
                        runOnUiThread(() -> setStatus("注册失败: " + error.getMessage()));
                    }
                });
            }

            @Override
            public void onError(String message) {
                setStatus("PushToken 获取失败: " + message);
            }
        });
    }

    private void fetchHistory() {
        setStatus("正在拉取历史消息...");
        executor.execute(() -> {
            try {
                List<NotifyMessage> messages = WorkerApi.fetchMessages();
                for (NotifyMessage message : messages) {
                    store.save(message);
                }
                runOnUiThread(() -> {
                    refreshLocal();
                    setStatus("历史消息已同步: " + messages.size() + " 条");
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("历史消息同步失败: " + error.getMessage()));
            }
        });
    }

    private void refreshLocal() {
        messageList.removeAllViews();
        List<NotifyMessage> messages = store.latest(100);
        if (messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无消息");
            empty.setTextColor(0xFF6B7280);
            empty.setPadding(0, dp(24), 0, 0);
            messageList.addView(empty);
            return;
        }

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        for (NotifyMessage message : messages) {
            TextView item = new TextView(this);
            item.setText(message.title + "\n" + message.body + "\n" + format.format(new Date(message.createdAt)));
            item.setTextSize(16);
            item.setTextColor(0xFF111827);
            item.setPadding(0, dp(12), 0, dp(12));
            messageList.addView(item);

            View divider = new View(this);
            divider.setBackgroundColor(0xFFE5E7EB);
            messageList.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
            ));
        }
    }

    private void saveLaunchMessage() {
        String title = getIntent().getStringExtra("message_title");
        String body = getIntent().getStringExtra("message_body");
        if (title == null || body == null) {
            return;
        }
        String id = getIntent().getStringExtra("message_id");
        if (id == null) {
            id = "launch-" + System.currentTimeMillis();
        }
        store.save(new NotifyMessage(
                id,
                title,
                body,
                getIntent().getStringExtra("message_data"),
                System.currentTimeMillis()
        ));
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
