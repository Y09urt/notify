# Cloudflare + 荣耀 Push Kit 手机推送

这个项目用 Cloudflare Workers + D1 + Queues 做一个无自建服务器的荣耀手机推送后端。

流程是：

1. 荣耀手机 App 集成 Honor Push Kit。
2. App 获取荣耀 PushToken。
3. App 首次打开后注册/登录，拿到长期 session 凭证。
4. App 调用 Worker 的 `POST /register`，带 session 把 PushToken 注册到 D1。
5. 管理端或业务系统调用 Worker 的 `POST /push`。
6. Worker 写入 Cloudflare Queue。
7. Queue consumer 调用荣耀 Push 服务端接口，下发通知到手机。

## 初始化

安装依赖：

```bash
npm install
```

创建 D1：

```bash
npx wrangler d1 create notify-db
```

把返回的 `database_id` 填到 `wrangler.toml`。

创建 Queue：

```bash
npm run queue:create
```

初始化表：

```bash
npm run db:init
```

配置 Worker 密钥：

```bash
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put HONOR_APP_ID
npx wrangler secret put HONOR_CLIENT_ID
npx wrangler secret put HONOR_CLIENT_SECRET
```

`HONOR_APP_ID`、`HONOR_CLIENT_ID`、`HONOR_CLIENT_SECRET` 来自荣耀开发者服务平台里的应用信息。

也可以把 `.env` 里的占位值改成真实值后一次性导入：

```bash
npx wrangler secret bulk .env
```

如果荣耀官方文档里的接口域名和当前默认值不同，可以额外配置：

```bash
npx wrangler secret put HONOR_TOKEN_URL
npx wrangler secret put HONOR_SEND_URL
```

`HONOR_SEND_URL` 支持写成：

```text
https://push-api.cloud.hihonor.com/v1/{appId}/messages:send
```

Worker 会自动把 `{appId}` 替换成 `HONOR_APP_ID`。

远程更新检查区分 Debug 版和正式版。Debug APK 会请求 `channel=debug`，正式 APK 会请求 `channel=release`。

Debug 版使用这些 Worker 变量：

```bash
npx wrangler secret put APP_DEBUG_LATEST_VERSION_CODE
npx wrangler secret put APP_DEBUG_LATEST_VERSION_NAME
npx wrangler secret put APP_DEBUG_DOWNLOAD_URL
npx wrangler secret put APP_DEBUG_RELEASE_NOTES
```

正式版使用这些 Worker 变量：

```bash
npx wrangler secret put APP_RELEASE_LATEST_VERSION_CODE
npx wrangler secret put APP_RELEASE_LATEST_VERSION_NAME
npx wrangler secret put APP_RELEASE_DOWNLOAD_URL
npx wrangler secret put APP_RELEASE_NOTES
```

其中下载地址可以填 GitHub Release、Cloudflare R2 或其他 HTTPS APK 下载地址。

## 部署到 Cloudflare

第一次部署建议按这个顺序：

```bash
npx wrangler login
npx wrangler d1 create notify-db
```

把 `d1 create` 返回的 `database_id` 填到 `wrangler.toml`：

```toml
database_id = "你的-d1-database-id"
```

然后创建 Queue：

```bash
npm run queue:create
```

初始化线上 D1 表：

```bash
npm run db:init
```

把 `.env` 里的密钥写入 Cloudflare：

```bash
npx wrangler secret bulk .env
```

最后部署：

```bash
npm run deploy
```

部署成功后，Wrangler 会输出 Worker 访问地址，例如：

```text
https://notify-worker.你的子域.workers.dev
```

用这个地址调用 `/health`：

```bash
curl https://notify-worker.你的子域.workers.dev/health
```

## API

注册账号：

```bash
curl -X POST "https://你的-worker域名/auth/register" \
  -H "content-type: application/json" \
  -d '{"id":"yogurt","password":"至少6位密码"}'
```

登录：

```bash
curl -X POST "https://你的-worker域名/auth/login" \
  -H "content-type: application/json" \
  -d '{"id":"yogurt","password":"至少6位密码"}'
```

注册荣耀 PushToken：

```bash
curl -X POST "https://你的-worker域名/register" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的SESSION_TOKEN" \
  -d '{"platform":"honor","token":"HONOR_PUSH_TOKEN"}'
```

给用户推送：

```bash
curl -X POST "https://你的-worker域名/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的ADMIN_TOKEN" \
  -d '{"userId":"yogurt","title":"新消息","body":"你有一条新通知","data":{"url":"/messages/1"}}'
```

直接给某个 PushToken 推送：

```bash
curl -X POST "https://你的-worker域名/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的ADMIN_TOKEN" \
  -d '{"platform":"honor","token":"HONOR_PUSH_TOKEN","title":"测试","body":"Cloudflare Worker 发出的荣耀通知"}'
```

## Android 端要做什么

在荣耀开发者服务平台创建 Android 应用，开通 Push Kit，配置包名和签名证书指纹，然后下载 `mcs-services.json` 放到 Android 项目的 `app/` 目录。

手机端注册/登录成功后拿到 sessionToken，再获取荣耀 PushToken 并调用 `/register`。用户退出登录、切换账号或 PushToken 刷新时，需要重新注册 PushToken。

`/register` 和 `/messages` 已经要求 session 登录态，避免别人把无关 token 写进你的 D1。

## Android App

仓库里的 `android-app/` 是一个原生 Android App 工程。它会：

1. 首次打开强制注册/登录，并保存 sessionToken。
2. 获取荣耀 PushToken 并带 session 上传到 `/register`。
3. 打开 App 时调用 `/messages` 拉取当前账号的历史消息。
4. 使用 SQLite 缓存已经展示过的消息。
5. 收到透传消息时写入本地缓存并展示系统通知。

新增历史消息表后，需要重新初始化远程 D1：

```bash
npm run db:init
```

然后重新部署 Worker：

```bash
npm run deploy
```
