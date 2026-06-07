import type { Env, PushJob } from "./types";

const DEFAULT_TOKEN_URL = "https://iam.developer.honor.com/auth/token";

let cachedAccessToken: { token: string; expiresAt: number } | undefined;

export async function sendHonor(env: Env, job: PushJob): Promise<void> {
  const accessToken = await getAccessToken(env);
  const sendUrl =
    env.HONOR_SEND_URL?.replace("{appId}", env.HONOR_APP_ID) ??
    `https://push-api.cloud.honor.com/api/v1/${env.HONOR_APP_ID}/sendMessage`;
  const messageId = job.messageId ?? crypto.randomUUID();
  const data = JSON.stringify({
    ...(job.data ?? {}),
    id: messageId,
    title: job.title,
    body: job.body,
  });

  const response = await fetch(sendUrl, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json; charset=UTF-8",
      timestamp: String(Date.now()),
    },
    body: JSON.stringify({
      notification: {
        title: job.title,
        body: job.body,
      },
      data,
      android: {
        notification: {
          title: job.title,
          body: job.body,
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

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`HONOR push request failed: ${response.status} ${detail}`);
  }

  const payload = (await response.json().catch(() => ({}))) as {
    code?: string;
    message?: string;
    msg?: string;
  };

  if (payload.code && payload.code !== "80000000") {
    throw new Error(
      `HONOR push request failed: ${payload.code} ${payload.message ?? payload.msg ?? ""}`,
    );
  }
}

async function getAccessToken(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedAccessToken.expiresAt - 60 > now) {
    return cachedAccessToken.token;
  }

  const response = await fetch(env.HONOR_TOKEN_URL ?? DEFAULT_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: env.HONOR_CLIENT_ID,
      client_secret: env.HONOR_CLIENT_SECRET,
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
