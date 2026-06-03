const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,OPTIONS",
  "access-control-allow-headers": "authorization,content-type,x-admin-token",
};

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: jsonHeaders,
  });
}

export function empty(status = 204): Response {
  return new Response(null, { status, headers: jsonHeaders });
}

export async function readJson<T>(request: Request): Promise<T> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    throw new HttpError(415, "content-type must be application/json");
  }

  try {
    return (await request.json()) as T;
  } catch {
    throw new HttpError(400, "invalid json body");
  }
}

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function requireAdmin(request: Request, adminToken: string): void {
  const authorization = request.headers.get("authorization") ?? "";
  const bearer = authorization.startsWith("Bearer ")
    ? authorization.slice("Bearer ".length)
    : "";
  const headerToken = request.headers.get("x-admin-token") ?? "";

  if (!adminToken || (bearer !== adminToken && headerToken !== adminToken)) {
    throw new HttpError(401, "missing or invalid admin token");
  }
}
