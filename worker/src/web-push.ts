/**
 * Web Push fan-out for Cloudflare Workers.
 *
 * Sister of `fcm.ts` for browsers: speaks plain Web Push (RFC 8030 +
 * RFC 8291 `aes128gcm` content encoding + RFC 8292 VAPID auth) instead
 * of going through a vendor SDK. The webapp's service worker is the
 * one that registered the {@link PushSubscription} we POST to here.
 *
 * VAPID keypair:
 *   - VAPID_PRIVATE_KEY  (secret): base64url-encoded raw P-256 scalar,
 *     32 bytes. Same format `openssl ecparam -genkey | openssl ec
 *     -noout -text` prints as "priv:" once you strip whitespace and
 *     re-encode. We import it as a JWK so WebCrypto is happy.
 *   - VAPID_PUBLIC_KEY   (var): base64url-encoded uncompressed P-256
 *     point (65 bytes, leading 0x04). Sent to clients via
 *     `GET /web-push/key` so they can pass it to
 *     `pushManager.subscribe({ applicationServerKey })`.
 *   - VAPID_SUBJECT      (var): `mailto:…` or absolute URL identifying
 *     the operator. Required by browser push services to contact us
 *     if a key gets abused.
 *
 * Payload encryption is per-subscription: we generate a fresh ECDH
 * P-256 keypair (the "AS" key in RFC 8291), derive a 16-byte CEK and
 * 12-byte nonce from the shared secret + the subscription's `auth`
 * secret via HKDF-SHA256, AES-128-GCM the padded payload, and frame
 * the result as `salt(16) || rs(4) || idlen(1=65) || keyid(65) || ct`.
 */

import { base64UrlEncodeBytes, base64UrlEncodeStr } from "./encoding";

export interface VapidKeys {
    /** Base64url of the raw P-256 private scalar (32 bytes). */
    privateKey: string;
    /** Base64url of the uncompressed P-256 public point (65 bytes). */
    publicKey: string;
    /** `mailto:…` or URL identifying us to the push service. */
    subject: string;
}

export interface WebPushSubscription {
    endpoint: string;
    /** Base64url of the uncompressed P-256 user agent public key (65 bytes). */
    p256dh: string;
    /** Base64url of the 16-byte auth secret. */
    auth: string;
}

export interface WebPushFanOutResult {
    sent: number;
    failed: number;
    /** Endpoints that came back 404/410 — the caller should delete them. */
    staleEndpoints: string[];
}

/**
 * Send the same opaque payload to every subscription. We deliberately
 * keep the payload short ({@link eventId}) — the SW will pull the
 * actual ops over the regular sync channel; the push only exists to
 * wake the SW up.
 */
export async function webPushFanOut(
    vapid: VapidKeys,
    subscriptions: WebPushSubscription[],
    payload: Record<string, string>,
): Promise<WebPushFanOutResult> {
    if (subscriptions.length === 0) {
        return { sent: 0, failed: 0, staleEndpoints: [] };
    }
    const body = new TextEncoder().encode(JSON.stringify(payload));
    const vapidKey = await importVapidPrivateKey(vapid.privateKey);

    const results = await Promise.allSettled(
        subscriptions.map((sub) => sendOne(vapid, vapidKey, sub, body)),
    );

    const staleEndpoints: string[] = [];
    let sent = 0;
    let failed = 0;
    for (let i = 0; i < results.length; i++) {
        const r = results[i];
        if (r.status === "rejected") {
            failed += 1;
            console.log(
                `webpush:reject endpoint=${shortEndpoint(subscriptions[i].endpoint)} ` +
                `err=${String(r.reason).slice(0, 200)}`,
            );
            continue;
        }
        if (r.value.ok) {
            sent += 1;
        } else {
            failed += 1;
            if (r.value.stale) staleEndpoints.push(subscriptions[i].endpoint);
        }
    }
    return { sent, failed, staleEndpoints };
}

async function sendOne(
    vapid: VapidKeys,
    vapidPrivate: CryptoKey,
    sub: WebPushSubscription,
    payload: Uint8Array,
): Promise<{ ok: boolean; stale?: boolean }> {
    const encrypted = await encryptPayload(sub, payload);
    const jwt = await signVapidJwt(vapid, vapidPrivate, originOf(sub.endpoint));

    const resp = await fetch(sub.endpoint, {
        method: "POST",
        headers: {
            "content-encoding": "aes128gcm",
            "content-type": "application/octet-stream",
            "content-length": String(encrypted.byteLength),
            ttl: "86400",
            urgency: "high",
            authorization: `vapid t=${jwt}, k=${vapid.publicKey}`,
        },
        body: encrypted,
    });
    const tag = shortEndpoint(sub.endpoint);
    if (resp.ok || resp.status === 201 || resp.status === 202) {
        console.log(`webpush:ok endpoint=${tag} status=${resp.status}`);
        return { ok: true };
    }
    const text = await resp.text().catch(() => "");
    const stale = resp.status === 404 || resp.status === 410;
    console.log(
        `webpush:fail endpoint=${tag} status=${resp.status} stale=${stale} ` +
        `body=${text.slice(0, 200)}`,
    );
    return { ok: false, stale };
}

// ---------- VAPID JWT (ES256, P-256, SHA-256) ----------

/**
 * Imports the VAPID private scalar as a P-256 signing key. We accept
 * the bare 32-byte `d` value (base64url) because that's what
 * `openssl ec -text` exposes — we synthesise the `x`/`y` JWK fields
 * by re-decoding the matching public key. Both halves must come from
 * the same keypair (we verify nothing here; a mismatch surfaces as a
 * 401 from the push service on the first send).
 */
async function importVapidPrivateKey(privateKeyB64Url: string): Promise<CryptoKey> {
    const d = base64UrlDecodeBytes(privateKeyB64Url);
    if (d.length !== 32) {
        throw new Error(`vapid_bad_private_key_len=${d.length}`);
    }
    // The webcrypto JWK importer for ECDSA requires x and y. We can
    // recover them from the configured public key — but to keep this
    // module pure we instead derive the JWK on the caller's side. The
    // simplest contract: caller hands us `privateKey` containing the
    // full JWK as base64url(JSON). We support both legacy raw-d and
    // JWK-JSON.
    const maybeJwk = tryParseJwk(privateKeyB64Url);
    if (maybeJwk) {
        return crypto.subtle.importKey(
            "jwk",
            maybeJwk,
            { name: "ECDSA", namedCurve: "P-256" },
            false,
            ["sign"],
        );
    }
    throw new Error(
        "vapid_private_key_format: expected JWK JSON (base64url-encoded) — " +
        "raw scalars cannot be imported without x/y components",
    );
}

function tryParseJwk(b64: string): JsonWebKey | null {
    try {
        const json = new TextDecoder().decode(base64UrlDecodeBytes(b64));
        const obj = JSON.parse(json) as JsonWebKey;
        if (obj && obj.kty === "EC" && obj.crv === "P-256" && typeof obj.d === "string") {
            return obj;
        }
        return null;
    } catch {
        return null;
    }
}

async function signVapidJwt(
    vapid: VapidKeys,
    privateKey: CryptoKey,
    audience: string,
): Promise<string> {
    const header = { typ: "JWT", alg: "ES256" };
    const nowSec = Math.floor(Date.now() / 1000);
    const claim = {
        aud: audience,
        exp: nowSec + 12 * 3600, // RFC 8292 caps at 24h; 12 is plenty.
        sub: vapid.subject,
    };
    const head = base64UrlEncodeStr(JSON.stringify(header));
    const body = base64UrlEncodeStr(JSON.stringify(claim));
    const signingInput = new TextEncoder().encode(`${head}.${body}`);

    // WebCrypto's ECDSA sign returns the raw r||s concatenation (64 bytes
    // for P-256) which is exactly the JOSE encoding ES256 wants — no
    // DER conversion needed.
    const sig = await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        privateKey,
        signingInput,
    );
    return `${head}.${body}.${base64UrlEncodeBytes(new Uint8Array(sig))}`;
}

function originOf(endpoint: string): string {
    const u = new URL(endpoint);
    return `${u.protocol}//${u.host}`;
}

// ---------- aes128gcm payload encryption (RFC 8291 + RFC 8188) ----------

const RECORD_SIZE = 4096;

async function encryptPayload(
    sub: WebPushSubscription,
    payload: Uint8Array,
): Promise<Uint8Array> {
    const uaPublicRaw = base64UrlDecodeBytes(sub.p256dh);
    const authSecret = base64UrlDecodeBytes(sub.auth);
    if (uaPublicRaw.length !== 65 || uaPublicRaw[0] !== 0x04) {
        throw new Error(`webpush_bad_p256dh_len=${uaPublicRaw.length}`);
    }
    if (authSecret.length !== 16) {
        throw new Error(`webpush_bad_auth_len=${authSecret.length}`);
    }
    if (payload.length + 16 + 1 > RECORD_SIZE) {
        // Single-record payloads only; the webapp push is tiny so this is fine.
        throw new Error(`webpush_payload_too_large=${payload.length}`);
    }

    // Fresh ephemeral ECDH keypair (the "AS" key in RFC 8291).
    // Cast: Workers' types report `generateKey` as a union with
    // `CryptoKey` even for asymmetric algos; for ECDH it always
    // returns a CryptoKeyPair at runtime.
    const asPair = (await crypto.subtle.generateKey(
        { name: "ECDH", namedCurve: "P-256" },
        true,
        ["deriveBits"],
    )) as CryptoKeyPair;
    const asPublicRaw = new Uint8Array(
        (await crypto.subtle.exportKey("raw", asPair.publicKey)) as ArrayBuffer,
    );

    const uaPublicKey = await crypto.subtle.importKey(
        "raw",
        uaPublicRaw,
        { name: "ECDH", namedCurve: "P-256" },
        false,
        [],
    );

    // ECDH(AS_priv, UA_pub) → 32-byte shared secret.
    // Cast: workers-types renames the WebCrypto `public` key to
    // `$public` in its DeriveKey algorithm interface; the runtime
    // still expects the spec-compliant name.
    const sharedBits = await crypto.subtle.deriveBits(
        { name: "ECDH", public: uaPublicKey } as unknown as SubtleCryptoDeriveKeyAlgorithm,
        asPair.privateKey,
        256,
    );
    const ikm = new Uint8Array(sharedBits);

    // First HKDF: PRK_key = HMAC(auth, IKM); key = HKDF-Expand(PRK_key,
    // info="WebPush: info\0" || ua_pub || as_pub, L=32).
    const prkKey = await hkdf(
        authSecret,
        ikm,
        concat(
            new TextEncoder().encode("WebPush: info\0"),
            uaPublicRaw,
            asPublicRaw,
        ),
        32,
    );

    const salt = crypto.getRandomValues(new Uint8Array(16));
    const cek = await hkdf(
        salt,
        prkKey,
        new TextEncoder().encode("Content-Encoding: aes128gcm\0"),
        16,
    );
    const nonce = await hkdf(
        salt,
        prkKey,
        new TextEncoder().encode("Content-Encoding: nonce\0"),
        12,
    );

    // Append the last-record delimiter byte (0x02) per RFC 8188 §2.
    const padded = new Uint8Array(payload.length + 1);
    padded.set(payload, 0);
    padded[payload.length] = 0x02;

    const cekKey = await crypto.subtle.importKey(
        "raw",
        cek,
        { name: "AES-GCM" },
        false,
        ["encrypt"],
    );
    const ciphertext = new Uint8Array(
        await crypto.subtle.encrypt(
            { name: "AES-GCM", iv: nonce },
            cekKey,
            padded,
        ),
    );

    // aes128gcm header: salt(16) || rs(4 BE) || idlen(1) || keyid(idlen).
    const header = new Uint8Array(16 + 4 + 1 + asPublicRaw.length);
    header.set(salt, 0);
    new DataView(header.buffer, header.byteOffset, header.byteLength)
        .setUint32(16, RECORD_SIZE, false);
    header[20] = asPublicRaw.length;
    header.set(asPublicRaw, 21);

    return concat(header, ciphertext);
}

// ---------- HKDF + helpers ----------

/**
 * HKDF-SHA256 in one shot. WebCrypto exposes HKDF as a key-derivation
 * algorithm so we have to wrap the IKM in a CryptoKey first.
 */
async function hkdf(
    salt: Uint8Array,
    ikm: Uint8Array,
    info: Uint8Array,
    length: number,
): Promise<Uint8Array> {
    const ikmKey = await crypto.subtle.importKey(
        "raw",
        ikm,
        { name: "HKDF" },
        false,
        ["deriveBits"],
    );
    const bits = await crypto.subtle.deriveBits(
        { name: "HKDF", hash: "SHA-256", salt, info },
        ikmKey,
        length * 8,
    );
    return new Uint8Array(bits);
}

function concat(...arrs: Uint8Array[]): Uint8Array {
    let total = 0;
    for (const a of arrs) total += a.length;
    const out = new Uint8Array(total);
    let off = 0;
    for (const a of arrs) {
        out.set(a, off);
        off += a.length;
    }
    return out;
}

function base64UrlDecodeBytes(s: string): Uint8Array {
    const normalized = s.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
    const bin = atob(padded);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}

function shortEndpoint(endpoint: string): string {
    try {
        const u = new URL(endpoint);
        const tail = u.pathname.slice(-12);
        return `${u.host}…${tail}`;
    } catch {
        return endpoint.slice(0, 40);
    }
}
