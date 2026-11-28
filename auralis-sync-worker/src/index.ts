import { createRemoteJWKSet, jwtVerify } from "jose";

interface D1Result<T> {
  results: T[];
}

interface D1Statement {
  bind(...values: unknown[]): D1Statement;
  first<T>(): Promise<T | null>;
  all<T>(): Promise<D1Result<T>>;
  run(): Promise<unknown>;
}

interface D1Database {
  prepare(query: string): D1Statement;
}

interface Env {
  DB: D1Database;
  CLERK_ISSUER: string;
  ENCRYPTION_KEY?: string;
}

interface ConnectionRow {
  provider: string;
  payload: string;
  updated_at: number;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const allowedProviders = new Set(["youtube", "spotify"]);
const hkdfSalt = encoder.encode("auralis-sync-encryption-v1");
const jwksByIssuer = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

function arrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function unauthorized(): Response {
  return json({ error: "unauthorized" }, 401);
}

function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function connectionContext(userId: string, provider: string): Uint8Array {
  return encoder.encode(`${userId}:${provider}`);
}

async function encryptionKey(secret: string, userId: string, provider: string): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey("raw", arrayBuffer(encoder.encode(secret)), "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: arrayBuffer(hkdfSalt), info: arrayBuffer(connectionContext(userId, provider)) },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

async function encrypt(secret: string, userId: string, provider: string, payload: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await encryptionKey(secret, userId, provider);
  const cipher = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: arrayBuffer(iv), additionalData: arrayBuffer(connectionContext(userId, provider)) },
    key,
    arrayBuffer(encoder.encode(payload)),
  );
  return `${encodeBase64(iv)}.${encodeBase64(new Uint8Array(cipher))}`;
}

async function decrypt(secret: string, userId: string, provider: string, stored: string): Promise<string | null> {
  try {
    const [ivPart, cipherPart, extra] = stored.split(".");
    if (!ivPart || !cipherPart || extra !== undefined) return null;
    const iv = decodeBase64(ivPart);
    if (iv.byteLength !== 12) return null;
    const key = await encryptionKey(secret, userId, provider);
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: arrayBuffer(iv), additionalData: arrayBuffer(connectionContext(userId, provider)) },
      key,
      arrayBuffer(decodeBase64(cipherPart)),
    );
    return decoder.decode(plain);
  } catch {
    return null;
  }
}

async function authenticate(request: Request, env: Env): Promise<string | null> {
  const authorization = request.headers.get("authorization");
  const match = authorization?.match(/^Bearer ([^\s]+)$/i);
  if (!match || !env.CLERK_ISSUER) return null;

  let jwks = jwksByIssuer.get(env.CLERK_ISSUER);
  if (!jwks) {
    jwks = createRemoteJWKSet(new URL(".well-known/jwks.json", `${env.CLERK_ISSUER.replace(/\/$/, "")}/`));
    jwksByIssuer.set(env.CLERK_ISSUER, jwks);
  }

  try {
    const { payload } = await jwtVerify(match[1], jwks, {
      algorithms: ["RS256"],
      issuer: env.CLERK_ISSUER,
      clockTolerance: 10,
    });
    if (typeof payload.sub !== "string" || !payload.sub || typeof payload.exp !== "number" || typeof payload.nbf !== "number") {
      return null;
    }
    return payload.sub;
  } catch {
    return null;
  }
}

async function readPayload(request: Request): Promise<{ payload?: string; status?: number }> {
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (contentLength > 66000) return { status: 413 };
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength > 66000) return { status: 413 };
  try {
    const body: unknown = JSON.parse(decoder.decode(bytes));
    if (!body || typeof body !== "object" || !("payload" in body) || typeof body.payload !== "string" || body.payload.length === 0) {
      return { status: 400 };
    }
    if (encoder.encode(body.payload).byteLength > 65536) return { status: 413 };
    return { payload: body.payload };
  } catch {
    return { status: 400 };
  }
}

async function handle(request: Request, env: Env): Promise<Response> {
  if (!env.ENCRYPTION_KEY || env.ENCRYPTION_KEY.length < 32) {
    return json({ error: "server_error" }, 500);
  }

  const userId = await authenticate(request, env);
  if (!userId) return unauthorized();

  const url = new URL(request.url);
  const segments = url.pathname.split("/").filter(Boolean);
  const isConnections = segments.length === 1 && segments[0] === "connections";
  const isProvider = segments.length === 2 && segments[0] === "connections";
  const isAccount = segments.length === 1 && segments[0] === "account";
  if (!isConnections && !isProvider && !isAccount) return json({ error: "not_found" }, 404);

  if (isConnections) {
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    const rows = await env.DB.prepare("SELECT provider, payload, updated_at FROM connections WHERE user_id = ?")
      .bind(userId)
      .all<ConnectionRow>();
    const providers: Record<string, { payload: string; updatedAt: number }> = {};
    for (const row of rows.results) {
      const payload = await decrypt(env.ENCRYPTION_KEY, userId, row.provider, row.payload);
      if (payload !== null) providers[row.provider] = { payload, updatedAt: row.updated_at };
    }
    return json({ providers });
  }

  if (isAccount) {
    if (request.method !== "DELETE") return json({ error: "method_not_allowed" }, 405);
    await env.DB.prepare("DELETE FROM connections WHERE user_id = ?").bind(userId).run();
    return json({ ok: true });
  }

  let provider: string;
  try {
    provider = decodeURIComponent(segments[1]);
  } catch {
    return json({ error: "bad_request" }, 400);
  }
  if (!allowedProviders.has(provider)) return json({ error: "bad_request" }, 400);
  if (request.method !== "PUT" && request.method !== "DELETE") return json({ error: "method_not_allowed" }, 405);

  if (request.method === "DELETE") {
    await env.DB.prepare("DELETE FROM connections WHERE user_id = ? AND provider = ?")
      .bind(userId, provider)
      .run();
    return json({ ok: true });
  }

  const parsed = await readPayload(request);
  if (parsed.status) {
    return json({ error: parsed.status === 413 ? "payload_too_large" : "bad_request" }, parsed.status);
  }

  const now = Math.floor(Date.now() / 1000);
  const encrypted = await encrypt(env.ENCRYPTION_KEY, userId, provider, parsed.payload!);
  const saved = await env.DB.prepare(
    "INSERT INTO connections (user_id, provider, payload, updated_at) VALUES (?, ?, ?, ?) " +
      "ON CONFLICT(user_id, provider) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at " +
      "WHERE connections.updated_at <= ? RETURNING updated_at",
  )
    .bind(userId, provider, encrypted, now, now - 5)
    .first<{ updated_at: number }>();
  if (!saved) return json({ error: "rate_limited" }, 429);
  return json({ ok: true, updatedAt: now });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch {
      return json({ error: "server_error" }, 500);
    }
  },
};
