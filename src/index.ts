import {
  authenticate,
  currentUser,
  isAdminUser,
  loginUser,
  logoutUser,
  registerUser,
} from "./auth";
import { sendHonor } from "./honor";
import { empty, HttpError, isAdmin, isHttpError, json, readJson } from "./http";
import type {
  Env,
  GroupMemberRequest,
  GroupRequest,
  PushJob,
  PushMessageRow,
  PushRequest,
  PushTokenRow,
  RegisterRequest,
  UserGroupMemberRow,
  UserGroupRow,
  UserListRow,
} from "./types";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      if (request.method === "OPTIONS") {
        return empty();
      }

      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, version: "2026-06-04-auth-2" });
      }

      if (request.method === "GET" && url.pathname === "/app/version") {
        return await appVersion(request, url, env);
      }

      if (request.method === "POST" && url.pathname === "/auth/register") {
        return await registerUser(request, env);
      }

      if (request.method === "POST" && url.pathname === "/auth/login") {
        return await loginUser(request, env);
      }

      if (request.method === "GET" && url.pathname === "/auth/me") {
        return await currentUser(request, env);
      }

      if (request.method === "POST" && url.pathname === "/auth/logout") {
        return await logoutUser(request, env);
      }

      if (request.method === "GET" && url.pathname === "/messages") {
        return await listMessages(request, url, env);
      }

      if (request.method === "POST" && url.pathname === "/messages") {
        return await sendMessage(request, env);
      }

      if (request.method === "GET" && url.pathname === "/groups") {
        return await listGroups(request, env);
      }

      if (request.method === "GET" && url.pathname === "/groups/members") {
        return await listGroupMembers(request, url, env);
      }

      if (request.method === "POST" && url.pathname === "/groups") {
        return await saveGroup(request, env);
      }

      if (request.method === "POST" && url.pathname === "/groups/members") {
        return await updateGroupMember(request, env);
      }

      if (request.method === "GET" && url.pathname === "/users") {
        return await listUsers(request, env);
      }

      if (request.method === "POST" && url.pathname === "/register") {
        return await registerDevice(request, env);
      }

      if (request.method === "POST" && url.pathname === "/push") {
        return await enqueuePush(request, env);
      }

      return json({ error: "not found" }, 404);
    } catch (error) {
      if (isHttpError(error)) {
        return json({ error: error.message }, error.status);
      }

      const detail = error instanceof Error ? error.message : String(error);
      console.error(error);
      return json({ error: "internal error", detail }, 500);
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

async function appVersion(request: Request, url: URL, env: Env): Promise<Response> {
  const rawChannel = url.searchParams.get("channel") ?? "release";
  const channel = rawChannel === "debug" ? "debug" : "release";
  if (channel === "debug") {
    const auth = await authenticate(request, env);
    if (!isDebugUpdateAllowed(auth.userId, env.APP_DEBUG_ALLOWED_IDS)) {
      throw new HttpError(403, "debug updates are not available for this account");
    }
  }
  const versionCode = Number(versionValue(env, channel, "VERSION_CODE") ?? "1");
  return json({
    ok: true,
    channel,
    versionCode: Number.isFinite(versionCode) ? versionCode : 1,
    versionName: versionValue(env, channel, "VERSION_NAME") ?? "1.0",
    downloadUrl: versionValue(env, channel, "DOWNLOAD_URL") ?? "",
    releaseNotes: versionValue(env, channel, "RELEASE_NOTES") ?? "",
  });
}

function isDebugUpdateAllowed(userId: string, allowedIds: string | undefined): boolean {
  return (allowedIds ?? "")
    .split(",")
    .map((id) => id.trim().toLowerCase())
    .filter(Boolean)
    .includes(userId.toLowerCase());
}

function versionValue(env: Env, channel: "debug" | "release", field: string): string | undefined {
  const keys =
    channel === "debug"
      ? {
          VERSION_CODE: "APP_DEBUG_LATEST_VERSION_CODE",
          VERSION_NAME: "APP_DEBUG_LATEST_VERSION_NAME",
          DOWNLOAD_URL: "APP_DEBUG_DOWNLOAD_URL",
          RELEASE_NOTES: "APP_DEBUG_RELEASE_NOTES",
        }
      : {
          VERSION_CODE: "APP_RELEASE_LATEST_VERSION_CODE",
          VERSION_NAME: "APP_RELEASE_LATEST_VERSION_NAME",
          DOWNLOAD_URL: "APP_RELEASE_DOWNLOAD_URL",
          RELEASE_NOTES: "APP_RELEASE_NOTES",
        };
  const key = keys[field as keyof typeof keys];
  const legacyKey =
    field === "VERSION_CODE"
      ? "APP_LATEST_VERSION_CODE"
      : field === "VERSION_NAME"
        ? "APP_LATEST_VERSION_NAME"
        : field === "DOWNLOAD_URL"
          ? "APP_DOWNLOAD_URL"
          : "APP_RELEASE_NOTES";
  return (env as unknown as Record<string, string | undefined>)[key] ?? (env as unknown as Record<string, string | undefined>)[legacyKey];
}

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
  const groupId = optionalString(body.groupId);
  const groupName = optionalString(body.groupName);
  const platform = optionalString(body.platform) ?? "honor";

  if (!token && !userId && !groupId && !groupName) {
    throw new HttpError(400, "token, userId, groupId or groupName is required");
  }
  if (groupId && groupName) {
    throw new HttpError(400, "send to either groupId or groupName");
  }

  if (platform !== "honor") {
    throw new HttpError(400, "only honor push tokens are supported in this worker");
  }

  const jobs: PushJob[] = [];
  if (token) {
    jobs.push({ messageId: crypto.randomUUID(), platform, token, title, body: messageBody, data });
  }

  if (userId) {
    const targetUserId = normalizeUserId(userId);
    const messageId = crypto.randomUUID();
    jobs.push(...(await jobsForUser(env, targetUserId, title, messageBody, data, messageId)));
    await saveMessage(env, targetUserId, title, messageBody, data, messageId);
  }

  if (groupId || groupName) {
    const targetGroupId = groupId ? normalizeGroupId(groupId) : await groupIdForName(env, requiredString(groupName, "groupName"));
    const targetUserIds = await userIdsForGroup(env, targetGroupId);
    for (const targetUserId of targetUserIds) {
      const messageId = crypto.randomUUID();
      jobs.push(...(await jobsForUser(env, targetUserId, title, messageBody, data, messageId)));
      await saveMessage(env, targetUserId, title, messageBody, data, messageId);
    }
  }

  await Promise.all(jobs.map((job) => env.PUSH_QUEUE.send(job)));
  return json({ ok: true, queued: jobs.length }, 202);
}

async function sendMessage(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await requireAdmin(env, auth.userId);
  const body = await readJson<PushRequest>(request);
  const title = requiredString(body.title, "title");
  const messageBody = requiredString(body.body, "body");
  const data = normalizeData(body.data);
  const targetUserId = optionalString(body.userId);
  const targetGroupId = optionalString(body.groupId);
  const targetGroupName = optionalString(body.groupName);

  if (!targetUserId && !targetGroupId && !targetGroupName) {
    throw new HttpError(400, "userId, groupId or groupName is required");
  }
  if ([targetUserId, targetGroupId, targetGroupName].filter(Boolean).length > 1) {
    throw new HttpError(400, "send to only one target");
  }

  const targetUserIds = targetGroupId || targetGroupName
    ? await userIdsForGroup(
        env,
        targetGroupId ? normalizeGroupId(targetGroupId) : await groupIdForName(env, requiredString(targetGroupName, "groupName")),
      )
    : [normalizeUserId(targetUserId)];
  const jobs: PushJob[] = [];
  for (const userId of targetUserIds) {
    const messageId = crypto.randomUUID();
    jobs.push(...(await jobsForUser(env, userId, title, messageBody, data, messageId)));
    await saveMessage(env, userId, title, messageBody, data, messageId);
  }
  await Promise.all(jobs.map((job) => env.PUSH_QUEUE.send(job)));
  return json({ ok: true, queued: jobs.length, recipients: targetUserIds.length }, 202);
}

async function listGroups(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await requireAdmin(env, auth.userId);
  const result = await env.DB.prepare(
    `SELECT groups.id, groups.name, groups.is_admin, COUNT(members.user_id) AS member_count
     FROM user_groups groups
     LEFT JOIN user_group_members members ON members.group_id = groups.id
     GROUP BY groups.id, groups.name, groups.is_admin
     ORDER BY groups.name`,
  ).all<UserGroupRow>();

  return json({
    ok: true,
    groups: (result.results ?? []).map((row) => ({
      id: row.id,
      name: row.name,
      isAdmin: row.is_admin === 1,
      memberCount: row.member_count,
    })),
  });
}

async function saveGroup(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await requireAdmin(env, auth.userId);
  const body = await readJson<GroupRequest>(request);
  const id = normalizeGroupId(body.id);
  const name = requiredString(body.name, "name");
  const isAdmin = body.isAdmin === true;

  await env.DB.prepare(
    `INSERT INTO user_groups (id, name, is_admin)
     VALUES (?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       name = excluded.name,
       is_admin = excluded.is_admin`,
  )
    .bind(id, name, isAdmin ? 1 : 0)
    .run();

  return json({ ok: true, group: { id, name, isAdmin } });
}

async function listGroupMembers(request: Request, url: URL, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await requireAdmin(env, auth.userId);
  const groupId = normalizeGroupId(url.searchParams.get("groupId"));
  const result = await env.DB.prepare(
    "SELECT user_id FROM user_group_members WHERE group_id = ? ORDER BY user_id",
  )
    .bind(groupId)
    .all<UserGroupMemberRow>();

  return json({
    ok: true,
    groupId,
    members: (result.results ?? []).map((row) => row.user_id),
  });
}

async function updateGroupMember(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await requireAdmin(env, auth.userId);
  const body = await readJson<GroupMemberRequest>(request);
  const groupId = normalizeGroupId(body.groupId);
  const userId = normalizeUserId(requiredString(body.userId, "userId"));
  const action = optionalString(body.action) ?? "add";

  if (action === "remove") {
    await env.DB.prepare("DELETE FROM user_group_members WHERE group_id = ? AND user_id = ?")
      .bind(groupId, userId)
      .run();
    return json({ ok: true });
  }

  const group = await env.DB.prepare("SELECT id FROM user_groups WHERE id = ?")
    .bind(groupId)
    .first();
  if (!group) {
    throw new HttpError(404, "group not found");
  }

  await env.DB.prepare(
    `INSERT INTO user_group_members (group_id, user_id)
     VALUES (?, ?)
     ON CONFLICT(group_id, user_id) DO NOTHING`,
  )
    .bind(groupId, userId)
    .run();

  return json({ ok: true });
}

async function listUsers(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await requireAdmin(env, auth.userId);
  const result = await env.DB.prepare(
    `SELECT users.id,
       GROUP_CONCAT(groups.name || ' (' || groups.id || ')', ', ') AS groups
     FROM users
     LEFT JOIN user_group_members members ON members.user_id = users.id
     LEFT JOIN user_groups groups ON groups.id = members.group_id
     GROUP BY users.id
     ORDER BY users.id`,
  ).all<UserListRow>();

  return json({
    ok: true,
    users: (result.results ?? []).map((row) => ({
      id: row.id,
      groups: row.groups ? row.groups.split(", ") : [],
    })),
  });
}

async function requireAdmin(env: Env, userId: string): Promise<void> {
  if (!(await isAdminUser(env, userId))) {
    throw new HttpError(403, "admin permission required");
  }
}

async function jobsForUser(
  env: Env,
  userId: string,
  title: string,
  messageBody: string,
  data: Record<string, string> | undefined,
  messageId: string,
): Promise<PushJob[]> {
  const result = await env.DB.prepare(
    "SELECT id, token FROM push_tokens WHERE user_id = ? AND platform = 'honor'",
  )
    .bind(userId)
    .all<PushTokenRow>();

  return (result.results ?? []).map((row) => ({
    messageId,
    tokenId: row.id,
    platform: "honor",
    token: row.token,
    title,
    body: messageBody,
    data,
  }));
}

async function saveMessage(
  env: Env,
  userId: string,
  title: string,
  messageBody: string,
  data: Record<string, string> | undefined,
  messageId: string,
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO push_messages (id, user_id, title, body, data)
     VALUES (?, ?, ?, ?, ?)`,
  )
    .bind(messageId, userId, title, messageBody, data ? JSON.stringify(data) : null)
    .run();
}

async function userIdsForGroup(env: Env, groupId: string): Promise<string[]> {
  const result = await env.DB.prepare(
    "SELECT user_id FROM user_group_members WHERE group_id = ? ORDER BY user_id",
  )
    .bind(normalizeGroupId(groupId))
    .all<UserGroupMemberRow>();
  return (result.results ?? []).map((row) => row.user_id);
}

async function groupIdForName(env: Env, groupName: string): Promise<string> {
  const result = await env.DB.prepare("SELECT id FROM user_groups WHERE name = ? ORDER BY id")
    .bind(groupName)
    .all<{ id: string }>();
  const rows = result.results ?? [];
  if (rows.length === 0) {
    throw new HttpError(404, "groupName not found");
  }
  if (rows.length > 1) {
    throw new HttpError(400, "groupName matches multiple groups; use groupId");
  }
  return rows[0].id;
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

function normalizeUserId(value: string | undefined): string {
  const text = requiredString(value, "userId").toLowerCase();
  if (!/^[a-z0-9_.-]{3,32}$/.test(text)) {
    throw new HttpError(400, "userId must be 3-32 chars: a-z, 0-9, _, . or -");
  }
  return text;
}

function normalizeGroupId(value: unknown): string {
  const text = requiredString(value, "groupId").toLowerCase();
  if (!/^[\p{L}\p{N}_.-]{1,32}$/u.test(text)) {
    throw new HttpError(400, "groupId must be 1-32 chars: letters, numbers, Chinese, _, . or -");
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
