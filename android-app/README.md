# Notify Android App

这是配合仓库根目录 Cloudflare Worker 使用的荣耀手机通知 App。

功能：

- 启动后请求通知权限。
- 首次打开强制注册/登录，并长期保存 session 凭证。
- 登录后获取荣耀 PushToken，并带 session 上传到 Worker 的 `/register`。
- 打开 App 后从 Worker 的 `/messages` 拉取当前账号的历史消息。
- 使用本地 SQLite 缓存已经展示过的消息。
- 收到透传消息时写入本地缓存并展示系统通知。
- 设置页可以修改 Worker URL。
- 侧边栏可以检查远程版本并跳转下载新版 APK。
- 支持查看消息详情、删除单条消息、清空本地缓存、添加本地测试消息。

## 使用步骤

1. 用 Android Studio 打开 `android-app/`。
2. 在荣耀开发者后台创建应用，包名使用 `com.yogurt.notify`，配置签名 SHA-256。
3. 下载 `mcs-services.json`，放到 `android-app/app/mcs-services.json`。
4. 新建 `android-app/local.properties`，写入荣耀后台的 App ID：

```properties
HONOR_PUSH_APPID=你的荣耀AppID
```

5. 检查 `AppConfig.java`：

```java
public static final String WORKER_BASE_URL = "https://notify.yogurts.top";
```

6. 连接荣耀 Magic7 Pro，运行 App。

荣耀认证没下来前，App 可以先测试 UI、历史消息拉取、D1 写入链路；真实 PushToken 和系统推送需要等 Push Kit 权限开通。
