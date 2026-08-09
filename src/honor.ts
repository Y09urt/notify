import type { Env, PushJob } from "./types";

const DEFAULT_TOKEN_URL = "https://iam.developer.honor.com/auth/token";

let cachedAccessToken: { token: string; expiresAt: number } | undefined;

export async function sendHonor(env: Env, job: PushJob): Promise<void> {
  const accessToken = await getAccessToken(env);
  const appId = envValue(env.HONOR_APP_ID, "HONOR_APP_ID");
  const sendUrl =
    env.HONOR_SEND_URL?.trim().replace("{appId}", appId) ??
    `https://push-api.cloud.honor.com/api/v1/${appId}/sendMessage`;
  const options = notificationOptions(job.data);
  const notificationTitle = plainNotifyText(job.title);
  const notificationBody = notificationBodyText(job);
  const android: Record<string, unknown> = {
    notification: buildNotification(job, options, notificationTitle, notificationBody),
    data: JSON.stringify({
      id: job.messageId,
      title: job.title,
      body: job.body,
      data: job.data ?? {},
    }),
  };
  if (typeof options.ttl === "string" && /^\d+s$/.test(options.ttl)) {
    android.ttl = options.ttl;
  }

  const response = await fetch(sendUrl, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json; charset=UTF-8",
      timestamp: String(Date.now()),
    },
    body: JSON.stringify({
      android,
      token: [job.token],
    }),
  });

  const detail = await response.text();
  if (!response.ok) {
    throw new Error(`HONOR push request failed: ${response.status} ${detail}`);
  }

  const payload = parseHonorResponse(detail) as {
    code?: string | number;
    message?: string;
    msg?: string;
  };

  if (payload.code != null && !isHonorSuccessCode(payload.code)) {
    throw new Error(
      `HONOR push request failed: ${payload.code} ${payload.message ?? payload.msg ?? ""}`,
    );
  }

  const failureSignal = honorFailureSignal(payload);
  if (failureSignal) {
    throw new Error(`HONOR push request failed: ${failureSignal}`);
  }
}

async function getAccessToken(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedAccessToken.expiresAt - 60 > now) {
    return cachedAccessToken.token;
  }

  const response = await fetch(env.HONOR_TOKEN_URL?.trim() || DEFAULT_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: envValue(env.HONOR_CLIENT_ID, "HONOR_CLIENT_ID"),
      client_secret: envValue(env.HONOR_CLIENT_SECRET, "HONOR_CLIENT_SECRET"),
    }),
  });

  const payload = (await response.json().catch(() => ({}))) as {
    access_token?: string;
    expires_in?: number;
    error?: string;
    error_description?: string;
    code?: string;
    message?: string;
  };

  if (!response.ok || !payload.access_token) {
    throw new Error(
      `HONOR token request failed: ${response.status} ${payload.error ?? payload.code ?? ""} ${
        payload.error_description ?? payload.message ?? ""
      }`,
    );
  }

  cachedAccessToken = {
    token: payload.access_token,
    expiresAt: now + (payload.expires_in ?? 3600),
  };

  return cachedAccessToken.token;
}

function envValue(value: string | undefined, name: string): string {
  const text = value?.trim() ?? "";
  if (!text) {
    throw new Error(`${name} is not configured`);
  }
  return text;
}

function buildNotification(
  job: PushJob,
  options: Record<string, unknown>,
  title: string,
  body: string,
): Record<string, unknown> {
  const style = numberValue(options.style) ?? defaultStyle(body);
  const notification: Record<string, unknown> = {
    title,
    body,
    importance: enumString(options.importance, ["LOW", "NORMAL"]) ?? "NORMAL",
    useDefaultVibrate: booleanValue(options.useDefaultVibrate) ?? true,
    useDefaultLight: booleanValue(options.useDefaultLight) ?? true,
    visibility: enumString(options.visibility, ["PUBLIC", "PRIVATE", "SECRET"]) ?? "PUBLIC",
    foregroundShow: booleanValue(options.foregroundShow) ?? true,
    clickAction: clickActionValue(options.clickAction) ?? { type: 3 },
    style,
  };

  if (style === 1) {
    notification.bigTitle = plainNotifyText(stringValue(options.bigTitle) ?? job.title);
    notification.bigBody = plainNotifyText(stringValue(options.bigBody) ?? body);
  }

  const image = httpsUrl(options.image);
  if (image) {
    notification.image = image;
  }

  const icon = stringValue(options.icon);
  if (icon && icon.startsWith("/raw/")) {
    notification.icon = icon.slice(0, 80);
  }

  const summary = stringValue(options.notifySummary);
  if (summary) {
    notification.notifySummary = plainNotifyText(summary).slice(0, 128);
  }

  const buttons = buttonValues(options.buttons);
  if (buttons.length > 0) {
    notification.buttons = buttons;
  }

  const badge = badgeNotificationValue(options.badgeNotification);
  if (badge) {
    notification.badgeNotification = badge;
  }

  const when = stringValue(options.when);
  if (when && !Number.isNaN(Date.parse(when))) {
    notification.when = when;
  }

  const notifyId = integerValue(options.notifyId, 1, 2_147_483_647);
  if (notifyId != null) {
    notification.notifyId = notifyId;
  }

  return notification;
}

function notificationOptions(data: Record<string, unknown> | undefined): Record<string, unknown> {
  const value = data?.notification;
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function defaultStyle(body: string): number {
  return body.includes("\n") || body.length > 48 ? 1 : 0;
}

function notificationBodyText(job: PushJob): string {
  const body = plainNotifyText(job.body);
  const sender = senderText(job.data);
  return sender ? `From: ${sender}\n${body}` : body;
}

function clickActionValue(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return undefined;
  }

  const source = value as Record<string, unknown>;
  const type = integerValue(source.type, 1, 3);
  if (!type) {
    return undefined;
  }

  const action: Record<string, unknown> = { type };
  if (type === 1) {
    const intent = stringValue(source.intent);
    const customAction = stringValue(source.action);
    if (intent) {
      action.intent = intent.slice(0, 512);
    }
    if (customAction) {
      action.action = customAction.slice(0, 256);
    }
  }
  if (type === 2) {
    const url = httpsUrl(source.url);
    if (url) {
      action.url = url;
    }
  }
  return action;
}

function buttonValues(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.slice(0, 3).flatMap((entry) => {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
      return [];
    }
    const source = entry as Record<string, unknown>;
    const name = stringValue(source.name)?.slice(0, 40);
    const actionType = integerValue(source.actionType, 0, 2);
    if (!name || actionType == null) {
      return [];
    }

    const button: Record<string, unknown> = { name, actionType };
    const intentType = integerValue(source.intentType, 0, 1);
    if (actionType === 1 && intentType != null) {
      button.intentType = intentType;
    }
    const intent = actionType === 2 ? httpsUrl(source.intent) : stringValue(source.intent);
    if (intent) {
      button.intent = intent.slice(0, 512);
    }
    const data = buttonDataValue(source.data);
    if (data) {
      button.data = data;
    }
    return [button];
  });
}

function badgeNotificationValue(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return undefined;
  }

  const source = value as Record<string, unknown>;
  const badgeClass = stringValue(source.badgeClass) ?? "com.yogurt.notify.MainActivity";
  const badge: Record<string, unknown> = { badgeClass };
  const setNum = integerValue(source.setNum, 0, 99);
  const addNum = integerValue(source.addNum, 1, 99);
  if (setNum != null) {
    badge.setNum = setNum;
  } else if (addNum != null) {
    badge.addNum = addNum;
  } else {
    badge.addNum = 1;
  }
  return badge;
}

function buttonDataValue(value: unknown): string | undefined {
  if (typeof value === "string") {
    return value.length <= 1024 ? value : value.slice(0, 1024);
  }
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return JSON.stringify(value).slice(0, 1024);
  }
  return undefined;
}

function enumString(value: unknown, allowed: string[]): string | undefined {
  const text = stringValue(value)?.toUpperCase();
  return text && allowed.includes(text) ? text : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function integerValue(value: unknown, min: number, max: number): number | undefined {
  const number = numberValue(value);
  if (number == null || !Number.isInteger(number) || number < min || number > max) {
    return undefined;
  }
  return number;
}

function httpsUrl(value: unknown): string | undefined {
  const text = stringValue(value);
  return text?.startsWith("https://") ? text.slice(0, 512) : undefined;
}

function senderText(data: Record<string, unknown> | undefined): string {
  const source = data?._source;
  if (!source || typeof source !== "object") {
    return "";
  }
  const sender = (source as Record<string, unknown>).sender;
  return typeof sender === "string" ? sender.trim().slice(0, 80) : "";
}

function plainNotifyText(value: string): string {
  return value
    .split("\n")
    .map(plainNotifyLine)
    .join("\n");
}

function plainNotifyLine(line: string): string {
  const withoutBlock = line.startsWith("# ")
    ? line.slice(2)
    : line.startsWith("- ")
      ? `• ${line.slice(2)}`
      : line;
  return withoutBlock
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/!!([^!]+)!!/g, "$1")
    .replace(/`([^`]+)`/g, "$1");
}

function isHonorSuccessCode(code: string | number): boolean {
  const text = String(code);
  return text === "200" || text === "80000000" || text === "0";
}

function parseHonorResponse(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) {
    return {};
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return { raw: trimmed.slice(0, 500) };
  }
}

function honorFailureSignal(value: unknown, path = "response"): string | undefined {
  if (Array.isArray(value)) {
    if (value.length > 0 && hasFailureName(path)) {
      return `${path} has ${value.length} item(s)`;
    }
    for (let i = 0; i < value.length; i += 1) {
      const child = honorFailureSignal(value[i], `${path}[${i}]`);
      if (child) {
        return child;
      }
    }
    return undefined;
  }
  if (!value || typeof value !== "object") {
    return undefined;
  }

  for (const [key, child] of Object.entries(value)) {
    const childPath = `${path}.${key}`;
    if (typeof child === "number" && child > 0 && hasFailureName(key)) {
      return `${childPath}=${child}`;
    }
    if (typeof child === "string" && child.trim() && hasFailureName(key)) {
      return `${childPath}=${child.trim().slice(0, 200)}`;
    }
    const nested = honorFailureSignal(child, childPath);
    if (nested) {
      return nested;
    }
  }

  return undefined;
}

function hasFailureName(name: string): boolean {
  const lower = name.toLowerCase();
  return (
    lower.includes("fail") ||
    lower.includes("invalid") ||
    lower.includes("illegal") ||
    lower.includes("error")
  );
}
