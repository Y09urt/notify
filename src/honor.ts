import type { Env, PushJob } from "./types";

const DEFAULT_TOKEN_URL = "https://iam.developer.hihonor.com/auth/token";

let cachedAccessToken: { token: string; expiresAt: number } | undefined;

export async function sendHonor(env: Env, job: PushJob): Promise<void> {
  const accessToken = await getAccessToken(env);
  const sendUrl =
    env.HONOR_SEND_URL?.replace("{appId}", env.HONOR_APP_ID) ??
    `https://push-api.cloud.hihonor.com/v1/${env.HONOR_APP_ID}/messages:send`;

  const response = await fetch(sendUrl, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      validate_only: false,
      message: {
        token: [job.token],
        notification: {
          title: job.title,
          body: job.body,
        },
        data: job.data ? JSON.stringify(job.data) : undefined,
        android: {
          notification: {
            foreground_show: true,
            click_action: {
              type: 3,
            },
          },
        },
      },
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
