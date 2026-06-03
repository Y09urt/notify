# Cloudflare + 荣耀 Push Kit 手机推送

这个项目用 Cloudflare Workers + D1 + Queues 做一个无自建服务器的荣耀手机推送后端。

流程是：

1. 荣耀手机 App 集成 Honor Push Kit。
2. App 获取荣耀 PushToken。
3. App 调用 Worker 的 `POST /register`，把 PushToken 注册到 D1。
4. 管理端或业务系统调用 Worker 的 `POST /push`。
5. Worker 写入 Cloudflare Queue。
6. Queue consumer 调用荣耀 Push 服务端接口，下发通知到手机。

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

注册荣耀 PushToken：

```bash
curl -X POST "https://你的-worker域名/register" \
  -H "content-type: application/json" \
  -d '{"userId":"u_1001","platform":"honor","token":"HONOR_PUSH_TOKEN"}'
```

给用户推送：

```bash
curl -X POST "https://你的-worker域名/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的ADMIN_TOKEN" \
  -d '{"userId":"u_1001","title":"新消息","body":"你有一条新通知","data":{"url":"/messages/1"}}'
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

手机端拿到荣耀 PushToken 后调用 `/register`。用户退出登录、切换账号或 PushToken 刷新时，需要重新注册。

生产环境不要让 `/register` 完全裸奔，建议接入你的登录态、签名或一次性绑定码，避免别人把无关 token 写进你的 D1。
