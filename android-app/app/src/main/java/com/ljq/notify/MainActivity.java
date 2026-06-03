package com.ljq.notify;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private SettingsStore settings;
    private WorkerApi api;
    private LinearLayout messageList;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MessageStore(this);
        settings = new SettingsStore(this);
        api = new WorkerApi(settings);
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
        statusView.setText("用户: " + settings.userId());
        statusView.setTextColor(0xFF4B5563);
        root.addView(statusView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> fetchHistory());
        actions.addView(refresh);

        Button register = new Button(this);
        register.setText("注册");
        register.setOnClickListener(v -> requestPushToken());
        actions.addView(register);

        Button settingsButton = new Button(this);
        settingsButton.setText("设置");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        actions.addView(settingsButton);
        root.addView(actions);

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        Button uploadToken = new Button(this);
        uploadToken.setText("重传Token");
        uploadToken.setOnClickListener(v -> uploadLastToken());
        secondaryActions.addView(uploadToken);

        Button clear = new Button(this);
        clear.setText("清空本地");
        clear.setOnClickListener(v -> confirmClearMessages());
        secondaryActions.addView(clear);

        Button localTest = new Button(this);
        localTest.setText("本地测试");
        localTest.setOnClickListener(v -> addLocalTestMessage());
        secondaryActions.addView(localTest);
        root.addView(secondaryActions);

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
                settings.setLastToken(token);
                setStatus("PushToken 已获取，正在上传...");
                executor.execute(() -> {
                    try {
                        api.registerToken(token);
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

    private void uploadLastToken() {
        String token = settings.lastToken();
        if (token.isEmpty()) {
            setStatus("还没有可重传的 PushToken");
            return;
        }
        setStatus("正在重传 PushToken...");
        executor.execute(() -> {
            try {
                api.registerToken(token);
                runOnUiThread(() -> setStatus("PushToken 已重传"));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("重传失败: " + error.getMessage()));
            }
        });
    }

    private void fetchHistory() {
        setStatus("正在拉取历史消息...");
        executor.execute(() -> {
            try {
                List<NotifyMessage> messages = api.fetchMessages();
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
            item.setOnClickListener(v -> showMessageDialog(message));
            messageList.addView(item);

            View divider = new View(this);
            divider.setBackgroundColor(0xFFE5E7EB);
            messageList.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
            ));
        }
    }

    private void showMessageDialog(NotifyMessage message) {
        String text = message.body;
        if (message.data != null && !"null".equals(message.data)) {
            text += "\n\nData:\n" + message.data;
        }
        new AlertDialog.Builder(this)
                .setTitle(message.title)
                .setMessage(text)
                .setPositiveButton("确定", null)
                .setNegativeButton("删除", (dialog, which) -> {
                    store.delete(message.id);
                    refreshLocal();
                })
                .show();
    }

    private void showSettingsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        layout.setPadding(padding, padding, padding, 0);

        EditText workerUrl = new EditText(this);
        workerUrl.setHint("Worker URL");
        workerUrl.setSingleLine(true);
        workerUrl.setText(settings.workerUrl());
        layout.addView(workerUrl);

        EditText userId = new EditText(this);
        userId.setHint("User ID");
        userId.setSingleLine(true);
        userId.setText(settings.userId());
        layout.addView(userId);

        TextView token = new TextView(this);
        String lastToken = settings.lastToken();
        token.setText(lastToken.isEmpty() ? "暂无 PushToken" : "Token: " + shortToken(lastToken));
        token.setTextColor(0xFF4B5563);
        token.setPadding(0, dp(12), 0, 0);
        layout.addView(token);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    settings.setWorkerUrl(workerUrl.getText().toString());
                    settings.setUserId(userId.getText().toString());
                    api = new WorkerApi(settings);
                    setStatus("设置已保存。用户: " + settings.userId());
                    fetchHistory();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearMessages() {
        new AlertDialog.Builder(this)
                .setTitle("清空本地消息")
                .setMessage("只会清空当前手机缓存，不会删除 Cloudflare D1 里的历史记录。")
                .setPositiveButton("清空", (dialog, which) -> {
                    store.clear();
                    refreshLocal();
                    setStatus("本地消息已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addLocalTestMessage() {
        NotifyMessage message = new NotifyMessage(
                "local-" + System.currentTimeMillis(),
                "本地测试消息",
                "这条消息只保存在当前手机，用于在荣耀 Push 开通前测试界面。",
                "{\"source\":\"local-test\"}",
                System.currentTimeMillis()
        );
        store.save(message);
        refreshLocal();
        setStatus("已添加本地测试消息");
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

    private String shortToken(String token) {
        if (token.length() <= 16) {
            return token;
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 8);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
