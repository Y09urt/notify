export interface Env {
  DB: D1Database;
  PUSH_QUEUE: Queue<PushJob>;
  ADMIN_TOKEN: string;
  HONOR_APP_ID: string;
  HONOR_CLIENT_ID: string;
  HONOR_CLIENT_SECRET: string;
  HONOR_TOKEN_URL?: string;
  HONOR_SEND_URL?: string;
}

export interface PushJob {
  tokenId?: string;
  platform: "honor";
  token: string;
  title: string;
  body: string;
  data?: Record<string, string>;
}

export interface RegisterRequest {
  token?: unknown;
  platform?: unknown;
  userId?: unknown;
  deviceId?: unknown;
}

export interface PushRequest {
  token?: unknown;
  userId?: unknown;
  platform?: unknown;
  title?: unknown;
  body?: unknown;
  data?: unknown;
}

export interface PushTokenRow {
  id: string;
  token: string;
}
