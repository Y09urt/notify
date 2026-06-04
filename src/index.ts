import {
  authenticate,
  currentUser,
  loginUser,
  logoutUser,
  registerUser,
} from "./auth";
import { sendHonor } from "./honor";
import { empty, HttpError, isAdmin, isHttpError, json, readJson } from "./http";
import type {
  Env,
  PushJob,
  PushMessageRow,
  PushRequest,
  PushTokenRow,
  RegisterRequest,
} from "./types";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      if (request.method === "OPTIONS") {
        return empty();
      }

      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, version: "2026-06-03-3" });
      }

      if (request.method === "POST" && url.pathname === "/auth/register") {
        return registerUser(request, env);
      }

      if (request.method === "POST" && url.pathname === "/auth/login") {
        return loginUser(request, env);
      }

      if (request.method === "GET" && url.pathname === "/auth/me") {
        return currentUser(request, env);
      }

      if (request.method === "POST" && url.pathname === "/auth/logout") {
        return logoutUser(request, env);
      }

      if (request.method === "GET" && url.pathname === "/messages") {
        return listMessages(request, url, env);
      }

      if (request.method === "POST" && url.pathname === "/register") {
        return registerDevice(request, env);
      }

      if (request.method === "POST" && url.pathname === "/push") {
        return enqueuePush(request, env);
      }

      return json({ error: "not found" }, 404);
    } catch (error) {
      if (isHttpError(error)) {
        return json({ error: error.message }, error.status);
      }

      console.error(error);
      return json({ error: "internal error" }, 500);
    }
  },

  async queue(batch: MessageBatch<PushJob>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      try {
        await sendHonor(env, message.body);
        if (message.body.tokenId) {
          await env.DB.prepare(
            "UPDATE push_tokens SET last_error = NULL, updated_at = datetime('now') WHERE id = ?",
          )
            .bind(message.body.tokenId)
            .run();
        }
        message.ack();
      } catch (error) {
        const lastError = error instanceof Error ? error.message : String(error);
        console.error("push failed", lastError);
        if (message.body.tokenId) {
          await env.DB.prepare(
            "UPDATE push_tokens SET last_error = ?, updated_at = datetime('now') WHERE id = ?",
          )
            .bind(lastError.slice(0, 1000), message.body.tokenId)
            .run();
        }
        message.retry();
      }
    }
  },
} satisfies ExportedHandler<Env, PushJob>;

async function registerDevice(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  const body = await readJson<RegisterRequest>(request);
  const token = requiredString(body.token, "token");
  const platform = optionalString(body.platform) ?? "honor";
  const deviceId = optionalString(body.deviceId);

  if (platform !== "honor") {
    throw new HttpError(400, "only honor push tokens are supported in this worker");
  }

  const id = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO push_tokens (id, user_id, platform, token, device_id)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(token) DO UPDATE SET
       user_id = excluded.user_id,
       platform = excluded.platform,
       device_id = excluded.device_id,
       last_error = NULL,
       updated_at = datetime('now')`,
  )
    .bind(id, auth.userId, platform, token, deviceId ?? null)
    .run();

  return json({ ok: true });
}

async function enqueuePush(request: Request, env: Env): Promise<Response> {
  if (!isAdmin(request, env.ADMIN_TOKEN)) {
    return json({ error: "missing or invalid admin token" }, 401);
  }

  const body = await readJson<PushRequest>(request);
  const title = requiredString(body.title, "title");
  const messageBody = requiredString(body.body, "body");
  const data = normalizeData(body.data);
  const token = optionalString(body.token);
  const userId = optionalString(body.userId);
  const platform = optionalString(body.platform) ?? "honor";

  if (!token && !userId) {
    throw new HttpError(400, "token or userId is required");
  }

  if (platform !== "honor") {
    throw new HttpError(400, "only honor push tokens are supported in this worker");
  }

  const jobs: PushJob[] = [];
  if (token) {
    jobs.push({ platform, token, title, body: messageBody, data });
  }

  if (userId) {
    const result = await env.DB.prepare(
      "SELECT id, token FROM push_tokens WHERE user_id = ? AND platform = 'honor'",
    )
      .bind(userId)
      .all<PushTokenRow>();

    for (const row of result.results ?? []) {
      jobs.push({ tokenId: row.id, platform, token: row.token, title, body: messageBody, data });
    }
  }

  if (userId) {
    await env.DB.prepare(
      `INSERT INTO push_messages (id, user_id, title, body, data)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind(crypto.randomUUID(), userId, title, messageBody, data ? JSON.stringify(data) : null)
      .run();
  }

  await Promise.all(jobs.map((job) => env.PUSH_QUEUE.send(job)));
  return json({ ok: true, queued: jobs.length }, 202);
}

async function listMessages(request: Request, url: URL, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  const rawLimit = Number(url.searchParams.get("limit") ?? "100");
  const limit = Number.isFinite(rawLimit) ? Math.min(Math.max(Math.floor(rawLimit), 1), 200) : 100;
  const result = await env.DB.prepare(
    `SELECT id, user_id, title, body, data, created_at
     FROM push_messages
     WHERE user_id = ?
     ORDER BY created_at DESC
     LIMIT ?`,
  )
    .bind(auth.userId, limit)
    .all<PushMessageRow>();

  return json({
    ok: true,
    messages: (result.results ?? []).map((row) => ({
      id: row.id,
      userId: row.user_id,
      title: row.title,
      body: row.body,
      data: row.data ? safeJson(row.data) : null,
      createdAt: row.created_at,
    })),
  });
}

function requiredString(value: unknown, fieldName: string): string {
  const text = optionalString(value);
  if (!text) {
    throw new HttpError(400, `${fieldName} is required`);
  }
  return text;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function normalizeData(value: unknown): Record<string, string> | undefined {
  if (value == null) {
    return undefined;
  }

  if (typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "data must be an object");
  }

  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).map(([key, entry]) => [
      key,
      typeof entry === "string" ? entry : JSON.stringify(entry),
    ]),
  );
}

function safeJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}
