import type { Env, PushJob } from "./types";

const DEFAULT_TOKEN_URL = "https://iam.developer.honor.com/auth/token";

let cachedAccessToken: { token: string; expiresAt: number } | undefined;

export async function sendHonor(env: Env, job: PushJob): Promise<void> {
  const accessToken = await getAccessToken(env);
  const appId = envValue(env.HONOR_APP_ID, "HONOR_APP_ID");
  const sendUrl =
    env.HONOR_SEND_URL?.trim().replace("{appId}", appId) ??
    `https://push-api.cloud.honor.com/api/v1/${appId}/sendMessage`;

  const response = await fetch(sendUrl, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json; charset=UTF-8",
      timestamp: String(Date.now()),
    },
    body: JSON.stringify({
      android: {
        notification: {
          title: job.title,
          body: job.body,
          importance: "NORMAL",
          useDefaultVibrate: true,
          useDefaultLight: true,
          visibility: "PUBLIC",
          foregroundShow: true,
          clickAction: {
            type: 3,
          },
          style: 1,
          bigTitle: job.title,
          bigBody: job.body,
        },
      },
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
