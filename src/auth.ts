import { HttpError, json, readJson } from "./http";
import type { Env } from "./types";

const SESSION_DAYS = 90;
const PASSWORD_ITERATIONS = 20_000;

let authSchemaReady: Promise<void> | undefined;

interface AuthBody {
  id?: unknown;
  password?: unknown;
}

interface UserRow {
  id: string;
  password_hash: string;
  salt: string;
}

interface SessionRow {
  id: string;
  user_id: string;
  expires_at: string;
}

export interface AuthUser {
  sessionId: string;
  userId: string;
}

export async function registerUser(request: Request, env: Env): Promise<Response> {
  await ensureAuthSchema(env);
  const { id, password } = await readAuthBody(request);
  const existing = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(id).first();
  if (existing) {
    throw new HttpError(409, "id already exists");
  }

  const salt = randomBase64Url(16);
  const passwordHash = await hashPassword(password, salt);
  await env.DB.prepare("INSERT INTO users (id, password_hash, salt) VALUES (?, ?, ?)")
    .bind(id, passwordHash, salt)
    .run();

  const sessionToken = await createSession(env, id);
  return json({ ok: true, userId: id, sessionToken, isAdmin: await isAdminUser(env, id) }, 201);
}

export async function loginUser(request: Request, env: Env): Promise<Response> {
  await ensureAuthSchema(env);
  const { id, password } = await readAuthBody(request);
  const user = await env.DB.prepare("SELECT id, password_hash, salt FROM users WHERE id = ?")
    .bind(id)
    .first<UserRow>();
  if (!user || !(await verifyPassword(password, user.salt, user.password_hash))) {
    throw new HttpError(401, "invalid id or password");
  }

  const sessionToken = await createSession(env, id);
  return json({ ok: true, userId: id, sessionToken, isAdmin: await isAdminUser(env, id) });
}

export async function currentUser(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  return json({
    ok: true,
    userId: auth.userId,
    isAdmin: await isAdminUser(env, auth.userId),
    groups: await groupsForUser(env, auth.userId),
  });
}

export async function logoutUser(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  await env.DB.prepare("DELETE FROM sessions WHERE id = ?").bind(auth.sessionId).run();
  return json({ ok: true });
}

export async function authenticate(request: Request, env: Env): Promise<AuthUser> {
  const token = bearerToken(request);
  if (!token) {
    throw new HttpError(401, "login required");
  }

  const [sessionId] = token.split(".", 1);
  if (!sessionId) {
    throw new HttpError(401, "invalid session");
  }

  await ensureAuthSchema(env);
  const tokenHash = await sha256Hex(token);
  const session = await env.DB.prepare(
    "SELECT id, user_id, expires_at FROM sessions WHERE id = ? AND token_hash = ? AND expires_at > datetime('now')",
  )
    .bind(sessionId, tokenHash)
    .first<SessionRow>();

  if (!session) {
    throw new HttpError(401, "session expired or invalid");
  }

  return { sessionId: session.id, userId: session.user_id };
}

export async function isAdminUser(env: Env, userId: string): Promise<boolean> {
  await ensureAuthSchema(env);
  if (isBootstrapAdmin(userId, env.APP_ADMIN_IDS)) {
    return true;
  }

  const row = await env.DB.prepare(
    `SELECT 1
     FROM user_group_members members
     JOIN user_groups groups ON groups.id = members.group_id
     WHERE members.user_id = ? AND groups.is_admin = 1
     LIMIT 1`,
  )
    .bind(userId)
    .first();
  return Boolean(row);
}

async function groupsForUser(env: Env, userId: string): Promise<Array<{ id: string; name: string; isAdmin: boolean }>> {
  await ensureAuthSchema(env);
  const result = await env.DB.prepare(
    `SELECT groups.id, groups.name, groups.is_admin
     FROM user_group_members members
     JOIN user_groups groups ON groups.id = members.group_id
     WHERE members.user_id = ?
     ORDER BY groups.name`,
  )
    .bind(userId)
    .all<{ id: string; name: string; is_admin: number }>();
  return (result.results ?? []).map((row) => ({
    id: row.id,
    name: row.name,
    isAdmin: row.is_admin === 1,
  }));
}

function ensureAuthSchema(env: Env): Promise<void> {
  authSchemaReady ??= createAuthSchema(env);
  return authSchemaReady;
}

async function createAuthSchema(env: Env): Promise<void> {
  const statements = [
    `CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      password_hash TEXT NOT NULL,
      salt TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )`,
    `CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      FOREIGN KEY (user_id) REFERENCES users(id)
    )`,
    "CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id)",
    "CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at)",
    `CREATE TABLE IF NOT EXISTS user_groups (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      is_admin INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )`,
    `CREATE TABLE IF NOT EXISTS user_group_members (
      group_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      PRIMARY KEY (group_id, user_id),
      FOREIGN KEY (group_id) REFERENCES user_groups(id),
      FOREIGN KEY (user_id) REFERENCES users(id)
    )`,
    "CREATE INDEX IF NOT EXISTS idx_user_group_members_user_id ON user_group_members(user_id)",
  ];

  for (const statement of statements) {
    await env.DB.prepare(statement).run();
  }
}

function isBootstrapAdmin(userId: string, adminIds: string | undefined): boolean {
  return (adminIds ?? "")
    .split(",")
    .map((id) => id.trim().toLowerCase())
    .filter(Boolean)
    .includes(userId.toLowerCase());
}

async function readAuthBody(request: Request): Promise<{ id: string; password: string }> {
  const body = await readJson<AuthBody>(request);
  const id = normalizeId(body.id);
  const password = typeof body.password === "string" ? body.password : "";
  if (!password || password.length < 6 || password.length > 128) {
    throw new HttpError(400, "password must be 6-128 characters");
  }
  return { id, password };
}

function normalizeId(value: unknown): string {
  if (typeof value !== "string") {
    throw new HttpError(400, "id is required");
  }
  const id = value.trim().toLowerCase();
  if (!/^[a-z0-9_.-]{3,32}$/.test(id)) {
    throw new HttpError(400, "id must be 3-32 chars: a-z, 0-9, _, . or -");
  }
  return id;
}

function bearerToken(request: Request): string | undefined {
  const authorization = request.headers.get("authorization") ?? "";
  return authorization.startsWith("Bearer ") ? authorization.slice("Bearer ".length).trim() : undefined;
}

async function createSession(env: Env, userId: string): Promise<string> {
  const sessionId = crypto.randomUUID();
  const secret = randomBase64Url(32);
  const sessionToken = `${sessionId}.${secret}`;
  const tokenHash = await sha256Hex(sessionToken);
  const expiresAt = new Date(Date.now() + SESSION_DAYS * 24 * 60 * 60 * 1000)
    .toISOString()
    .replace("T", " ")
    .slice(0, 19);

  await env.DB.prepare(
    "INSERT INTO sessions (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
  )
    .bind(sessionId, userId, tokenHash, expiresAt)
    .run();
  return sessionToken;
}

async function hashPassword(password: string, salt: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: new TextEncoder().encode(salt),
      iterations: PASSWORD_ITERATIONS,
    },
    key,
    256,
  );
  return hex(bits);
}

async function verifyPassword(password: string, salt: string, expectedHash: string): Promise<boolean> {
  return timingSafeEqual(await hashPassword(password, salt), expectedHash);
}

async function sha256Hex(value: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}

function randomBase64Url(bytes: number): string {
  const values = new Uint8Array(bytes);
  crypto.getRandomValues(values);
  let text = "";
  for (const value of values) {
    text += String.fromCharCode(value);
  }
  return btoa(text).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function hex(buffer: ArrayBuffer): string {
  return [...new Uint8Array(buffer)].map((value) => value.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let index = 0; index < a.length; index++) {
    diff |= a.charCodeAt(index) ^ b.charCodeAt(index);
  }
  return diff === 0;
}
