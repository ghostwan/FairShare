/**
 * FairShare sync Worker — zero-knowledge transport for CRDT ops.
 *
 * Endpoints (per DESIGN.md §6.2):
 *   POST   /events/:eventId/ops                           push a batch of ops
 *   GET    /events/:eventId/ops?since=N                   pull ops with lamport > N
 *   PUT    /events/:eventId/devices/:deviceId/token       register a FCM token
 *   DELETE /events/:eventId/devices/:deviceId/token       remove a FCM token
 *
 * Auth (per DESIGN.md §7): Authorization: Bearer <hex(HMAC-SHA256(eventKey, eventId))>.
 * The Worker never sees `eventKey` itself; clients prove possession by sending the static
 * per-event bearer. Since the bearer is bound to `eventId`, leaking it only grants access
 * to that single event's encrypted log — and never to plaintext (AES-256-GCM happens
 * exclusively on devices).
 *
 * Storage is intentionally opaque: `nonce` and `ciphertext` are stored as BLOBs and
 * are never decoded server-side. Losing the D1 database leaks zero plaintext.
 *
 * FCM fan-out: after a successful POST /ops, the Worker pushes a data-only
 * notification to every device registered for that event except the senders in
 * the batch, so paired devices can pull immediately instead of polling.
 */

import { fcmFanOut, parseServiceAccount, type ServiceAccount } from "./fcm";
import {
    webPushFanOut,
    type VapidKeys,
    type WebPushSubscription,
} from "./web-push";

export interface Env {
    DB: D1Database;
    PULL_PAGE_SIZE: string;
    MAX_PUSH_BYTES: string;
    /**
     * Optional. JSON content of a Firebase service account private key
     * (`wrangler secret put FCM_SERVICE_ACCOUNT`). When absent, FCM
     * fan-out is silently skipped and clients fall back to manual /
     * resume-based syncs.
     */
    FCM_SERVICE_ACCOUNT?: string;
    /**
     * Optional. VAPID keypair for the Web Push fan-out (RFC 8292):
     *   - VAPID_PRIVATE_KEY: base64url-encoded JWK JSON of the P-256
     *     private key (must include `d`, `x`, `y`, `crv:"P-256"`,
     *     `kty:"EC"`). Stored as a Worker secret.
     *   - VAPID_PUBLIC_KEY: base64url-encoded uncompressed P-256
     *     point (65 bytes; the `applicationServerKey` clients pass to
     *     `pushManager.subscribe`). Exposed via `GET /web-push/key`.
     *   - VAPID_SUBJECT: `mailto:…` or absolute URL.
     * When any of them is absent, Web Push fan-out is skipped and the
     * webapp keeps relying on focus-driven catch-up syncs.
     */
    VAPID_PRIVATE_KEY?: string;
    VAPID_PUBLIC_KEY?: string;
    VAPID_SUBJECT?: string;
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
    nextSinceOp: string; // op_id tiebreaker for the (lamport, op_id) cursor
    hasMore: boolean;
}

const MAX_LAMPORT = Number.MAX_SAFE_INTEGER;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Conservative bound on FCM registration tokens; real tokens are ~163 chars. */
const MAX_FCM_TOKEN_LEN = 4096;
/** Bound on the deviceId path segment; mirrors the body validator in handlePush. */
const MAX_DEVICE_ID_LEN = 128;

export default {
    async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        // CORS preflight: respond before routing. Browsers send OPTIONS
        // with `Access-Control-Request-Headers: authorization, content-type`
        // before any cross-origin POST/GET that carries the Authorization
        // header. Without a 2xx here the actual request never goes out
        // and the JS fetch fails with a generic TypeError ("network
        // error"). Native Android clients don't preflight, which is why
        // this only ever broke the webapp.
        if (request.method === "OPTIONS") {
            return withCors(request, new Response(null, { status: 204 }));
        }
        try {
            const res = await route(request, env, ctx);
            return withCors(request, res);
        } catch (err) {
            // Last-resort guard so a code bug never leaks a stack trace to clients.
            console.error("Unhandled error", err);
            return withCors(request, jsonError(500, "internal_error"));
        }
    },
};

/**
 * Echo the request Origin (or `*` for tools without one) and advertise
 * the methods + headers used by the webapp. We don't use cookies so
 * `Access-Control-Allow-Credentials` stays off — the bearer travels in
 * Authorization, which is fine to expose to any origin.
 */
function withCors(request: Request, res: Response): Response {
    const origin = request.headers.get("origin") ?? "*";
    const headers = new Headers(res.headers);
    headers.set("access-control-allow-origin", origin);
    headers.set("vary", "Origin");
    headers.set(
        "access-control-allow-methods",
        "GET, POST, PUT, DELETE, OPTIONS",
    );
    headers.set(
        "access-control-allow-headers",
        "authorization, content-type",
    );
    headers.set("access-control-max-age", "86400");
    return new Response(res.body, {
        status: res.status,
        statusText: res.statusText,
        headers,
    });
}

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/" || url.pathname === "/health") {
        return new Response("fairshare-sync ok\n", { headers: { "content-type": "text/plain" } });
    }

    // Public, unauthenticated endpoint: the webapp fetches this once
    // when subscribing to Web Push so it can pass the right
    // `applicationServerKey` to `pushManager.subscribe`. We don't
    // gate it on the bearer because by definition the client doesn't
    // have an event yet at first install — and the public key is, well,
    // public.
    if (url.pathname === "/web-push/key") {
        if (!env.VAPID_PUBLIC_KEY) return jsonError(404, "web_push_not_configured");
        return Response.json({ publicKey: env.VAPID_PUBLIC_KEY });
    }

    const opsMatch = url.pathname.match(/^\/events\/([^/]+)\/ops\/?$/);
    if (opsMatch) {
        const eventId = decodeURIComponent(opsMatch[1]);
        if (!UUID_RE.test(eventId)) return jsonError(400, "bad_event_id");

        const bearer = readBearer(request);
        if (bearer == null) return jsonError(401, "missing_bearer");
        const auth = await checkBearer(eventId, bearer, request.method, env);
        if (!auth.ok) return jsonError(401, auth.code);

        if (request.method === "POST") return handlePush(eventId, bearer, request, env, ctx);
        if (request.method === "GET") return handlePull(eventId, url, env);
        return jsonError(405, "method_not_allowed");
    }

    const tokenMatch = url.pathname.match(/^\/events\/([^/]+)\/devices\/([^/]+)\/token\/?$/);
    if (tokenMatch) {
        const eventId = decodeURIComponent(tokenMatch[1]);
        const deviceId = decodeURIComponent(tokenMatch[2]);
        if (!UUID_RE.test(eventId)) return jsonError(400, "bad_event_id");
        if (deviceId.length === 0 || deviceId.length > MAX_DEVICE_ID_LEN) {
            return jsonError(400, "bad_device_id");
        }

        const bearer = readBearer(request);
        if (bearer == null) return jsonError(401, "missing_bearer");
        // PUT registers a token; the event row may not exist yet on the
        // server side if the device hasn't pushed any op (eg. a fresh
        // joiner that wants to be reachable before its first write).
        // Allow the bearer to bootstrap the verifier just like POST /ops.
        const auth = await checkBearer(eventId, bearer, request.method === "PUT" ? "POST" : request.method, env);
        if (!auth.ok) return jsonError(401, auth.code);

        if (request.method === "PUT") return handleRegisterToken(eventId, deviceId, bearer, request, env);
        if (request.method === "DELETE") return handleUnregisterToken(eventId, deviceId, env);
        return jsonError(405, "method_not_allowed");
    }

    const webPushMatch = url.pathname.match(/^\/events\/([^/]+)\/devices\/([^/]+)\/web-push\/?$/);
    if (webPushMatch) {
        const eventId = decodeURIComponent(webPushMatch[1]);
        const deviceId = decodeURIComponent(webPushMatch[2]);
        if (!UUID_RE.test(eventId)) return jsonError(400, "bad_event_id");
        if (deviceId.length === 0 || deviceId.length > MAX_DEVICE_ID_LEN) {
            return jsonError(400, "bad_device_id");
        }

        const bearer = readBearer(request);
        if (bearer == null) return jsonError(401, "missing_bearer");
        const auth = await checkBearer(eventId, bearer, request.method === "PUT" ? "POST" : request.method, env);
        if (!auth.ok) return jsonError(401, auth.code);

        if (request.method === "PUT") return handleRegisterWebPush(eventId, deviceId, bearer, request, env);
        if (request.method === "DELETE") return handleUnregisterWebPush(eventId, deviceId, env);
        return jsonError(405, "method_not_allowed");
    }

    return jsonError(404, "not_found");
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
    ctx: ExecutionContext,
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

    // Fan-out FCM notifications to every paired device of this event
    // except the senders in the current batch (a device shouldn't be
    // woken up to pull its own write). Fire-and-forget via waitUntil
    // so push latency stays dominated by the DB write, not by the
    // FCM round-trip. If FCM is not configured we simply skip.
    if (inserted > 0) {
        const senderIds = new Set(body.ops.map(op => op.deviceId));
        if (env.FCM_SERVICE_ACCOUNT) {
            ctx.waitUntil(notifyPairedDevices(eventId, senderIds, env));
        }
        if (env.VAPID_PRIVATE_KEY && env.VAPID_PUBLIC_KEY && env.VAPID_SUBJECT) {
            ctx.waitUntil(notifyWebPushSubscribers(eventId, senderIds, env));
        }
    }

    return Response.json({ inserted });
}

/**
 * Pulls every FCM token registered for `eventId` except the
 * `senderDeviceIds` set, posts a data-only push to each, and prunes
 * tokens that came back as UNREGISTERED / NOT_FOUND so they don't
 * waste fan-out cycles on the next push.
 */
async function notifyPairedDevices(
    eventId: string,
    senderDeviceIds: Set<string>,
    env: Env,
): Promise<void> {
    let sa: ServiceAccount;
    try {
        sa = parseServiceAccount(env.FCM_SERVICE_ACCOUNT!);
    } catch (err) {
        console.error("fcm_parse_service_account", err);
        return;
    }

    const rows = await env.DB
        .prepare("SELECT device_id, fcm_token FROM device_tokens WHERE event_id = ?1")
        .bind(eventId)
        .all<{ device_id: string; fcm_token: string }>();
    const targets = (rows.results ?? []).filter(r => !senderDeviceIds.has(r.device_id));
    if (targets.length === 0) return;

    let result;
    try {
        result = await fcmFanOut(sa, targets.map(t => t.fcm_token), { eventId });
    } catch (err) {
        console.error("fcm_fan_out_failed", err);
        return;
    }
    console.log(
        `fcm_fan_out event=${eventId} sent=${result.sent} failed=${result.failed} ` +
        `stale=${result.staleTokens.length}`,
    );

    if (result.staleTokens.length > 0) {
        const stmts = result.staleTokens.map(token =>
            env.DB
                .prepare("DELETE FROM device_tokens WHERE event_id = ?1 AND fcm_token = ?2")
                .bind(eventId, token),
        );
        await env.DB.batch(stmts);
    }
}

// ---------- PUT|DELETE /events/:id/devices/:deviceId/token ----------

interface RegisterTokenBody {
    fcmToken: string;
}

/**
 * Fan-out a wake-up push to every Web Push subscription of the event
 * (browsers/PWAs), excluding the senders. Mirrors `notifyPairedDevices`
 * but speaks the standard Web Push protocol (RFC 8030/8291/8292)
 * instead of FCM HTTP v1. Stale subscriptions (HTTP 410 Gone) are
 * pruned in the same pass.
 */
async function notifyWebPushSubscribers(
    eventId: string,
    senderDeviceIds: Set<string>,
    env: Env,
): Promise<void> {
    const vapid: VapidKeys = {
        privateKey: env.VAPID_PRIVATE_KEY!,
        publicKey: env.VAPID_PUBLIC_KEY!,
        subject: env.VAPID_SUBJECT!,
    };

    const rows = await env.DB
        .prepare(
            "SELECT device_id, endpoint, p256dh, auth FROM web_push_subscriptions " +
            "WHERE event_id = ?1",
        )
        .bind(eventId)
        .all<{ device_id: string; endpoint: string; p256dh: string; auth: string }>();

    const targets = (rows.results ?? [])
        .filter(r => !senderDeviceIds.has(r.device_id))
        .map<WebPushSubscription>(r => ({
            endpoint: r.endpoint,
            p256dh: r.p256dh,
            auth: r.auth,
        }));
    if (targets.length === 0) return;

    let result;
    try {
        result = await webPushFanOut(vapid, targets, { eventId });
    } catch (err) {
        console.error("web_push_fan_out_failed", err);
        return;
    }
    console.log(
        `web_push_fan_out event=${eventId} sent=${result.sent} failed=${result.failed} ` +
        `stale=${result.staleEndpoints.length}`,
    );

    if (result.staleEndpoints.length > 0) {
        const stmts = result.staleEndpoints.map(endpoint =>
            env.DB
                .prepare("DELETE FROM web_push_subscriptions WHERE event_id = ?1 AND endpoint = ?2")
                .bind(eventId, endpoint),
        );
        await env.DB.batch(stmts);
    }
}

interface RegisterWebPushBody {
    endpoint: string;
    p256dh: string;
    auth: string;
}

async function handleRegisterWebPush(
    eventId: string,
    deviceId: string,
    bearer: string,
    request: Request,
    env: Env,
): Promise<Response> {
    let body: RegisterWebPushBody;
    try {
        body = await request.json() as RegisterWebPushBody;
    } catch {
        return jsonError(400, "bad_json");
    }
    if (!body || typeof body.endpoint !== "string" ||
        typeof body.p256dh !== "string" || typeof body.auth !== "string") {
        return jsonError(400, "bad_shape");
    }
    const endpoint = body.endpoint.trim();
    const p256dh = body.p256dh.trim();
    const auth = body.auth.trim();
    if (endpoint.length === 0 || endpoint.length > 2048) return jsonError(400, "bad_endpoint");
    if (!/^https:\/\//i.test(endpoint)) return jsonError(400, "bad_endpoint_scheme");
    // Base64url sanity (length must roughly match 65 bytes / 16 bytes
    // post-decoding; we keep the upper bound loose).
    if (p256dh.length < 80 || p256dh.length > 200) return jsonError(400, "bad_p256dh");
    if (auth.length < 20 || auth.length > 64) return jsonError(400, "bad_auth");

    await registerBearerIfNeeded(eventId, bearer, env);

    await env.DB
        .prepare(
            "INSERT INTO web_push_subscriptions " +
            "(event_id, device_id, endpoint, p256dh, auth, updated_at) " +
            "VALUES (?1, ?2, ?3, ?4, ?5, ?6) " +
            "ON CONFLICT(event_id, device_id) DO UPDATE SET " +
            "endpoint = excluded.endpoint, " +
            "p256dh = excluded.p256dh, " +
            "auth = excluded.auth, " +
            "updated_at = excluded.updated_at",
        )
        .bind(eventId, deviceId, endpoint, p256dh, auth, Date.now())
        .run();

    return Response.json({ ok: true });
}

async function handleUnregisterWebPush(
    eventId: string,
    deviceId: string,
    env: Env,
): Promise<Response> {
    const result = await env.DB
        .prepare("DELETE FROM web_push_subscriptions WHERE event_id = ?1 AND device_id = ?2")
        .bind(eventId, deviceId)
        .run();
    return Response.json({ removed: result.meta.changes ?? 0 });
}

async function handleRegisterToken(
    eventId: string,
    deviceId: string,
    bearer: string,
    request: Request,
    env: Env,
): Promise<Response> {
    let body: RegisterTokenBody;
    try {
        body = await request.json() as RegisterTokenBody;
    } catch {
        return jsonError(400, "bad_json");
    }
    if (!body || typeof body.fcmToken !== "string") return jsonError(400, "bad_shape");
    const token = body.fcmToken.trim();
    if (token.length === 0 || token.length > MAX_FCM_TOKEN_LEN) {
        return jsonError(400, "bad_token");
    }

    // Same bootstrap as POST /ops: an event that only exists because
    // a device wants to register a push token must still get its
    // bearer verifier persisted so subsequent GETs work.
    await registerBearerIfNeeded(eventId, bearer, env);

    await env.DB
        .prepare(
            "INSERT INTO device_tokens (event_id, device_id, fcm_token, updated_at) " +
            "VALUES (?1, ?2, ?3, ?4) " +
            "ON CONFLICT(event_id, device_id) DO UPDATE SET " +
            "fcm_token = excluded.fcm_token, updated_at = excluded.updated_at",
        )
        .bind(eventId, deviceId, token, Date.now())
        .run();

    return Response.json({ ok: true });
}

async function handleUnregisterToken(
    eventId: string,
    deviceId: string,
    env: Env,
): Promise<Response> {
    const result = await env.DB
        .prepare("DELETE FROM device_tokens WHERE event_id = ?1 AND device_id = ?2")
        .bind(eventId, deviceId)
        .run();
    return Response.json({ removed: result.meta.changes ?? 0 });
}

// ---------- GET /events/:id/ops?since=N[&since_op=UUID] ----------

async function handlePull(eventId: string, url: URL, env: Env): Promise<Response> {
    const sinceStr = url.searchParams.get("since") ?? "0";
    const since = parseInt(sinceStr, 10);
    if (!Number.isFinite(since) || since < 0 || since > MAX_LAMPORT) {
        return jsonError(400, "bad_since");
    }
    // Composite cursor `(lamport, op_id)`. When absent, fall back to the
    // legacy strict `lamport > since` semantics so older clients keep
    // working. New clients always pass `since_op` and benefit from
    // correct paging when > PULL_PAGE_SIZE ops share the same Lamport
    // value (otherwise the last page at that lamport would be silently
    // dropped — `nextSince = lamport` + strict `lamport > since` would
    // skip every remaining op at exactly `lamport`).
    const sinceOpRaw = url.searchParams.get("since_op");
    const sinceOp = sinceOpRaw ?? "";
    if (sinceOp.length > 0 && !UUID_RE.test(sinceOp)) {
        return jsonError(400, "bad_since_op");
    }
    const pageSize = parseInt(env.PULL_PAGE_SIZE, 10);

    const stmt = sinceOp.length === 0
        ? env.DB
            .prepare(
                "SELECT op_id, lamport, device_id, nonce, ciphertext " +
                "FROM ops WHERE event_id = ?1 AND lamport > ?2 " +
                "ORDER BY lamport ASC, op_id ASC LIMIT ?3",
            )
            .bind(eventId, since, pageSize + 1)
        : env.DB
            .prepare(
                "SELECT op_id, lamport, device_id, nonce, ciphertext " +
                "FROM ops WHERE event_id = ?1 " +
                "AND (lamport > ?2 OR (lamport = ?2 AND op_id > ?3)) " +
                "ORDER BY lamport ASC, op_id ASC LIMIT ?4",
            )
            .bind(eventId, since, sinceOp, pageSize + 1);

    const result = await stmt.all<{
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
    const last = trimmed[trimmed.length - 1];
    const nextSince = last ? last.lamport : since;
    const nextSinceOp = last ? last.op_id : sinceOp;
    const payload: PullResponse = { ops, nextSince, nextSinceOp, hasMore };
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
