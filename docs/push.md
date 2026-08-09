# Push 接口调用文档

本文档只说明如何调用 Notify Worker 的 `POST /push` 接口。

## 接口地址

```text
POST https://你的-worker-域名/push
```

示例：

```text
POST https://notify.yogurts.top/push
```

如果使用 Cloudflare Workers 默认域名：

```text
POST https://notify-worker.你的子域.workers.dev/push
```

## 请求头

必须使用 JSON 请求体：

```http
content-type: application/json
```

`/push` 需要管理员密钥 `ADMIN_TOKEN`。下面两种鉴权方式任选一种。

使用 `authorization`：

```http
authorization: Bearer 你的_ADMIN_TOKEN
```

或者使用 `x-admin-token`：

```http
x-admin-token: 你的_ADMIN_TOKEN
```

## 请求体

请求体必须是 JSON 对象。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `title` | string | 是 | 通知标题 |
| `body` | string | 是 | 通知正文 |
| `userId` | string | 目标字段之一 | 推送给指定 Notify 账号 |
| `groupId` | string | 目标字段之一 | 推送给指定用户组 ID |
| `groupName` | string | 目标字段之一 | 推送给指定用户组名称 |
| `token` | string | 目标字段之一 | 直接推送给指定荣耀 PushToken |
| `platform` | string | 否 | 默认 `honor`，当前只支持 `honor` |
| `sender` | string | 否 | 发送者名称，会显示在 App 消息列表和详情中 |
| `data` | object | 否 | 透传数据 |

目标字段至少提供一个：

```text
userId / groupId / groupName / token
```

`groupId` 和 `groupName` 不能同时提供。

发送者也可以通过请求头传：

```http
x-notify-sender: 发送者名称
```

如果同时传了请求体 `sender` 和请求头 `x-notify-sender`，优先使用请求体里的 `sender`。

## 来源信息

调用 `/push` 时，服务端会自动给消息追加来源信息，放在 `data._source` 中。调用方不需要手动传这些字段。

自动追加的字段：

| 字段 | 说明 |
| --- | --- |
| `requestId` | 本次推送请求 ID |
| `sentAt` | 服务端接收请求的 ISO 时间 |
| `sender` | 发送者名称，来自请求体 `sender` 或请求头 `x-notify-sender` |
| `channel` | 来源通道，外部 `/push` 为 `push` |
| `ip` | 调用方 IP，优先读取 `cf-connecting-ip` |
| `country` | Cloudflare 识别到的来源国家或地区 |
| `userAgent` | 调用方 `User-Agent` |
| `authType` | 使用的鉴权头，可能是 `authorization` 或 `x-admin-token` |
| `signature` | 服务端用 `ADMIN_TOKEN` 对请求摘要生成的 HMAC-SHA256 签名 |

如果请求中已经手动传了 `data._source`，服务端不会覆盖它。

App 的消息详情页会把 `_source` 展示成独立的 `Source` 区域，同时保留完整 `Data` JSON。

## 最小调用示例

按用户推送：

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "userId": "你的Notify账号ID",
    "sender": "监控系统",
    "title": "测试标题",
    "body": "测试内容"
  }'
```

## 按用户推送

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "userId": "yogurt",
    "sender": "Server-A",
    "title": "新消息",
    "body": "你有一条新通知"
  }'
```

使用 `userId` 推送时，目标用户需要已经在 App 登录过，并完成 PushToken 注册。按用户推送会写入历史消息，App 刷新时可以同步到消息列表。

## 按用户组 ID 推送

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "groupId": "admins",
    "sender": "运维平台",
    "title": "群发消息",
    "body": "这条消息会发给 admins 用户组"
  }'
```

按用户组推送会给组内所有已注册 PushToken 的用户发送通知，并为每个用户写入历史消息。

## 按用户组名称推送

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "groupName": "管理员",
    "sender": "告警中心",
    "title": "群发消息",
    "body": "这条消息会发给名为 管理员 的用户组"
  }'
```

如果存在多个同名用户组，接口会返回错误。此时改用 `groupId`。

## 按 PushToken 直接推送

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "platform": "honor",
    "token": "HONOR_PUSH_TOKEN",
    "sender": "调试脚本",
    "title": "测试",
    "body": "Cloudflare Worker 发出的荣耀通知"
  }'
```

按 `token` 直接推送只适合临时测试设备通道。因为请求里没有用户归属，所以不会写入用户历史消息。

## 携带透传数据

`data` 必须是 JSON 对象。

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "userId": "yogurt",
    "sender": "任务系统",
    "title": "新消息",
    "body": "你有一条新通知",
    "data": {
      "url": "/messages/1",
      "source": "api"
    }
  }'
```

`data` 中的字符串会原样保存和下发；非字符串值会被转换成 JSON 字符串。

服务端实际保存和下发时会自动合并 `_source`，效果类似：

```json
{
  "url": "/messages/1",
  "source": "api",
  "_source": {
    "requestId": "9acb0b1b-5e84-4c90-a793-2c4b7f6a4f99",
    "sentAt": "2026-08-09T10:30:00.000Z",
    "sender": "任务系统",
    "channel": "push",
    "ip": "203.0.113.10",
    "country": "CN",
    "userAgent": "curl/8.0.0",
    "authType": "authorization",
    "signature": "hmac-sha256签名"
  }
}
```

## 正文格式标注

`body` 支持简单的文本标记。请求格式不变，仍然把带标记的内容放进 `body` 字符串里。

| 写法 | App 中的显示效果 |
| --- | --- |
| `**重点**` | 加粗 |
| `!!紧急!!` | 红色加粗 |
| `` `code` `` | 等宽蓝色文本 |
| `# 标题` | 段落标题 |
| `- 内容` | 项目符号 |

示例：

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "userId": "yogurt",
    "title": "任务异常",
    "body": "# 京东签到失败\n- 账号：**pt_pin_001**\n- 状态：!!Cookie 已失效!!\n- 建议：重新登录并更新 `cookie`"
  }'
```

App 的消息列表和消息详情会显示格式化效果。系统通知栏由服务端下发纯文本，会自动去掉标记符号，并在正文前显示发送者：

```text
From: 任务系统
京东签到失败
• 账号：pt_pin_001
• 状态：Cookie 已失效
• 建议：重新登录并更新 cookie
```

## 通知栏优化配置

荣耀系统通知栏不解析 `**加粗**`、`!!红色!!` 这类自定义标记。服务端会把通知栏内容自动转换成干净文本，同时保留原始 `body` 给 App 内消息列表和详情页渲染。

可以在 `data.notification` 中配置荣耀通知栏字段：

通知栏字段建议只放短摘要，完整明细放在顶层 `body` 或 `data.detail`，交给 App 内消息详情页展示。

荣耀官方 FAQ 对通知标题和正文的说明是：`title` 和 `body` 没有单独长度限制，但整个消息体除去 `pushToken` 后不能超过 `4K`；标题、正文过长时，设备端会展示不全，超出部分用省略号代替。因此这里按“官方硬限制 + 项目保守建议”来控制：

| 字段 | 官方限制 | 项目建议 |
| --- | --- | --- |
| 整个消息体，不含 `pushToken` | 不超过 `4K` | 尽量控制在 `3K` 以内，给来源信息、按钮、角标等字段留余量 |
| `title` / `bigTitle` | 未给单字段上限；过长会省略显示 | 40 个中文字符以内 |
| `body` / `notifySummary` | 未给单字段上限；过长会省略显示 | 80 个中文字符以内 |
| `bigBody` | 未给单字段上限，但计入总消息体 `4K` | 180 个中文字符以内，最多 4 行 |
| `image` | 计入总消息体 `4K` | 只填短 HTTPS URL，避免过长查询参数 |
| `buttons[].name` | 计入总消息体 `4K` | 8 个中文字符以内 |

如果内容超过项目建议长度，优先裁剪通知栏摘要，不要裁剪顶层 `body`。顶层 `body` 会保存在历史消息里，App 内仍可展示完整明细和格式标注。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `style` | number | `0` 默认样式，`1` 大文本样式；不传时会根据正文长度和换行自动选择 |
| `bigTitle` | string | 大文本样式标题 |
| `bigBody` | string | 大文本样式正文 |
| `image` | string | 通知图片 HTTPS URL |
| `buttons` | array | 通知按钮，最多 3 个 |
| `badgeNotification` | object | 角标配置 |
| `importance` | string | 通知分类，当前建议 `NORMAL` |
| `clickAction` | object | 点击通知后的动作 |
| `foregroundShow` | boolean | App 前台时是否展示通知，默认 `true` |
| `useDefaultVibrate` | boolean | 使用默认震动，默认 `true` |
| `useDefaultLight` | boolean | 使用默认呼吸灯，默认 `true` |
| `visibility` | string | 锁屏可见性，默认 `PUBLIC` |
| `notifySummary` | string | 通知摘要 |
| `notifyId` | number | 通知 ID，相同 ID 可覆盖旧通知 |
| `ttl` | string | 消息有效期，例如 `3600s` |

完整示例：

```bash
curl -X POST "https://notify.yogurts.top/push" \
  -H "content-type: application/json" \
  -H "authorization: Bearer 你的_ADMIN_TOKEN" \
  -d '{
    "userId": "yogurt",
    "sender": "监控系统",
    "title": "任务异常",
    "body": "# 京东签到失败\n- 账号：**pt_pin_001**\n- 状态：!!Cookie 已失效!!\n- 建议：重新登录并更新 `cookie`",
    "data": {
      "url": "/messages/1",
      "notification": {
        "style": 1,
        "bigTitle": "京东签到失败",
        "bigBody": "账号：pt_pin_001\n状态：Cookie 已失效\n建议：重新登录并更新 cookie",
        "image": "https://example.com/notify/banner.png",
        "importance": "NORMAL",
        "foregroundShow": true,
        "useDefaultVibrate": true,
        "useDefaultLight": true,
        "visibility": "PUBLIC",
        "notifySummary": "Cookie 已失效",
        "notifyId": 1001,
        "ttl": "3600s",
        "badgeNotification": {
          "badgeClass": "com.yogurt.notify.MainActivity",
          "addNum": 1
        },
        "clickAction": {
          "type": 3
        },
        "buttons": [
          {
            "name": "打开",
            "actionType": 0
          }
        ]
      }
    }
  }'
```

按钮字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 按钮文字 |
| `actionType` | number | 按钮动作类型，按荣耀字段传入 |
| `intentType` | number | Intent 类型，按荣耀字段传入 |
| `intent` | string | Intent 或 HTTPS URL |
| `data` | string/object | 按钮附加数据 |

通知栏最终会更接近这样：

```text
From: 监控系统
京东签到失败
账号：pt_pin_001
状态：Cookie 已失效
建议：重新登录并更新 cookie
```

App 内消息详情仍会显示加粗、红色、结构化正文和 Source 信息。

## 成功返回

成功时返回 HTTP `202`：

```json
{
  "ok": true,
  "sent": 1,
  "failed": 0,
  "source": {
    "requestId": "9acb0b1b-5e84-4c90-a793-2c4b7f6a4f99",
    "sentAt": "2026-08-09T10:30:00.000Z",
    "sender": "任务系统",
    "channel": "push",
    "ip": "203.0.113.10",
    "country": "CN",
    "userAgent": "curl/8.0.0",
    "authType": "authorization",
    "signature": "hmac-sha256签名"
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `ok` | 请求已处理成功 |
| `sent` | 成功下发的设备数量 |
| `failed` | 下发失败的设备数量 |
| `source` | 本次请求的来源信息 |

## 常见错误

### 401 missing or invalid admin token

`ADMIN_TOKEN` 没有传，或者传错了。

检查请求头：

```http
authorization: Bearer 你的_ADMIN_TOKEN
```

或者：

```http
x-admin-token: 你的_ADMIN_TOKEN
```

### 415 content-type must be application/json

请求头缺少 JSON 类型。

检查请求头：

```http
content-type: application/json
```

### 400 title is required

请求体缺少 `title`，或者 `title` 是空字符串。

### 400 body is required

请求体缺少 `body`，或者 `body` 是空字符串。

### 400 token, userId, groupId or groupName is required

请求体没有指定推送目标。

至少填写一个：

```text
userId / groupId / groupName / token
```

### 400 send to either groupId or groupName

同时填写了 `groupId` 和 `groupName`。

只保留其中一个。更稳定的方式是使用 `groupId`。

### 404 groupName not found

找不到指定的用户组名称。

检查用户组是否已经创建，或者改用 `groupId`。

### 409 no registered Honor push tokens for target

目标用户或用户组里没有可用的荣耀 PushToken。

常见原因：

1. 用户还没有在 App 登录。
2. App 没有成功获取荣耀 PushToken。
3. App 没有成功调用 `POST /register` 注册设备。
4. 按用户组推送时，目标用户还没有加入该用户组。

### 502 push delivery failed

Worker 找到了目标设备，但调用荣耀 Push 服务失败。

常见检查项：

1. `HONOR_APP_ID` 是否正确。
2. `HONOR_CLIENT_ID` 是否正确。
3. `HONOR_CLIENT_SECRET` 是否正确。
4. `HONOR_SEND_URL` 是否正确。
5. 荣耀开发者后台是否已经启用 Push Kit。
6. Android 应用包名、签名 SHA-256、`mcs-services.json` 是否匹配。
