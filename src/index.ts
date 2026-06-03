import { sendHonor } from "./honor";
import { empty, HttpError, isHttpError, json, readJson, requireAdmin } from "./http";
import type { Env, PushJob, PushRequest, PushTokenRow, RegisterRequest } from "./types";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      if (request.method === "OPTIONS") {
        return empty();
      }

      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true });
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
  const body = await readJson<RegisterRequest>(request);
  const token = requiredString(body.token, "token");
  const platform = optionalString(body.platform) ?? "honor";
  const userId = optionalString(body.userId);
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
    .bind(id, userId ?? null, platform, token, deviceId ?? null)
    .run();

  return json({ ok: true });
}

async function enqueuePush(request: Request, env: Env): Promise<Response> {
  requireAdmin(request, env.ADMIN_TOKEN);

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

  await Promise.all(jobs.map((job) => env.PUSH_QUEUE.send(job)));
  return json({ ok: true, queued: jobs.length }, 202);
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
