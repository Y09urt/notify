export interface Env {
  DB: D1Database;
  PUSH_QUEUE: Queue<PushJob>;
  ADMIN_TOKEN: string;
  HONOR_APP_ID: string;
  HONOR_CLIENT_ID: string;
  HONOR_CLIENT_SECRET: string;
  HONOR_TOKEN_URL?: string;
  HONOR_SEND_URL?: string;
  APP_LATEST_VERSION_CODE?: string;
  APP_LATEST_VERSION_NAME?: string;
  APP_DOWNLOAD_URL?: string;
  APP_RELEASE_NOTES?: string;
  APP_DEBUG_LATEST_VERSION_CODE?: string;
  APP_DEBUG_LATEST_VERSION_NAME?: string;
  APP_DEBUG_DOWNLOAD_URL?: string;
  APP_DEBUG_RELEASE_NOTES?: string;
  APP_DEBUG_ALLOWED_IDS?: string;
  APP_RELEASE_LATEST_VERSION_CODE?: string;
  APP_RELEASE_LATEST_VERSION_NAME?: string;
  APP_RELEASE_DOWNLOAD_URL?: string;
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

export interface PushMessageRow {
  id: string;
  user_id: string;
  title: string;
  body: string;
  data: string | null;
  created_at: string;
}
