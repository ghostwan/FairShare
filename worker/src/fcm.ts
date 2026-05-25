/**
 * FCM HTTP v1 client for Cloudflare Workers.
 *
 * We use the modern HTTP v1 API (not the legacy /fcm/send) because the
 * legacy API was deprecated June 2024. Authentication is OAuth2 access
 * tokens minted from a Google service account private key — we sign a
 * JWT (RS256) with the service account's private key, exchange it at
 * the Google token endpoint for an access token valid for 1 hour, and
 * cache it in module-level state (re-fetched on cache miss / expiry).
 *
 * Workers isolates are reused across requests, so the cache is hit on
 * 99%+ of pushes; a cold isolate or a one-hour rollover triggers a
 * single ~150ms token mint.
 */

import { base64UrlEncodeBytes, base64UrlEncodeStr, pemToDer } from "./encoding";

export interface ServiceAccount {
    project_id: string;
    private_key: string;
    client_email: string;
    token_uri: string;
}

/**
 * Parses a service account JSON (typically delivered via
 * `wrangler secret put FCM_SERVICE_ACCOUNT`). Trims the PEM newlines
 * that wrangler sometimes escapes as `\\n`.
 */
export function parseServiceAccount(raw: string): ServiceAccount {
    const parsed = JSON.parse(raw) as ServiceAccount;
    parsed.private_key = parsed.private_key.replace(/\\n/g, "\n");
    return parsed;
}

interface CachedToken {
    token: string;
    /** UNIX seconds at which the token expires. */
    expiresAt: number;
}

let cachedToken: CachedToken | null = null;

async function getAccessToken(sa: ServiceAccount): Promise<string> {
    const nowSec = Math.floor(Date.now() / 1000);
    // 60s safety margin so a token nearing expiry is refreshed before
    // a request actually fails with 401.
    if (cachedToken && cachedToken.expiresAt > nowSec + 60) {
        return cachedToken.token;
    }

    const header = { alg: "RS256", typ: "JWT" };
    const claim = {
        iss: sa.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: sa.token_uri,
        exp: nowSec + 3600,
        iat: nowSec,
    };
    const jwt = await signRs256(header, claim, sa.private_key);

    const resp = await fetch(sa.token_uri, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body:
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer" +
            "&assertion=" + encodeURIComponent(jwt),
    });
    if (!resp.ok) {
        const text = await resp.text();
        throw new Error(`fcm_token_${resp.status}: ${text.slice(0, 200)}`);
    }
    const json = await resp.json() as { access_token: string; expires_in: number };
    cachedToken = { token: json.access_token, expiresAt: nowSec + json.expires_in };
    return json.access_token;
}

async function signRs256(header: object, claim: object, pkPem: string): Promise<string> {
    const head = base64UrlEncodeStr(JSON.stringify(header));
    const body = base64UrlEncodeStr(JSON.stringify(claim));
    const unsigned = `${head}.${body}`;

    const der = pemToDer(pkPem);
    const key = await crypto.subtle.importKey(
        "pkcs8",
        der,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"],
    );
    const sig = await crypto.subtle.sign(
        "RSASSA-PKCS1-v1_5",
        key,
        new TextEncoder().encode(unsigned),
    );
    return `${unsigned}.${base64UrlEncodeBytes(new Uint8Array(sig))}`;
}

export interface FcmSendResult {
    sent: number;
    failed: number;
    /** FCM tokens that came back as UNREGISTERED / INVALID_ARGUMENT. */
    staleTokens: string[];
}

/**
 * Sends a data-only push to each token. We deliberately use data-only
 * (no `notification` block) so the OS doesn't render anything: the
 * Android app's `FirebaseMessagingService` will receive the payload,
 * trigger a one-shot sync, and decide for itself whether to surface a
 * user-visible notification.
 *
 * Failures are isolated per token — one bad token does not abort the
 * fan-out. Stale tokens (UNREGISTERED / NOT_FOUND) are reported so the
 * caller can delete them from the registry on the next pass.
 */
export async function fcmFanOut(
    sa: ServiceAccount,
    tokens: string[],
    data: Record<string, string>,
): Promise<FcmSendResult> {
    if (tokens.length === 0) return { sent: 0, failed: 0, staleTokens: [] };

    const accessToken = await getAccessToken(sa);
    const endpoint = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;
    const staleTokens: string[] = [];
    let sent = 0;
    let failed = 0;

    // FCM HTTP v1 sends one message per token (no multicast). We fire
    // them in parallel; pushes are tiny and the typical event has
    // 2-5 paired devices so concurrency stays bounded.
    const results = await Promise.allSettled(
        tokens.map(async (token) => {
            const body = JSON.stringify({
                message: {
                    token,
                    data,
                    android: { priority: "high" },
                },
            });
            const resp = await fetch(endpoint, {
                method: "POST",
                headers: {
                    authorization: `Bearer ${accessToken}`,
                    "content-type": "application/json",
                },
                body,
            });
            if (resp.ok) return { ok: true as const };
            const text = await resp.text();
            const isStale = resp.status === 404 ||
                /UNREGISTERED|NOT_FOUND|INVALID_ARGUMENT/.test(text);
            return { ok: false as const, isStale, token };
        }),
    );
    for (const r of results) {
        if (r.status === "rejected") {
            failed += 1;
            continue;
        }
        if (r.value.ok) {
            sent += 1;
        } else {
            failed += 1;
            if (r.value.isStale) staleTokens.push(r.value.token);
        }
    }
    return { sent, failed, staleTokens };
}
