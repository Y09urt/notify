# Notify Android App

这是配合仓库根目录 Cloudflare Worker 使用的荣耀手机通知 App。

功能：

- 启动后请求通知权限。
- 获取荣耀 PushToken，并上传到 Worker 的 `/register`。
- 打开 App 后从 Worker 的 `/messages` 拉取历史消息。
- 使用本地 SQLite 缓存已经展示过的消息。
- 收到透传消息时写入本地缓存并展示系统通知。
- 设置页可以修改 Worker URL 和 User ID。
- 支持查看消息详情、删除单条消息、清空本地缓存、添加本地测试消息。

## 使用步骤

1. 用 Android Studio 打开 `android-app/`。
2. 在荣耀开发者后台创建应用，包名使用 `com.ljq.notify`，配置签名 SHA-256。
3. 下载 `mcs-services.json`，放到 `android-app/app/mcs-services.json`。
4. 新建 `android-app/local.properties`，写入荣耀后台的 App ID：

```properties
HONOR_PUSH_APPID=你的荣耀AppID
```

5. 检查 `AppConfig.java`：

```java
public static final String WORKER_BASE_URL = "https://notify.ljq1515109607.workers.dev";
public static final String USER_ID = "u_1001";
```

6. 连接荣耀 Magic7 Pro，运行 App。

荣耀认证没下来前，App 可以先测试 UI、历史消息拉取、D1 写入链路；真实 PushToken 和系统推送需要等 Push Kit 权限开通。
