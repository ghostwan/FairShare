/**
 * FairShare sync Worker — zero-knowledge transport for CRDT ops.
 *
 * Endpoints (per DESIGN.md §6.2):
 *   POST /events/:eventId/ops           push a batch of {opId, lamport, deviceId, nonce, ciphertext}
 *   GET  /events/:eventId/ops?since=N   pull ops with lamport > N, ordered by (lamport, op_id)
 *
 * Auth (per DESIGN.md §7): Authorization: Bearer <hex(HMAC-SHA256(eventKey, eventId))>.
 * The Worker never sees `eventKey` itself; clients prove possession by sending the static
 * per-event bearer. Since the bearer is bound to `eventId`, leaking it only grants access
 * to that single event's encrypted log — and never to plaintext (AES-256-GCM happens
 * exclusively on devices).
 *
 * Storage is intentionally opaque: `nonce` and `ciphertext` are stored as BLOBs and
 * are never decoded server-side. Losing the D1 database leaks zero plaintext.
 */

export interface Env {
    DB: D1Database;
    PULL_PAGE_SIZE: string;
    MAX_PUSH_BYTES: string;
}

interface PushOp {
    opId: string;
    lamport: number;
    deviceId: string;
    nonce: string;       // base64
    ciphertext: string;  // base64
}

interface PushBody {
    ops: PushOp[];
}

interface PullOp {
    opId: string;
    lamport: number;
    deviceId: string;
    nonce: string;
    ciphertext: string;
}

interface PullResponse {
    ops: PullOp[];
    nextSince: number; // max lamport returned, or `since` if empty
    hasMore: boolean;
}

const MAX_LAMPORT = Number.MAX_SAFE_INTEGER;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default {
    async fetch(request: Request, env: Env): Promise<Response> {
        try {
            return await route(request, env);
        } catch (err) {
            // Last-resort guard so a code bug never leaks a stack trace to clients.
            console.error("Unhandled error", err);
            return jsonError(500, "internal_error");
        }
    },
};

async function route(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/" || url.pathname === "/health") {
        return new Response("fairshare-sync ok\n", { headers: { "content-type": "text/plain" } });
    }

    const match = url.pathname.match(/^\/events\/([^/]+)\/ops\/?$/);
    if (!match) return jsonError(404, "not_found");

    const eventId = decodeURIComponent(match[1]);
    if (!UUID_RE.test(eventId)) return jsonError(400, "bad_event_id");

    const bearer = readBearer(request);
    if (bearer == null) return jsonError(401, "missing_bearer");
    const auth = await checkBearer(eventId, bearer, request.method, env);
    if (!auth.ok) return jsonError(401, auth.code);

    if (request.method === "POST") return handlePush(eventId, bearer, request, env);
    if (request.method === "GET") return handlePull(eventId, url, env);
    return jsonError(405, "method_not_allowed");
}

// ---------- Auth ----------

function readBearer(request: Request): string | null {
    const header = request.headers.get("authorization");
    if (!header) return null;
    const match = header.match(/^Bearer\s+([0-9a-fA-F]{64})$/);
    return match ? match[1].toLowerCase() : null;
}

type AuthResult = { ok: true } | { ok: false; code: string };

/**
 * Verifies a bearer token for `eventId` against the per-event verifier
 * (`SHA-256(bearer)`) stored in D1.
 *
 * The verifier is registered lazily by the first successful POST (see
 * {@link registerBearerIfNeeded}). Until that happens, GETs are rejected
 * so an attacker can't probe arbitrary eventIds with arbitrary bearers.
 * POSTs against an unknown event are allowed through — the bearer will
 * be persisted as the event's verifier on first insert.
 */
async function checkBearer(
    eventId: string,
    bearer: string,
    method: string,
    env: Env,
): Promise<AuthResult> {
    const expected = await sha256Hex(bearer);
    const row = await env.DB
        .prepare("SELECT verifier FROM event_bearers WHERE event_id = ?1")
        .bind(eventId)
        .first<{ verifier: string }>();
    if (row == null) {
        if (method === "POST") return { ok: true };
        return { ok: false, code: "unknown_event" };
    }
    return constantTimeEqual(row.verifier, expected)
        ? { ok: true }
        : { ok: false, code: "bad_bearer" };
}

async function registerBearerIfNeeded(
    eventId: string,
    bearer: string,
    env: Env,
): Promise<void> {
    const verifier = await sha256Hex(bearer);
    await env.DB
        .prepare("INSERT OR IGNORE INTO event_bearers (event_id, verifier, created_at) VALUES (?1, ?2, ?3)")
        .bind(eventId, verifier, Date.now())
        .run();
}

// ---------- POST /events/:id/ops ----------

async function handlePush(
    eventId: string,
    bearer: string,
    request: Request,
    env: Env,
): Promise<Response> {
    const maxBytes = parseInt(env.MAX_PUSH_BYTES, 10);
    const raw = await request.text();
    if (raw.length > maxBytes) return jsonError(413, "payload_too_large");

    let body: PushBody;
    try {
        body = JSON.parse(raw);
    } catch {
        return jsonError(400, "bad_json");
    }
    if (!body || !Array.isArray(body.ops)) return jsonError(400, "bad_shape");
    if (body.ops.length === 0) {
        // Treat an empty push as a no-op acknowledgement — useful for clients
        // that want to register a bearer without actually having ops to send.
        await registerBearerIfNeeded(eventId, bearer, env);
        return Response.json({ inserted: 0 });
    }
    if (body.ops.length > 1000) return jsonError(400, "too_many_ops");

    // Validate each op shape before touching the DB so a single bad entry does
    // not leave the batch half-applied.
    for (const op of body.ops) {
        if (typeof op.opId !== "string" || !UUID_RE.test(op.opId)) return jsonError(400, "bad_op_id");
        if (typeof op.lamport !== "number" || op.lamport < 0 || op.lamport > MAX_LAMPORT) {
            return jsonError(400, "bad_lamport");
        }
        if (typeof op.deviceId !== "string" || op.deviceId.length === 0 || op.deviceId.length > 128) {
            return jsonError(400, "bad_device_id");
        }
        if (typeof op.nonce !== "string" || !isBase64(op.nonce)) return jsonError(400, "bad_nonce");
        if (typeof op.ciphertext !== "string" || !isBase64(op.ciphertext)) return jsonError(400, "bad_ciphertext");
    }

    await registerBearerIfNeeded(eventId, bearer, env);

    const now = Date.now();
    const stmts = body.ops.map(op =>
        env.DB
            .prepare(
                "INSERT OR IGNORE INTO ops " +
                "(event_id, op_id, lamport, device_id, nonce, ciphertext, received_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            )
            .bind(
                eventId,
                op.opId,
                op.lamport,
                op.deviceId,
                base64ToBytes(op.nonce),
                base64ToBytes(op.ciphertext),
                now,
            ),
    );
    const results = await env.DB.batch(stmts);
    const inserted = results.reduce((acc, r) => acc + (r.meta.changes ?? 0), 0);

    return Response.json({ inserted });
}

// ---------- GET /events/:id/ops?since=N ----------

async function handlePull(eventId: string, url: URL, env: Env): Promise<Response> {
    const sinceStr = url.searchParams.get("since") ?? "0";
    const since = parseInt(sinceStr, 10);
    if (!Number.isFinite(since) || since < 0 || since > MAX_LAMPORT) {
        return jsonError(400, "bad_since");
    }
    const pageSize = parseInt(env.PULL_PAGE_SIZE, 10);

    const result = await env.DB
        .prepare(
            "SELECT op_id, lamport, device_id, nonce, ciphertext " +
            "FROM ops WHERE event_id = ?1 AND lamport > ?2 " +
            "ORDER BY lamport ASC, op_id ASC LIMIT ?3",
        )
        .bind(eventId, since, pageSize + 1)
        .all<{
            op_id: string;
            lamport: number;
            device_id: string;
            nonce: ArrayBuffer | Uint8Array;
            ciphertext: ArrayBuffer | Uint8Array;
        }>();
    const rows = result.results ?? [];
    const hasMore = rows.length > pageSize;
    const trimmed = hasMore ? rows.slice(0, pageSize) : rows;

    const ops: PullOp[] = trimmed.map(r => ({
        opId: r.op_id,
        lamport: r.lamport,
        deviceId: r.device_id,
        nonce: bytesToBase64(toBytes(r.nonce)),
        ciphertext: bytesToBase64(toBytes(r.ciphertext)),
    }));
    const nextSince = ops.length > 0 ? ops[ops.length - 1].lamport : since;
    const payload: PullResponse = { ops, nextSince, hasMore };
    return Response.json(payload);
}

// ---------- Helpers ----------

function jsonError(status: number, code: string): Response {
    return new Response(JSON.stringify({ error: code }), {
        status,
        headers: { "content-type": "application/json" },
    });
}

async function sha256Hex(input: string): Promise<string> {
    const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
    return bytesToHex(new Uint8Array(buf));
}

function constantTimeEqual(a: string, b: string): boolean {
    if (a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

function isBase64(s: string): boolean {
    // Permits both standard and URL-safe base64; ciphertext blobs are reasonably small.
    return /^[A-Za-z0-9+/_-]*=*$/.test(s) && s.length <= 2_000_000;
}

function base64ToBytes(s: string): Uint8Array {
    // Normalize URL-safe alphabet then use atob.
    const normalized = s.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
    const bin = atob(padded);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}

function bytesToBase64(bytes: Uint8Array): string {
    let bin = "";
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
}

function bytesToHex(bytes: Uint8Array): string {
    let out = "";
    for (let i = 0; i < bytes.length; i++) out += bytes[i].toString(16).padStart(2, "0");
    return out;
}

function toBytes(blob: ArrayBuffer | Uint8Array): Uint8Array {
    return blob instanceof Uint8Array ? blob : new Uint8Array(blob);
}
