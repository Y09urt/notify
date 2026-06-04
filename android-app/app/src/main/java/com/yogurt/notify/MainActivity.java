package com.yogurt.notify;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.yogurt.notify.push.HonorPushRegistrar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "YogurtApp";
    private static final int COLOR_BACKGROUND = 0xFFF5F7FB;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_PRIMARY = 0xFF2563EB;
    private static final int COLOR_PRIMARY_SOFT = 0xFFEFF6FF;
    private static final int COLOR_TEXT = 0xFF111827;
    private static final int COLOR_MUTED = 0xFF64748B;
    private static final int COLOR_BORDER = 0xFFE5E7EB;
    private static final int COLOR_SUCCESS = 0xFF059669;
    private static final int COLOR_WARNING = 0xFFD97706;
    private static final int COLOR_DANGER = 0xFFDC2626;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MessageStore store;
    private SettingsStore settings;
    private WorkerApi api;
    private LinearLayout messageList;
    private TextView statusView;
    private TextView countView;
    private View drawerScrim;
    private LinearLayout drawerPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");
        store = new MessageStore(this);
        settings = new SettingsStore(this);
        api = new WorkerApi(settings);
        buildUi();
        requestNotificationPermission();
        saveLaunchMessage();
        refreshLocal();
        if (settings.hasSession()) {
            validateSession();
        } else {
            setStatus("请先登录 Notify");
            showLoginDialog();
        }
    }

    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        int padding = dp(18);
        root.setPadding(padding, dp(18), padding, dp(12));

        TextView title = new TextView(this);
        title.setText("Notify");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("荣耀 Push 消息中心");
        subtitle.setTextSize(14);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText(statusText(settings.hasSession() ? "用户: " + settings.userId() : "未登录"));
        statusView.setTextSize(14);
        statusView.setTextColor(COLOR_PRIMARY);
        statusView.setMinHeight(dp(48));
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusView.setBackground(rounded(COLOR_PRIMARY_SOFT, COLOR_PRIMARY, 16));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.setMargins(0, 0, 0, dp(14));
        root.addView(statusView, statusParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        styleButton(refresh, COLOR_PRIMARY, 0xFFFFFFFF);
        refresh.setOnClickListener(v -> fetchHistory());
        actions.addView(refresh, actionParams(1, 0, 4));

        Button more = new Button(this);
        more.setText("☰");
        more.setContentDescription("打开菜单");
        styleButton(more, 0xFFFFFFFF, COLOR_PRIMARY);
        more.setTextSize(22);
        more.setOnClickListener(v -> openDrawer());
        actions.addView(more, iconActionParams());
        root.addView(actions, rowParams());

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        sectionHeader.setPadding(0, dp(12), 0, dp(8));

        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("最近消息");
        sectionTitle.setTextSize(18);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setTextColor(COLOR_TEXT);
        sectionHeader.addView(sectionTitle, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        countView = new TextView(this);
        countView.setText("0 条");
        countView.setTextSize(13);
        countView.setTextColor(COLOR_MUTED);
        sectionHeader.addView(countView);
        root.addView(sectionHeader);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, 0, 0, dp(8));
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(messageList);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        screen.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        buildDrawer(screen);
        setContentView(screen);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void validateSession() {
        setStatus("正在验证登录凭证...");
        executor.execute(() -> {
            try {
                String userId = api.checkSession();
                settings.setSession(userId, settings.sessionToken());
                api = new WorkerApi(settings);
                runOnUiThread(() -> {
                    setStatus("已登录: " + userId);
                    enterApp();
                });
            } catch (Exception error) {
                Log.e(TAG, "Session check failed", error);
                settings.clearSession();
                api = new WorkerApi(settings);
                runOnUiThread(() -> {
                    setStatus("登录已过期，请重新登录");
                    showLoginDialog();
                });
            }
        });
    }

    private void enterApp() {
        fetchHistory();
        requestPushToken();
    }

    private void showLoginDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        layout.setPadding(padding, padding, padding, 0);

        EditText idInput = new EditText(this);
        idInput.setHint("ID");
        idInput.setSingleLine(true);
        idInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        layout.addView(idInput);

        EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(passwordInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("登录 Notify")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("登录", null)
                .setNegativeButton("注册", null)
                .create();
        dialog.setOnShowListener(view -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button ->
                    authenticateAccount(dialog, idInput.getText().toString(), passwordInput.getText().toString(), false));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(button ->
                    authenticateAccount(dialog, idInput.getText().toString(), passwordInput.getText().toString(), true));
        });
        dialog.show();
    }

    private void authenticateAccount(AlertDialog dialog, String id, String password, boolean createAccount) {
        String action = createAccount ? "注册" : "登录";
        setStatus("正在" + action + "...");
        executor.execute(() -> {
            try {
                WorkerApi.AuthSession session = createAccount
                        ? api.registerAccount(id, password)
                        : api.login(id, password);
                settings.setSession(session.userId, session.sessionToken);
                api = new WorkerApi(settings);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    setStatus(action + "成功: " + session.userId);
                    enterApp();
                });
            } catch (Exception error) {
                Log.e(TAG, action + " failed", error);
                runOnUiThread(() -> setStatus(action + "失败: " + error.getMessage()));
            }
        });
    }

    private void logout() {
        setStatus("正在退出登录...");
        executor.execute(() -> {
            try {
                if (settings.hasSession()) {
                    api.logout();
                }
            } catch (Exception error) {
                Log.e(TAG, "Logout failed", error);
            }
            settings.clearSession();
            api = new WorkerApi(settings);
            runOnUiThread(() -> {
                refreshLocal();
                setStatus("已退出登录");
                showLoginDialog();
            });
        });
    }

    private void requestPushToken() {
        if (!settings.hasSession()) {
            showLoginDialog();
            return;
        }
        setStatus("正在请求荣耀 PushToken...");
        HonorPushRegistrar.requestToken(this, new HonorPushRegistrar.Callback() {
            @Override
            public void onToken(String token) {
                Log.i(TAG, "PushToken received: " + shortToken(token));
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
                Log.e(TAG, "PushToken failed: " + message);
                setStatus("PushToken 获取失败: " + message);
            }
        });
    }

    private void uploadLastToken() {
        if (!settings.hasSession()) {
            showLoginDialog();
            return;
        }
        String token = settings.lastToken();
        if (token.isEmpty()) {
            setStatus("还没有可重传的 PushToken");
            return;
        }
        setStatus("正在重传 PushToken...");
        executor.execute(() -> {
            try {
                Log.i(TAG, "Uploading last PushToken");
                api.registerToken(token);
                runOnUiThread(() -> setStatus("PushToken 已重传"));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("重传失败: " + error.getMessage()));
            }
        });
    }

    private void fetchHistory() {
        if (!settings.hasSession()) {
            showLoginDialog();
            return;
        }
        setStatus("正在拉取历史消息...");
        executor.execute(() -> {
            try {
                Log.i(TAG, "Fetching history for user " + settings.userId());
                List<NotifyMessage> messages = api.fetchMessages();
                Log.i(TAG, "Fetched messages: " + messages.size());
                for (NotifyMessage message : messages) {
                    store.save(message);
                }
                runOnUiThread(() -> {
                    refreshLocal();
                    setStatus("历史消息已同步: " + messages.size() + " 条");
                });
            } catch (Exception error) {
                Log.e(TAG, "Fetch history failed", error);
                runOnUiThread(() -> setStatus("历史消息同步失败: " + error.getMessage()));
            }
        });
    }

    private void refreshLocal() {
        messageList.removeAllViews();
        List<NotifyMessage> messages = store.latest(100);
        if (countView != null) {
            countView.setText(messages.size() + " 条");
        }
        if (messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无消息\n收到荣耀 Push 后会显示在这里");
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(COLOR_MUTED);
            empty.setLineSpacing(dp(3), 1.0f);
            empty.setPadding(dp(18), dp(42), dp(18), dp(42));
            empty.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, 18));
            messageList.addView(empty, cardParams());
            return;
        }

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        for (NotifyMessage message : messages) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, 18));
            card.setOnClickListener(v -> showMessageDialog(message));

            TextView itemTitle = new TextView(this);
            itemTitle.setText(message.title);
            itemTitle.setTextSize(16);
            itemTitle.setTypeface(Typeface.DEFAULT_BOLD);
            itemTitle.setTextColor(COLOR_TEXT);
            itemTitle.setSingleLine(true);
            itemTitle.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(itemTitle);

            TextView itemBody = new TextView(this);
            itemBody.setText(message.body);
            itemBody.setTextSize(14);
            itemBody.setTextColor(COLOR_MUTED);
            itemBody.setMaxLines(2);
            itemBody.setEllipsize(TextUtils.TruncateAt.END);
            itemBody.setPadding(0, dp(6), 0, dp(8));
            card.addView(itemBody);

            TextView itemTime = new TextView(this);
            itemTime.setText(format.format(new Date(message.createdAt)));
            itemTime.setTextSize(12);
            itemTime.setTextColor(0xFF94A3B8);
            card.addView(itemTime);

            messageList.addView(card, cardParams());
        }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams actionParams(int weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(44),
                weight
        );
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private LinearLayout.LayoutParams iconActionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(54),
                dp(44)
        );
        params.setMargins(dp(4), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private void styleButton(Button button, int backgroundColor, int textColor) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setBackground(rounded(backgroundColor, backgroundColor == COLOR_PRIMARY || backgroundColor == COLOR_SUCCESS
                ? backgroundColor
                : COLOR_BORDER, 14));
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private String statusText(String text) {
        return "●  " + text;
    }

    private int statusColor(String text) {
        if (text.contains("失败") || text.contains("错误")) {
            return COLOR_DANGER;
        }
        if (text.contains("正在") || text.contains("还没有")) {
            return COLOR_WARNING;
        }
        return COLOR_PRIMARY;
    }

    private void buildDrawer(FrameLayout screen) {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        screen.addView(drawerScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(20), dp(28), dp(20), dp(20));
        drawerPanel.setBackgroundColor(COLOR_SURFACE);
        drawerPanel.setVisibility(View.GONE);
        drawerPanel.setTranslationX(-dp(292));
        if (Build.VERSION.SDK_INT >= 21) {
            drawerPanel.setElevation(dp(10));
        }

        TextView title = new TextView(this);
        title.setText("Notify");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        drawerPanel.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("荣耀 Push 管理");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setPadding(0, dp(2), 0, dp(20));
        drawerPanel.addView(subtitle);

        drawerPanel.addView(drawerItem("获取 PushToken", COLOR_SUCCESS, this::requestPushToken));
        drawerPanel.addView(drawerItem("重传 PushToken", COLOR_TEXT, this::uploadLastToken));
        drawerPanel.addView(drawerItem("设置服务器", COLOR_PRIMARY, this::showSettingsDialog));
        drawerPanel.addView(drawerItem("检查更新", COLOR_PRIMARY, this::checkForUpdates));
        drawerPanel.addView(drawerItem("添加本地测试消息", COLOR_WARNING, this::addLocalTestMessage));
        drawerPanel.addView(drawerItem("清空本地消息", COLOR_DANGER, this::confirmClearMessages));
        drawerPanel.addView(drawerItem("退出登录", COLOR_DANGER, this::logout));

        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                dp(292),
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        drawerParams.gravity = Gravity.START;
        screen.addView(drawerPanel, drawerParams);
    }

    private TextView drawerItem(String text, int color, Runnable action) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextSize(16);
        item.setTextColor(color);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), 0, dp(14), 0);
        item.setBackground(rounded(0xFFFFFFFF, COLOR_BORDER, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        params.setMargins(0, 0, 0, dp(10));
        item.setLayoutParams(params);
        item.setOnClickListener(v -> {
            closeDrawer();
            action.run();
        });
        return item;
    }

    private void openDrawer() {
        if (drawerPanel == null || drawerScrim == null) {
            return;
        }
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.animate().translationX(0).setDuration(180).start();
    }

    private void closeDrawer() {
        if (drawerPanel == null || drawerScrim == null) {
            return;
        }
        drawerScrim.setVisibility(View.GONE);
        drawerPanel.animate()
                .translationX(-dp(292))
                .setDuration(160)
                .withEndAction(() -> drawerPanel.setVisibility(View.GONE))
                .start();
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

        TextView userId = new TextView(this);
        userId.setText(settings.hasSession() ? "账号: " + settings.userId() : "账号: 未登录");
        userId.setTextColor(COLOR_MUTED);
        userId.setPadding(0, dp(12), 0, 0);
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
                    api = new WorkerApi(settings);
                    setStatus("设置已保存。用户: " + settings.userId());
                    fetchHistory();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void checkForUpdates() {
        setStatus("正在检查更新...");
        executor.execute(() -> {
            try {
                WorkerApi.UpdateInfo update = api.fetchUpdateInfo();
                int currentVersionCode = currentVersionCode();
                runOnUiThread(() -> showUpdateResult(update, currentVersionCode));
            } catch (Exception error) {
                Log.e(TAG, "Check update failed", error);
                runOnUiThread(() -> setStatus("检查更新失败: " + error.getMessage()));
            }
        });
    }

    private void showUpdateResult(WorkerApi.UpdateInfo update, int currentVersionCode) {
        if (update.versionCode <= currentVersionCode) {
            setStatus("已是最新版本");
            new AlertDialog.Builder(this)
                    .setTitle("已是最新版本")
                    .setMessage("当前版本已经是最新。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        String notes = update.releaseNotes == null || update.releaseNotes.isEmpty()
                ? "发现新版本。"
                : update.releaseNotes;
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + update.versionName)
                .setMessage(notes)
                .setPositiveButton("下载", (dialog, which) -> openDownloadUrl(update.downloadUrl))
                .setNegativeButton("稍后", null)
                .show();
        setStatus("发现新版本: " + update.versionName);
    }

    private void openDownloadUrl(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            setStatus("更新包下载地址未配置");
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl.trim())));
    }

    private int currentVersionCode() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= 28) {
            return (int) info.getLongVersionCode();
        }
        return info.versionCode;
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
        Log.i(TAG, "status=" + text);
        runOnUiThread(() -> {
            int color = statusColor(text);
            statusView.setText(statusText(text));
            statusView.setTextColor(color);
            statusView.setBackground(rounded(softColor(color), color, 16));
        });
    }

    private int softColor(int color) {
        if (color == COLOR_DANGER) {
            return 0xFFFEF2F2;
        }
        if (color == COLOR_WARNING) {
            return 0xFFFFFBEB;
        }
        return COLOR_PRIMARY_SOFT;
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

